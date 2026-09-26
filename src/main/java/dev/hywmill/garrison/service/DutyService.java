package dev.hywmill.garrison.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.PerfCounters;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.duty.DutyAllocator;
import dev.hywmill.garrison.duty.DutyMotion;
import dev.hywmill.garrison.duty.DutyPlan;
import dev.hywmill.garrison.duty.DutyQuota;
import dev.hywmill.garrison.duty.DutyTable;
import dev.hywmill.garrison.duty.DutyTables;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.defense.AlertState;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * M4 standing duties: once per duty interval per active village (staggered), allocate standing
 * duties over the living garrison and move loaded units on standing duty by setting their HYW
 * home, one hop at a time, on loaded ground only. Nothing is scanned: units are found by UUID
 * from the roster, the layout is read only every {@code layoutRecheckTicks}, and posts and routes
 * are recomputed only when the layout changes. Units the M2 deployment owns (DEPLOYED, RETURNING)
 * and raid contingents are left alone.
 */
public final class DutyService {
    private final PerfCounters perf;
    private final Map<UUID, Rt> villages = new HashMap<>();

    /** Runtime (not persisted) duty state of one village. Everything here is recomputable. */
    static final class Rt {
        @Nullable DutyPlan plan;
        long layoutCheckedTick = Long.MIN_VALUE;
        @Nullable Object tablesSeen;
        MilitaryTier tier;
        String culture = "";
        long allocationSig;
        DutyQuota quota = new DutyQuota(0, 0, 0, 0);
    }

    public DutyService(PerfCounters perf) {
        this.perf = perf;
    }

    public static boolean enabled() {
        return HywMillConfig.DUTIES_ENABLED.get();
    }

    /** Called for every active village with a record, once per duty interval (staggered). */
    public void tick(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, long tick) {
        GarrisonRoster r = rec.hywRoster;
        UnitProvider units = Services.units();
        SettlementSource source = Services.settlements();
        if (r == null || units == null || source == null || !enabled()) {
            return;
        }
        long t0 = perf.start();
        Rt rt = villages.computeIfAbsent(rec.villageId, k -> new Rt());
        DutyTables tables = DutyTables.current();
        DutyTable table = tables.forCulture(rec.culture);
        if (!replan(overworld, source, rec, rt, table, tables, tick)) {
            perf.stop("duty.tick", t0);
            return;
        }
        DutyPlan plan = rt.plan;
        boolean changed = allocate(rec, r, rt, table, plan, tick);

        AlertState alert = alertState(rec.villageId);
        boolean calm = alert == AlertState.CALM;
        Map<Integer, List<UUID>> pairs = new HashMap<>();
        for (RosterEntry e : r.entries()) {
            if (e.assignedDuty == Duty.SENTRY && e.dutyIndex >= 0 && available(e)) {
                pairs.computeIfAbsent(e.dutyIndex, k -> new ArrayList<>()).add(e.rosterId);
            }
        }
        pairs.values().forEach(l -> l.sort(Comparator.naturalOrder()));
        for (RosterEntry e : r.entries()) {
            if (!(e.state() == UnitState.GARRISONED || e.state() == UnitState.RECOVERED || e.state() == UnitState.SPAWNED)
                    || e.duty == Duty.RAID || e.entityUuid == null) {
                continue;
            }
            if (e.duty != e.assignedDuty) {
                e.duty = e.assignedDuty; // back from DEFENSE/RETURNING (or an M3 save): resume the standing duty
                changed = true;
            }
            Entity ent = GarrisonService.find(overworld.getServer(), e.entityUuid);
            if (ent == null || !ent.isAlive() || !(ent.level() instanceof ServerLevel level)) {
                continue;
            }
            int member = e.assignedDuty == Duty.SENTRY ? Math.max(0, pairs.getOrDefault(e.dutyIndex, List.of()).indexOf(e.rosterId)) : 0;
            DutyMotion.Ctx ctx = new DutyMotion.Ctx(table.move(), table.scout(), rec.center, calm, member, rt.quota.patrol());
            int stepBefore = e.dutyStep;
            BlockPos goal = DutyMotion.goal(e, ent.getX(), ent.getZ(), plan, ctx, tick);
            BlockPos target = hopTarget(level, ent, goal, table.move().maxHop());
            if (target == null) {
                DutyMotion.blocked(e, plan, tick);
            } else {
                BlockPos home = units.home(ent);
                if (home == null || home.distSqr(target) > 2) {
                    units.setHome(ent, target);
                }
            }
            changed |= e.dutyStep != stepBefore;
        }
        if (changed) {
            ledger.setDirty();
        }
        perf.stop("duty.tick", t0);
    }

    /** Reads the layout when due and recomputes posts and routes if it (or the data) changed. False if there is no plan. */
    private boolean replan(ServerLevel overworld, SettlementSource source, VillageRecord rec, Rt rt, DutyTable table, DutyTables tables,
                           long tick) {
        boolean due = rt.plan == null || tick - rt.layoutCheckedTick >= HywMillConfig.DUTY_LAYOUT_RECHECK.get()
                || rt.tablesSeen != tables || rt.tier != rec.tier || !rt.culture.equals(rec.culture);
        if (!due) {
            return true;
        }
        rt.layoutCheckedTick = tick;
        long t0 = perf.start();
        Optional<SettlementSource.Layout> layout = source.layout(overworld, rec.villageId);
        perf.stop("duty.layout", t0);
        if (layout.isEmpty()) {
            return rt.plan != null;
        }
        long key = layout.get().key();
        if (rt.plan == null || rt.plan.key() != key || rt.tablesSeen != tables || rt.tier != rec.tier || !rt.culture.equals(rec.culture)) {
            rt.plan = DutyPlan.of(layout.get(), rec.villageId, table.move(), table.scout());
            rt.tablesSeen = tables;
            rt.tier = rec.tier;
            rt.culture = rec.culture;
            rt.allocationSig = 0;
            HmLog.info("Duty plan for village '{}' ({} {}): {} sentry post(s), patrol of {} waypoint(s), {} scout post(s), {} muster point(s), reserve at {}",
                    rec.name, rec.culture, rec.tier, rt.plan.sentryPosts().size(), rt.plan.patrol().size(), rt.plan.scoutPosts().size(),
                    rt.plan.muster().size(), rt.plan.reserve().toShortString());
        }
        return true;
    }

    /** Units counted for standing duties: living and bound, not MISSING, not away on a raid. */
    static boolean available(RosterEntry e) {
        UnitState s = e.state();
        return (s == UnitState.SPAWNED || s == UnitState.GARRISONED || s == UnitState.RECOVERED || s == UnitState.DEPLOYED
                || s == UnitState.RETURNING) && e.duty != Duty.RAID;
    }

    /** Re-runs the allocation when the available units or the plan changed. Returns true if any duty changed. */
    private boolean allocate(VillageRecord rec, GarrisonRoster r, Rt rt, DutyTable table, DutyPlan plan, long tick) {
        List<DutyAllocator.Candidate> cands = new ArrayList<>();
        long sig = plan.key() * 31 + rec.tier.ordinal();
        Map<String, UnitSpec> specs = GarrisonTables.current().units();
        for (RosterEntry e : r.entries()) {
            if (available(e)) {
                UnitSpec spec = specs.get(e.unitKey);
                cands.add(new DutyAllocator.Candidate(e.rosterId, spec != null ? spec.unitClass() : UnitClass.LINE, e.assignedDuty, e.dutyIndex));
                sig = sig * 1_000_003L + e.rosterId.hashCode();
            }
        }
        sig = sig * 31 + cands.size();
        if (sig == rt.allocationSig) {
            return false;
        }
        rt.allocationSig = sig;
        DutyQuota q = DutyQuota.of(table.tier(rec.tier), cands.size(), plan.sentryPosts().size());
        rt.quota = q;
        Map<UUID, DutyAllocator.Assignment> out = DutyAllocator.allocate(cands, q);
        boolean changed = false;
        List<String> moves = new ArrayList<>();
        for (RosterEntry e : r.entries()) {
            DutyAllocator.Assignment a = out.get(e.rosterId);
            if (a == null || (a.duty() == e.assignedDuty && a.index() == e.dutyIndex)) {
                continue;
            }
            moves.add(e.shortId() + " " + e.assignedDuty + "->" + a.duty() + (a.index() >= 0 ? "#" + a.index() : ""));
            e.assignedDuty = a.duty();
            e.dutyIndex = a.index();
            DutyMotion.start(e, plan, new DutyMotion.Ctx(table.move(), table.scout(), rec.center, true, 0, q.patrol()), tick);
            if (e.duty.standing()) {
                e.duty = a.duty();
            }
            changed = true;
        }
        if (changed) {
            HmLog.info("Duties of village '{}' ({} available: {} sentry pair(s), {} patrol, {} scout(s), {} reserve): {}", rec.name,
                    cands.size(), q.sentryPairs(), q.patrol(), q.scouts(), q.reserve(), moves);
        }
        return changed;
    }

    /**
     * The home to set for one move towards {@code goal}: at most {@code maxHop} away, on standable
     * ground in an entity-ticking chunk (shortened in 8-block steps if the way ahead is not
     * loaded). Null if no such spot exists: the caller treats the leg as blocked.
     */
    @Nullable
    static BlockPos hopTarget(ServerLevel level, Entity ent, BlockPos goal, int maxHop) {
        double d = DutyMotion.horizontal(ent.getX(), ent.getZ(), goal);
        for (int h = (int) Math.min(maxHop, Math.ceil(d)); h > 0; h -= 8) {
            BlockPos s = stand(level, DutyMotion.hop(ent.getX(), ent.getY(), ent.getZ(), goal, Math.max(1, h)));
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    private static final int[][] NEAR = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};

    /** Standable ground at or near {@code p} (its own Y first, then the surface); loaded, entity-ticking chunks only. */
    @Nullable
    static BlockPos stand(ServerLevel level, BlockPos p) {
        for (int[] o : NEAR) {
            BlockPos q = p.offset(o[0], 0, o[1]);
            if (!level.isPositionEntityTicking(q)) {
                continue; // never force-load
            }
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                BlockPos f = q.above(dy);
                if (standable(level, f)) {
                    return f;
                }
            }
            BlockPos top = new BlockPos(q.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, q.getX(), q.getZ()), q.getZ());
            if (Math.abs(top.getY() - p.getY()) <= 24 && standable(level, top)) {
                return top;
            }
        }
        return null;
    }

    static boolean standable(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty() && level.getFluidState(below).isEmpty();
    }

    private static AlertState alertState(UUID village) {
        dev.hywmill.core.HywMillRuntime rt = dev.hywmill.core.HywMillRuntime.get();
        return rt == null ? AlertState.CALM : rt.defense().state(village);
    }

    // ---- queries ----

    @Nullable
    public DutyPlan plan(UUID village) {
        Rt rt = villages.get(village);
        return rt == null ? null : rt.plan;
    }

    public DutyQuota quota(UUID village) {
        Rt rt = villages.get(village);
        return rt == null ? new DutyQuota(0, 0, 0, 0) : rt.quota;
    }

    /** Forces a layout read and re-allocation on the next duty tick. */
    public void invalidate(UUID village) {
        villages.remove(village);
    }

    public void prune(Collection<UUID> known) {
        villages.keySet().retainAll(known);
    }
}

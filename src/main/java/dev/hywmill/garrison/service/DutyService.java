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
import dev.hywmill.garrison.duty.StuckWatch;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
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
    private final RaidService raids;

    /** Runtime (not persisted) duty state of one village. Everything here is recomputable. */
    static final class Rt {
        @Nullable DutyPlan plan;
        long layoutCheckedTick = Long.MIN_VALUE;
        @Nullable Object tablesSeen;
        MilitaryTier tier;
        String culture = "";
        long allocationSig;
        DutyQuota quota = new DutyQuota(0, 0, 0, 0);
        /** rosterId → {goal, home set for it}: a unit still travelling to a valid hop needs no new ground search. */
        final Map<UUID, long[]> moves = new HashMap<>();
        /** M5-5 errand units: rosterId → {home, sinceTick, detourAttempt, lastPos} for the stuck/detour check. */
        final Map<UUID, long[]> errandMoves = new HashMap<>();
        /** M4 reliability recovery: progress watch of home-duty units (rosterId → watch). */
        final Map<UUID, StuckWatch.Track> stuck = new HashMap<>();
        /** Post exclusion of recently recovered units (rosterId → what they must not be given again yet). */
        final Map<UUID, StuckWatch.Avoid> avoid = new HashMap<>();
        /** Recovered scouts: the ride to resume with when they scout again. */
        final Map<UUID, Integer> scoutRide = new HashMap<>();
    }

    public DutyService(PerfCounters perf) {
        this.perf = perf;
        this.raids = new RaidService(perf);
    }

    /** DEV ONLY ({@code /hywmill dev duties on|off}, cost comparison): overrides the config switch until restart. */
    @Nullable public static volatile Boolean devOverride;

    public static boolean enabled() {
        Boolean o = devOverride;
        return o != null ? o : HywMillConfig.DUTIES_ENABLED.get();
    }

    /** Called for every active village with a record, once per duty interval (staggered). */
    public void tick(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, long tick) {
        GarrisonRoster r = rec.hywRoster;
        UnitProvider units = Services.units();
        SettlementSource source = Services.settlements();
        if (r == null || units == null || source == null) {
            return;
        }
        long t0 = perf.start();
        Rt rt = villages.computeIfAbsent(rec.villageId, k -> new Rt());
        DutyTables tables = DutyTables.current();
        DutyTable table = tables.forCulture(rec.culture);
        boolean duties = enabled() && replan(overworld, source, rec, rt, table, tables, tick);
        AlertState alert = alertState(rec.villageId);
        // raids first: a contingent leaving or coming back changes who is available for duties
        boolean changed = raids.tick(overworld, ledger, rec, r, table.raid(), duties ? rt.plan : null, alert, tick);
        changed |= ErrandService.tick(overworld, rec, r, table, alert, tick, rt.errandMoves); // M5-5 detachments
        if (!duties) {
            if (changed) {
                ledger.setDirty();
            }
            perf.stop("duty.tick", t0);
            return;
        }
        DutyPlan plan = rt.plan;
        changed |= allocate(rec, r, rt, table, plan, tick);

        boolean calm = alert == AlertState.CALM;
        Map<Integer, List<UUID>> pairs = new HashMap<>();
        for (RosterEntry e : r.entries()) {
            if (e.assignedDuty == Duty.SENTRY && e.dutyIndex >= 0 && available(e)) {
                pairs.computeIfAbsent(e.dutyIndex, k -> new ArrayList<>()).add(e.rosterId);
            }
        }
        pairs.values().forEach(l -> l.sort(Comparator.naturalOrder()));
        StuckWatch.Limits lim = StuckWatch.Limits.of(table.move(), table.scout());
        java.util.Set<UUID> watchedNow = new java.util.HashSet<>();
        for (RosterEntry e : r.entries()) {
            if (!movedByDuties(e)) {
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
            reequip(rec, e, ent, units);
            int member = e.assignedDuty == Duty.SENTRY ? Math.max(0, pairs.getOrDefault(e.dutyIndex, List.of()).indexOf(e.rosterId)) : 0;
            DutyMotion.Ctx ctx = new DutyMotion.Ctx(table.move(), table.scout(), rec.center, calm, member, rt.quota.patrol());
            int stepBefore = e.dutyStep;
            // M4 reliability recovery (StuckWatch): home duties only, same dimension, unit in a loaded chunk
            StuckWatch.Track st = level == overworld && StuckWatch.watched(e.assignedDuty) && level.isPositionEntityTicking(ent.blockPosition())
                    ? rt.stuck.computeIfAbsent(e.rosterId, k -> new StuckWatch.Track()) : null;
            BlockPos goal;
            if (st != null) {
                watchedNow.add(e.rosterId);
            }
            if (st != null && st.spot() != null) {
                goal = st.spot(); // falling back to the village by normal movement
                StuckWatch.Step step = StuckWatch.fallback(st, DutyMotion.horizontal(ent.getX(), ent.getZ(), goal),
                        StuckWatch.arriveFallback(table.move()), tick, lim);
                if (step == StuckWatch.Step.RECOVERED || (step == StuckWatch.Step.TELEPORT && lastResort(level, ent, goal))) {
                    StuckWatch.Avoid av = StuckWatch.toGarrison(e, st, tick);
                    if (av != null) {
                        rt.avoid.put(e.rosterId, av); // not the failed post again for 10 minutes
                        if (av.nextRide() >= 0) {
                            rt.scoutRide.put(e.rosterId, av.nextRide());
                        }
                    }
                    units.setHome(ent, goal);
                    rt.moves.remove(e.rosterId);
                    rt.allocationSig = 0; // the next allocation pass redistributes it
                    changed = true;
                    HmLog.info("Duty unit {} of village '{}' recovered at {} ({}); on GARRISON duty until the next allocation; {}", e.shortId(), rec.name,
                            goal.toShortString(), step == StuckWatch.Step.RECOVERED ? "walked back" : "last resort: moved onto the fallback spot",
                            av == null ? "no post exclusion" : "avoids " + av.duty() + (av.duty() == Duty.SCOUT ? " (any)" : "#" + av.index())
                                    + " until tick " + av.until() + (av.nextRide() >= 0 ? ", resumes at ride " + av.nextRide() : ""));
                    continue;
                }
                if (step == StuckWatch.Step.TELEPORT) {
                    StuckWatch.abandonFallback(st, tick); // the spot is no longer valid: back to its duty, another window before retrying
                    goal = DutyMotion.goal(e, ent.getX(), ent.getZ(), plan, ctx, tick);
                }
            } else {
                goal = DutyMotion.goal(e, ent.getX(), ent.getZ(), plan, ctx, tick);
                double dist = DutyMotion.horizontal(ent.getX(), ent.getZ(), goal);
                if (st != null && StuckWatch.observe(st, goal, dist, StuckWatch.arrive(e.assignedDuty, table.move()), tick, lim)) {
                    BlockPos spot = StuckWatch.fallbackSpot(e.rosterId, List.of(plan.scoutBase(), rec.center), q -> safeStand(level, ent, q));
                    if (spot == null) {
                        StuckWatch.retryLater(st, tick);
                        HmLog.diag("Duty unit {} of village '{}' made no progress towards {} but no fallback spot is loaded and safe", e.shortId(),
                                rec.name, goal.toShortString());
                    } else {
                        StuckWatch.fallBack(st, e, spot, DutyMotion.horizontal(ent.getX(), ent.getZ(), spot), tick);
                        rt.moves.remove(e.rosterId);
                        HmLog.info("Duty unit {} of village '{}' ({}) made no progress towards {} for {} ticks at {}: stuck, falling back to {}",
                                e.shortId(), rec.name, e.assignedDuty, goal.toShortString(), lim.stuckTicks(), ent.blockPosition().toShortString(),
                                spot.toShortString());
                        goal = spot;
                    }
                }
            }
            changed |= e.dutyStep != stepBefore;
            BlockPos home = units.home(ent);
            long[] last = rt.moves.get(e.rosterId);
            if (last != null && home != null && last[0] == goal.asLong() && last[1] == home.asLong() && last[2] == 1
                    && staticDuty(e.assignedDuty) && tick - last[3] >= table.move().hopTimeout()
                    && (DutyMotion.horizontal(ent.getX(), ent.getZ(), home) > table.move().arriveRadius() + 1 || below(ent, home, last))) {
                // stuck short of a fixed spot (e.g. a tower top HYW cannot path to): try ground around it (attempts 1-3);
                // then move it onto its spot (attempt 4, or whenever it is trapped in a pit or well); else hold where reachable
                int attempt = (int) last[4] + 1;
                BlockPos alt = attempt <= 3 ? around(level, goal, 2 + 2 * attempt, member * 4 + attempt, home) : null;
                // after the fallback spots: onto the spot itself (a sentry post on a tower HYW cannot path up to), once
                if (alt == null && (trapped(ent, last) || attempt == 4)
                        && unstick(level, ent, recoverySpot(ent.getX(), ent.getY(), ent.getZ(), stand(level, goal), goal, q -> stand(level, q)))) {
                    alt = BlockPos.containing(ent.getX(), ent.getY(), ent.getZ());
                    HmLog.diag("Duty unit {} of village '{}' was trapped; moved onto its {} spot {}", e.shortId(), rec.name, e.assignedDuty, alt.toShortString());
                } else if (alt == null && DutyMotion.horizontal(ent.getX(), ent.getZ(), goal) <= 24) {
                    alt = stand(level, ent.blockPosition());
                    holdDiag(level, rec, e, ent, goal, home, last, attempt, alt);
                }
                if (alt != null) {
                    units.setHome(ent, alt);
                    home = alt;
                }
                rt.moves.put(e.rosterId, new long[]{goal.asLong(), home.asLong(), 1, tick, attempt, ent.blockPosition().asLong()});
                continue;
            }
            if (last != null && home != null && last[0] == goal.asLong() && last[1] == home.asLong() && last[2] == 0
                    && tick - last[3] >= table.move().hopTimeout()
                    && DutyMotion.horizontal(ent.getX(), ent.getZ(), home) > table.move().arriveRadius() + 1) {
                // not reaching an intermediate hop: detour (rotated hops, alternating sides), then give the leg up
                int attempt = (int) last[4] + 1;
                BlockPos alt = attempt <= 4 ? hopTarget(level, ent, goal, table.move().maxHop(), attempt) : null;
                if (alt == null && trapped(ent, last)
                        && unstick(level, ent, recoverySpot(ent.getX(), ent.getY(), ent.getZ(), home, goal, q -> stand(level, q)))) {
                    HmLog.diag("Duty unit {} of village '{}' was trapped; moved onto its {} hop {}", e.shortId(), rec.name, e.assignedDuty, home.toShortString());
                    rt.moves.remove(e.rosterId);
                } else if (alt == null) {
                    DutyMotion.blocked(e, plan, tick);
                    rt.moves.remove(e.rosterId);
                } else {
                    units.setHome(ent, alt);
                    rt.moves.put(e.rosterId, new long[]{goal.asLong(), alt.asLong(), 0, tick, attempt, ent.blockPosition().asLong()});
                }
                continue;
            }
            if (last != null && home != null && last[0] == goal.asLong() && last[1] == home.asLong()
                    && (last[2] == 1 || DutyMotion.horizontal(ent.getX(), ent.getZ(), home) > table.move().maxHop() / 2.0)) {
                continue; // same goal, its hop is still the unit's home: at its final spot, or still on the way
            }
            BlockPos target = hopTarget(level, ent, goal, table.move().maxHop());
            if (target == null) {
                DutyMotion.blocked(e, plan, tick);
                rt.moves.remove(e.rosterId);
            } else {
                if (home == null || home.distSqr(target) > 2) {
                    units.setHome(ent, target);
                    home = target;
                }
                boolean fin = DutyMotion.horizontal(target.getX() + 0.5, target.getZ() + 0.5, goal) <= 3;
                rt.moves.put(e.rosterId, new long[]{goal.asLong(), home.asLong(), fin ? 1 : 0, tick, 0, ent.blockPosition().asLong()});
            }
        }
        rt.stuck.keySet().retainAll(watchedNow); // units deployed, away, unloaded or off these duties start a fresh watch
        if (changed) {
            ledger.setDirty();
        }
        perf.stop("duty.tick", t0);
    }

    /**
     * Profile equipment (M4, optional): when the village's provider depends on the duty role and the
     * unit's role changed since its equipment was applied, re-applies it (HYW's level, then the overlay).
     */
    private static void reequip(VillageRecord rec, RosterEntry e, Entity ent, UnitProvider units) {
        String role = dev.hywmill.garrison.equip.EquipmentProfiles.dutyRole(e.assignedDuty);
        if (role.equals(e.equipRole)) {
            return;
        }
        GarrisonTables gt = GarrisonTables.current();
        dev.hywmill.garrison.spi.EquipmentProvider eq = Services.equipment(gt.forCulture(rec.culture).equipmentProvider());
        UnitSpec spec = gt.units().get(e.unitKey);
        if (eq == null || !eq.reequips() || spec == null) {
            e.equipRole = role;
            return;
        }
        eq.apply(ent, spec, e.equipmentLevel, new dev.hywmill.garrison.spi.EquipmentProvider.Context(rec.culture, rec.tier, role,
                dev.hywmill.garrison.equip.EquipmentProfiles.classRole(spec.unitClass()), e.rosterId));
        e.equipRole = role;
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
                || s == UnitState.RETURNING) && !e.duty.away();
    }

    /** Re-runs the allocation when the available units or the plan changed. Returns true if any duty changed. */
    private boolean allocate(VillageRecord rec, GarrisonRoster r, Rt rt, DutyTable table, DutyPlan plan, long tick) {
        List<DutyAllocator.Candidate> cands = new ArrayList<>();
        long sig = plan.key() * 31 + rec.tier.ordinal();
        Map<String, UnitSpec> specs = GarrisonTables.current().units();
        rt.avoid.values().removeIf(a -> !a.active(tick)); // exclusions expire (the signature changes, so the pass reruns)
        for (RosterEntry e : r.entries()) {
            if (available(e)) {
                UnitSpec spec = specs.get(e.unitKey);
                StuckWatch.Avoid av = rt.avoid.get(e.rosterId);
                cands.add(new DutyAllocator.Candidate(e.rosterId, spec != null ? spec.unitClass() : UnitClass.LINE, e.assignedDuty, e.dutyIndex,
                        av == null ? null : av.slot()));
                sig = sig * 1_000_003L + e.rosterId.hashCode();
                if (av != null) {
                    sig = sig * 31 + av.duty().ordinal() * 1009L + av.index() + 2;
                }
            }
        }
        sig = sig * 31 + cands.size();
        if (sig == rt.allocationSig) {
            return false;
        }
        rt.allocationSig = sig;
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        cands.forEach(c -> ids.add(c.rosterId()));
        rt.moves.keySet().retainAll(ids);
        rt.scoutRide.keySet().retainAll(ids);
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
            Integer ride = a.duty() == Duty.SCOUT ? rt.scoutRide.remove(e.rosterId) : null;
            if (ride != null) {
                StuckWatch.resumeScout(e, ride); // a recovered scout does not restart its failed sequence from ride 0
            }
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
        return hopTarget(level, ent, goal, maxHop, 0);
    }

    /**
     * As above; {@code turn} (detour attempts) rotates an intermediate hop by 35° steps, alternating
     * sides, to get round an obstacle. Intermediate hops prefer the surface; the final spot is
     * looked for at its own height first (a wall walk or tower floor).
     */
    @Nullable
    static BlockPos hopTarget(ServerLevel level, Entity ent, BlockPos goal, int maxHop, int turn) {
        double d = DutyMotion.horizontal(ent.getX(), ent.getZ(), goal);
        for (int h = (int) Math.min(maxHop, Math.ceil(d)); h > 0; h -= 8) {
            BlockPos p = DutyMotion.hop(ent.getX(), ent.getY(), ent.getZ(), goal, Math.max(1, h));
            boolean last = p.equals(goal);
            if (!last && turn > 0) {
                double a = Math.toRadians(35 * ((turn + 1) / 2)) * (turn % 2 == 1 ? 1 : -1);
                double dx = p.getX() + 0.5 - ent.getX(), dz = p.getZ() + 0.5 - ent.getZ();
                p = BlockPos.containing(ent.getX() + dx * Math.cos(a) - dz * Math.sin(a), ent.getY(), ent.getZ() + dx * Math.sin(a) + dz * Math.cos(a));
            }
            BlockPos s = last ? stand(level, p) : surface(level, p);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /** Surface first (within 12 blocks of the point's height), else {@link #stand}. */
    @Nullable
    static BlockPos surface(ServerLevel level, BlockPos p) {
        if (level.isPositionEntityTicking(p)) {
            BlockPos top = new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
            if (Math.abs(top.getY() - p.getY()) <= 12 && standable(level, top)) {
                return top;
            }
        }
        return stand(level, p);
    }

    /**
     * The unit sits a block or more below its home spot and has not moved since the last order: it is
     * in a pit or well next to its spot (horizontally "arrived", but cannot see or reach anything).
     */
    static boolean below(Entity ent, BlockPos home, long[] last) {
        return ent.getY() < home.getY() - 0.9 && trapped(ent, last);
    }

    /** The unit has not moved more than 2 blocks since its last move order (index 5 of the hop cache). */
    static boolean trapped(Entity ent, long[] last) {
        return last.length > 5 && BlockPos.of(last[5]).distSqr(ent.blockPosition()) <= 4;
    }

    /**
     * Last resort for a trapped unit (it has not moved through every detour): moves it (with its
     * mount) onto {@code spot} if that is standable, loaded and at most 40 blocks away. Returns true if moved.
     */
    static boolean unstick(ServerLevel level, Entity ent, @Nullable BlockPos spot) {
        if (spot == null || DutyMotion.horizontal(ent.getX(), ent.getZ(), spot) > 40 || !level.isPositionEntityTicking(spot)
                || !standable(level, spot)) {
            return false;
        }
        RaidService.teleport(ent, net.minecraft.world.phys.Vec3.atBottomCenterOf(spot));
        return true;
    }

    /**
     * Units this service moves (and may recover when trapped): living home units on a standing duty. Units away on a
     * raid or lent to a player (DETACHED) are never moved or recovered here; lent soldiers never teleport.
     */
    static boolean movedByDuties(RosterEntry e) {
        return (e.state() == UnitState.GARRISONED || e.state() == UnitState.RECOVERED || e.state() == UnitState.SPAWNED)
                && !e.duty.away() && e.entityUuid != null;
    }

    /** The recovery ceiling of {@link #unstick}. */
    static final double UNSTICK_MAX = 40;
    /** Waypoint distances tried towards a target beyond the ceiling, nearest to the target first (all below the ceiling). */
    static final int[] RECOVERY_STEPS = {32, 24, 16, 8};

    /**
     * Where a trapped home unit may be recovered to (M5 approved M4 fix). Pure: {@code stand} maps a point to standable
     * ground in a loaded chunk, or null.
     * <ul>
     *   <li>Target within {@link #UNSTICK_MAX}: {@code spot}, exactly as before (the unchanged M4 behaviour).</li>
     *   <li>Target beyond it: the nearest-to-target standable, loaded waypoint on the line towards the unit's duty target,
     *       at most {@link #RECOVERY_STEPS}[0] blocks from the unit and closer to the target than the unit. Previously such a
     *       unit was never recovered (the ceiling refused the spot) and stayed trapped.</li>
     * </ul>
     * Null when no candidate qualifies (the unit then holds, as before).
     */
    @Nullable
    static BlockPos recoverySpot(double x, double y, double z, @Nullable BlockPos spot, BlockPos target,
                                 java.util.function.Function<BlockPos, BlockPos> stand) {
        double toTarget = DutyMotion.horizontal(x, z, target);
        if (toTarget <= UNSTICK_MAX) {
            return spot;
        }
        for (int h : RECOVERY_STEPS) {
            BlockPos s = stand.apply(DutyMotion.hop(x, y, z, target, h));
            if (s != null && DutyMotion.horizontal(x, z, s) <= RECOVERY_STEPS[0] + 3
                    && DutyMotion.horizontal(s.getX() + 0.5, s.getZ() + 0.5, target) < toTarget) {
                return s;
            }
        }
        return null;
    }

    /** G4-2 diagnostic: holds farther than this from the spot are logged (the G4-2 acceptance radius). */
    static final double HOLD_DIAG_MIN = 10;

    /**
     * G4-2 diagnostic only (approved; no behaviour change): a static-duty unit that did not reach its spot is being
     * left holding where it is, 10-24 blocks from the spot (inside M4's 24-block hold radius). Logs why the M4 steps
     * before the hold did not put it on the spot. Re-evaluates the same pure checks; moves nothing.
     */
    private static void holdDiag(ServerLevel level, VillageRecord rec, RosterEntry e, Entity ent, BlockPos goal, BlockPos home, long[] last,
                                 int attempt, @Nullable BlockPos hold) {
        double d = DutyMotion.horizontal(ent.getX(), ent.getZ(), goal);
        if (d <= HOLD_DIAG_MIN) {
            return;
        }
        boolean trapped = trapped(ent, last);
        String around = attempt <= 3 ? "no standable ground around the spot at radius " + (2 + 2 * attempt) + " (attempt " + attempt + ")"
                : "ground around the spot already tried (attempts 1-3), attempt " + attempt;
        BlockPos candidate = null;
        String recovery;
        if (trapped || attempt == 4) {
            candidate = recoverySpot(ent.getX(), ent.getY(), ent.getZ(), stand(level, goal), goal, q -> stand(level, q));
            recovery = "move onto the spot refused: " + unstickRefusal(level, ent, candidate);
        } else {
            recovery = "move onto the spot not attempted (only on attempt 4 or when trapped; the unit moved more than 2 blocks since its last order)";
        }
        HmLog.info("M4 hold diag: unit {} (entity {}) of village '{}' on {}#{} at {} is {} blocks from its spot {} (inside the 24-block hold "
                        + "radius: true); did not reach its home {} ({} blocks away) within the hop timeout (reachability: HYW path not completed; "
                        + "M4 does not query the path); {}; {}; recovery candidate: {}; holding at {}",
                e.rosterId, ent.getUUID(), rec.name, e.assignedDuty, e.dutyIndex, ent.blockPosition().toShortString(), String.format("%.1f", d),
                goal.toShortString(), home.toShortString(), String.format("%.1f", DutyMotion.horizontal(ent.getX(), ent.getZ(), home)), around,
                recovery, candidate == null ? "none" : candidate.toShortString(), hold == null ? "(no standable ground here)" : hold.toShortString());
    }

    /** Why {@link #unstick} refuses {@code spot} (diagnostics; the same checks, in the same order). */
    static String unstickRefusal(ServerLevel level, Entity ent, @Nullable BlockPos spot) {
        if (spot == null) {
            return "no standable, loaded ground at or near the spot";
        }
        if (DutyMotion.horizontal(ent.getX(), ent.getZ(), spot) > UNSTICK_MAX) {
            return "candidate more than 40 blocks away";
        }
        if (!level.isPositionEntityTicking(spot)) {
            return "candidate not in an entity-ticking chunk";
        }
        if (!standable(level, spot)) {
            return "candidate not standable";
        }
        return "none: the candidate was valid (the move was not refused by unstick)";
    }

    /** Standable, loaded, safe ground at or near {@code p} for {@code ent} (M4 reliability recovery fallback spots); else null. */
    @Nullable
    static BlockPos safeStand(ServerLevel level, Entity ent, BlockPos p) {
        BlockPos s = stand(level, p);
        return s != null && safe(level, ent, s) ? s : null;
    }

    static boolean safe(ServerLevel level, Entity ent, BlockPos s) {
        return level.isPositionEntityTicking(s) && standable(level, s)
                && (!(ent instanceof Mob m) || WalkNodeEvaluator.getPathTypeStatic(m, s) == PathType.WALKABLE);
    }

    /**
     * M4 reliability recovery, last resort: moves a unit that could not walk to its fallback spot onto it (with its mount),
     * after re-validating the spot (same level, loaded, standable, safe). Never forces a chunk. True if moved.
     */
    static boolean lastResort(ServerLevel level, Entity ent, BlockPos spot) {
        if (ent.level() != level || !safe(level, ent, spot)) {
            return false;
        }
        RaidService.teleport(ent, net.minecraft.world.phys.Vec3.atBottomCenterOf(spot));
        return true;
    }

    static boolean staticDuty(Duty d) {
        return d == Duty.SENTRY || d == Duty.RESERVE || d == Duty.GARRISON;
    }

    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /** A standable spot about {@code radius} blocks around {@code center} (directions from {@code start}), not {@code not}. */
    @Nullable
    static BlockPos around(ServerLevel level, BlockPos center, int radius, int start, BlockPos not) {
        for (int i = 0; i < DIRS.length; i++) {
            int[] d = DIRS[Math.floorMod(start + i, DIRS.length)];
            BlockPos s = stand(level, center.offset(d[0] * radius, 0, d[1] * radius));
            if (s != null && !s.equals(not)) {
                return s;
            }
        }
        return null;
    }

    private static final int[][] NEAR = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};

    /**
     * Standable ground at or near {@code p}: first at its own height (within two blocks up or down)
     * on it or around it, then on the surface; loaded, entity-ticking chunks only.
     */
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
        }
        for (int[] o : NEAR) {
            BlockPos q = p.offset(o[0], 0, o[1]);
            if (!level.isPositionEntityTicking(q)) {
                continue;
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

package dev.hywmill.garrison.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.PerfCounters;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.duty.DutyMotion;
import dev.hywmill.garrison.duty.RaidPlanner;
import dev.hywmill.garrison.duty.RaidRule;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.military.defense.AlertState;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * M4: HYW garrison contingents in the village's own Millénaire raids. Millénaire fires no raid
 * events and is not changed; its public raid state is polled on the village's duty tick.
 *
 * <ol>
 *   <li>Raid started (target set, raid start &gt; 0) while the village is CALM: a contingent is
 *       chosen by {@link RaidPlanner} from units at home, marked DEPLOYED + RAID and mustered.</li>
 *   <li>When Millénaire materializes its own raiders ({@link #MATERIALIZE} ticks after the start)
 *       and the target is entity-ticking, the contingent is moved to Millénaire's own landing
 *       point (as Millénaire moves its raiders), then advances on the target by HYW home hops and
 *       engages the target's residents with temporary hostility only.</li>
 *   <li>Raid over (target cleared, raid history grew, village gone) or the home village alerted:
 *       loaded survivors are brought home and resume their duties through the M2 return path.
 *       Deaths are the M3 death path (DEAD, no respawn). Nothing is ever spawned here.</li>
 * </ol>
 */
public final class RaidService {
    /** Millénaire materializes its raiders about this many ticks after the raid starts (RaidManager). */
    public static final long MATERIALIZE = 500;
    /** A raid record older than this is ended regardless (Millénaire raids last well under a day). */
    public static final long MAX_RAID = 24000;
    /** Residents within this distance of a raid unit are engaged. */
    public static final double ENGAGE_RANGE = 32;

    public static final String MUSTER = "MUSTER", AWAY = "AWAY";

    private final PerfCounters perf;

    public RaidService(PerfCounters perf) {
        this.perf = perf;
    }

    public static boolean enabled() {
        return HywMillConfig.RAIDS_ENABLED.get();
    }

    /** Runs on the attacker's duty tick. Returns true if any roster state changed. */
    public boolean tick(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r, RaidRule rule,
                        @Nullable dev.hywmill.garrison.duty.DutyPlan plan, AlertState alert, long tick) {
        SettlementSource source = Services.settlements();
        UnitProvider units = Services.units();
        if (source == null || units == null) {
            return false;
        }
        long t0 = perf.start();
        boolean changed = false;
        Optional<SettlementSource.RaidInfo> info = source.raidInfo(overworld, rec.villageId);
        GarrisonRoster.RaidRecord raid = r.raid;
        if (raid == null) {
            if (info.isPresent() && info.get().target() != null && info.get().raidStart() > 0 && enabled() && alert == AlertState.CALM) {
                changed |= start(overworld, rec, r, rule, info.get(), units, plan, tick);
            } else {
                changed |= bringHomeStale(overworld, rec, r, units, tick); // only when no raid of ours is running
            }
        } else {
            boolean over = info.isEmpty() || !raid.target.equals(info.get().target()) || info.get().performed() > raid.performedBase
                    || tick - raid.raidStart > MAX_RAID;
            boolean homeAlert = alert == AlertState.ALERT || alert == AlertState.ENGAGED;
            if (over || homeAlert) {
                HmLog.info("Raid of village '{}' on {} {}: bringing the contingent home", rec.name, raid.target,
                        over ? "is over" : "abandoned (home village " + alert + ")");
                r.raid = null;
                changed = true;
                bringHomeStale(overworld, rec, r, units, tick);
            } else if (raid.phase.equals(MUSTER)) {
                changed |= land(overworld, ledger, rec, r, raid, units, source, tick);
            } else {
                advance(overworld, ledger, rec, r, raid, units, source);
            }
        }
        perf.stop("raid.tick", t0);
        return changed;
    }

    private boolean start(ServerLevel overworld, VillageRecord rec, GarrisonRoster r, RaidRule rule, SettlementSource.RaidInfo info,
                          UnitProvider units, @Nullable dev.hywmill.garrison.duty.DutyPlan plan, long tick) {
        List<RaidPlanner.Candidate> cands = new ArrayList<>();
        for (RosterEntry e : r.entries()) {
            if ((e.state() == UnitState.GARRISONED || e.state() == UnitState.RECOVERED) && e.duty.standing() && e.entityUuid != null
                    && !DutyMotion.scoutAway(e)) {
                Entity ent = GarrisonService.find(overworld.getServer(), e.entityUuid);
                if (ent != null && ent.isAlive()) {
                    cands.add(new RaidPlanner.Candidate(e.rosterId, e.assignedDuty, e.dutyIndex));
                }
            }
        }
        List<UUID> chosen = RaidPlanner.select(rule, cands);
        r.raid = new GarrisonRoster.RaidRecord(info.target(), info.raidStart(), info.performed(), MUSTER, tick, chosen.size());
        BlockPos muster = plan != null ? plan.reserve() : rec.center;
        List<String> names = new ArrayList<>();
        for (UUID id : chosen) {
            RosterEntry e = r.entry(id);
            e.transition(UnitState.DEPLOYED, tick);
            e.duty = Duty.RAID;
            Entity ent = GarrisonService.find(overworld.getServer(), e.entityUuid);
            if (ent != null) {
                units.setHome(ent, muster);
            }
            names.add(e.shortId() + " " + e.unitKey + "(" + e.assignedDuty + ")");
        }
        HmLog.info("Village '{}' raids {}: {} of {} available garrison unit(s) join the raid {}", rec.name, info.target(), chosen.size(),
                cands.size(), names);
        return true;
    }

    /** Moves the contingent to Millénaire's landing point once Millénaire materializes its raiders there. */
    private boolean land(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r, GarrisonRoster.RaidRecord raid,
                         UnitProvider units, SettlementSource source, long tick) {
        if (tick < raid.raidStart + MATERIALIZE) {
            return false;
        }
        Optional<BlockPos> landing = source.raidLandingPoint(overworld, raid.target, rec.center);
        if (landing.isEmpty() || !overworld.isPositionEntityTicking(landing.get())) {
            return false; // like Millénaire: raiders only materialize at a loaded target; retry next tick
        }
        int moved = 0;
        for (RosterEntry e : r.entries()) {
            if (e.duty != Duty.RAID || e.entityUuid == null) {
                continue;
            }
            Entity ent = GarrisonService.find(overworld.getServer(), e.entityUuid);
            if (ent == null || !ent.isAlive()) {
                continue;
            }
            Vec3 spot = GarrisonService.findSpot(overworld, landing.get(), e.rosterId);
            if (spot == null) {
                spot = Vec3.atBottomCenterOf(landing.get());
            }
            teleport(ent, spot);
            units.setHome(ent, BlockPos.containing(spot));
            moved++;
        }
        raid.phase = AWAY;
        raid.phaseSince = tick;
        HmLog.info("Raid contingent of village '{}' ({} unit(s)) moved to Millénaire's landing point {} near {}", rec.name, moved,
                landing.get().toShortString(), targetName(ledger, raid.target));
        return true;
    }

    /** Advances on the target by hops and engages the nearest target residents (temporary hostility only). */
    private void advance(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r, GarrisonRoster.RaidRecord raid,
                         UnitProvider units, SettlementSource source) {
        VillageRecord target = ledger.get(raid.target);
        if (target == null) {
            return;
        }
        List<SettlementSource.RosterEntry> defenders = source.defenseRoster(overworld, raid.target);
        for (RosterEntry e : r.entries()) {
            if (e.duty != Duty.RAID || e.entityUuid == null) {
                continue;
            }
            Entity ent = GarrisonService.find(overworld.getServer(), e.entityUuid);
            if (ent == null || !ent.isAlive() || !(ent.level() instanceof ServerLevel level)) {
                continue;
            }
            LivingEntity current = units.target(ent);
            if (current != null && current.isAlive() && units.isTemporarilyHostile(ent, current)) {
                continue;
            }
            LivingEntity best = null;
            double bestD = ENGAGE_RANGE * ENGAGE_RANGE;
            for (SettlementSource.RosterEntry d : defenders) {
                double dx = d.x() - ent.getX(), dy = d.y() - ent.getY(), dz = d.z() - ent.getZ();
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 < bestD && level.getEntity(d.id()) instanceof LivingEntity le && le.isAlive()) {
                    bestD = d2;
                    best = le;
                }
            }
            if (best != null) {
                units.engage(ent, best);
            } else {
                BlockPos hop = DutyService.hopTarget(level, ent, target.center, 24);
                if (hop != null) {
                    units.setHome(ent, hop);
                }
            }
        }
    }

    /**
     * Brings loaded RAID units home (the raid is over, or they were away when it ended): teleported
     * next to the village's spawn anchor if it is loaded; DEPLOYED ones then take the normal M2 return
     * path (RETURNING, then back to their standing duty). Unloaded ones follow when they load.
     */
    private boolean bringHomeStale(ServerLevel overworld, VillageRecord rec, GarrisonRoster r, UnitProvider units, long tick) {
        BlockPos anchor = GarrisonService.anchorOf(rec);
        if (!overworld.isPositionEntityTicking(anchor)) {
            return false;
        }
        boolean changed = false;
        int n = 0;
        for (RosterEntry e : r.entries()) {
            if (e.duty != Duty.RAID || e.entityUuid == null || !e.state().bound() || e.state() == UnitState.MISSING) {
                continue;
            }
            Entity ent = GarrisonService.find(overworld.getServer(), e.entityUuid);
            if (ent == null || !ent.isAlive()) {
                continue;
            }
            Vec3 spot = GarrisonService.findSpot(overworld, anchor, e.rosterId);
            if (ent.level() != overworld || spot == null) {
                continue;
            }
            teleport(ent, spot);
            units.disengage(ent);
            units.setHome(ent, BlockPos.containing(spot));
            if (e.state() == UnitState.DEPLOYED) {
                e.transition(UnitState.RETURNING, tick);
                e.duty = Duty.RETURNING;
            } else {
                e.duty = e.assignedDuty;
            }
            changed = true;
            n++;
        }
        if (n > 0) {
            HmLog.info("Raid contingent of village '{}': {} survivor(s) back home", rec.name, n);
        }
        return changed;
    }

    /** Moves the whole mount stack (an HYW rider moves with its horse). */
    static void teleport(Entity ent, Vec3 spot) {
        Entity root = ent.getRootVehicle();
        root.teleportTo(spot.x, spot.y, spot.z);
    }

    private static String targetName(GarrisonLedger ledger, UUID target) {
        VillageRecord t = ledger.get(target);
        return t != null ? "'" + t.name + "'" : target.toString();
    }

    /** Roster ids currently away on a raid (diagnostics). */
    public static Set<UUID> contingent(GarrisonRoster r) {
        Set<UUID> out = new HashSet<>();
        for (RosterEntry e : r.entries()) {
            if (e.duty == Duty.RAID) {
                out.add(e.rosterId);
            }
        }
        return out;
    }
}

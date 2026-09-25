package dev.hywmill.military.defense;

import dev.hywmill.military.doctrine.Doctrine;
import net.minecraft.world.entity.LivingEntity;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.PerfCounters;
import dev.hywmill.core.Services;
import dev.hywmill.military.ThreatTracker;
import dev.hywmill.military.classify.RoleClassifier;
import dev.hywmill.military.classify.RoleTable;
import dev.hywmill.military.classify.RoleTables;
import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.military.doctrine.DoctrineResolver;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Glue between the threat tracker, the pure doctrine/defense logic and the goal decorators.
 * One instance per server (owned by {@code HywMillRuntime}); all state is runtime state.
 *
 * <p>Per village: the alert state machine is advanced on every threat scan; while not CALM the
 * {@link DefenseCoordinator} recomputes assignments and the reserve from the loaded roster.
 * Decorators only read the result (map lookups).
 */
public final class DefenseService {
    private final Map<UUID, VillageDefenseState> villages = new ConcurrentHashMap<>();
    private final PerfCounters perf;
    private ThreatTracker threats;
    @Nullable private ScanListener listener;

    /** Notified after every village scan with M2's result (M3 garrison deployment). Must not change M2 state. */
    public interface ScanListener {
        void afterScan(ServerLevel level, UUID village, AlertState state, Doctrine doctrine, List<DefenseCoordinator.ThreatView> threats,
                       Map<UUID, LivingEntity> threatEntities, DefenseCoordinator.Pos anchor);
    }

    public void setListener(@Nullable ScanListener listener) {
        this.listener = listener;
    }

    private void notifyListener(ServerLevel level, VillageDefenseState st, List<DefenseCoordinator.ThreatView> tv,
                                Map<UUID, LivingEntity> entities) {
        if (listener != null) {
            listener.afterScan(level, st.village, st.state(), st.doctrine.doctrine(), tv, entities,
                    st.defendingPos != null ? st.defendingPos : st.center);
        }
    }

    public DefenseService(PerfCounters perf) {
        this.perf = perf;
    }

    public void bind(ThreatTracker threats) {
        this.threats = threats;
    }

    /** Called at every profile refresh with the freshly resolved doctrine. */
    public void configure(UUID village, DoctrineResolver.Resolved doctrine, BlockPos center, BlockPos defendingPos) {
        VillageDefenseState st = villages.computeIfAbsent(village, VillageDefenseState::new);
        st.doctrine = doctrine;
        st.center = pos(center);
        st.defendingPos = pos(defendingPos);
    }

    @Nullable
    public VillageDefenseState get(UUID village) {
        return villages.get(village);
    }

    public Collection<VillageDefenseState> all() {
        return Collections.unmodifiableCollection(villages.values());
    }

    // ---- signals (from the damage hook) ----

    /** An HYW unit damaged a resident, or a resident hit an HYW unit: ENGAGED at the next scan, which runs next tick. */
    public void engageSignal(UUID village) {
        VillageDefenseState st = villages.get(village);
        if (st != null) {
            st.engageSignal = true;
            if (threats != null) {
                threats.requestScan(village);
            }
        }
    }

    // ---- scan ----

    public void onScan(ServerLevel level, UUID village, List<ThreatTracker.Threat> found, long now) {
        VillageDefenseState st = villages.get(village);
        if (st == null || st.doctrine == null) {
            return;
        }
        long t0 = perf.start();
        boolean engage = st.engageSignal;
        st.engageSignal = false;
        AlertState before = st.machine.update(now, !found.isEmpty(), engage, st.doctrine.doctrine());
        if (before != null) {
            onTransition(level, st, before, now);
        }
        st.lastScanTick = now;
        if (st.state() == AlertState.CALM) {
            st.assignments = Map.of();
            st.reserve = java.util.Set.of();
            st.byThreat = Map.of();
            st.threatPositions = List.of();
            st.eligible = 0;
            perf.stop("defense.update", t0);
            notifyListener(level, st, List.of(), Map.of());
            return;
        }
        List<DefenseCoordinator.ThreatView> tv = new ArrayList<>(found.size());
        List<DefenseCoordinator.Pos> tp = new ArrayList<>(found.size());
        Map<UUID, LivingEntity> threatEntities = new java.util.HashMap<>();
        for (ThreatTracker.Threat t : found) {
            DefenseCoordinator.Pos p = new DefenseCoordinator.Pos(t.entity().getX(), t.entity().getY(), t.entity().getZ());
            tv.add(new DefenseCoordinator.ThreatView(t.entity().getUUID(), p, t.reasons()));
            tp.add(p);
            threatEntities.put(t.entity().getUUID(), t.entity());
        }
        SettlementSource source = Services.settlements();
        List<DefenseCoordinator.DefenderView> roster = new ArrayList<>();
        if (source != null) {
            RoleTable table = RoleTables.current();
            for (SettlementSource.RosterEntry e : source.defenseRoster(level, village)) {
                VillagerRole role = RoleClassifier.villager(e.facts(), table);
                if (role.isDefender()) {
                    roster.add(new DefenseCoordinator.DefenderView(e.id(), role, new DefenseCoordinator.Pos(e.x(), e.y(), e.z())));
                }
            }
        }
        DefenseCoordinator.Result r = DefenseCoordinator.assign(new DefenseCoordinator.Input(st.doctrine.doctrine(), st.state(),
                roster, tv, st.defendingPos != null ? st.defendingPos : st.center, st.assignments, st.reserve));
        if (!r.assignments().equals(st.assignments)) {
            HmLog.diag("Defense assignments in village {} ({}): {} | reserve {}", village, st.state(), r.byThreat(), r.reserve());
        }
        st.assignments = r.assignments();
        st.reserve = r.reserve();
        st.byThreat = r.byThreat();
        st.eligible = r.eligible();
        st.threatPositions = tp;
        perf.stop("defense.update", t0);
        notifyListener(level, st, tv, threatEntities);
    }

    private void onTransition(ServerLevel level, VillageDefenseState st, AlertState before, long now) {
        AlertState after = st.state();
        HmLog.info("Village {} alert state {} -> {}", st.village, before, after);
        VillageRecord rec = GarrisonLedger.get(level.getServer().overworld()).get(st.village);
        if (rec == null) {
            return;
        }
        if (after == AlertState.ALERT || (after == AlertState.ENGAGED && before == AlertState.CALM)) {
            rec.stats.alerts++;
            rec.stats.lastAlertTick = now;
        }
        if (after == AlertState.ENGAGED) {
            rec.stats.engagements++;
            rec.stats.lastEngagedTick = now;
        }
        GarrisonLedger.get(level.getServer().overworld()).setDirty();
    }

    // ---- queries for decorators and tasks (cheap) ----

    public AlertState state(UUID village) {
        VillageDefenseState st = villages.get(village);
        return st == null ? AlertState.CALM : st.state();
    }

    @Nullable
    public UUID assignedThreat(UUID village, UUID defender) {
        VillageDefenseState st = villages.get(village);
        return st == null ? null : st.assignments.get(defender);
    }

    /** Assigned to exactly this target, or fighting back against it (self-defense is never restricted). */
    public boolean mayEngage(UUID village, UUID defender, UUID target, boolean selfDefense) {
        return selfDefense || target.equals(assignedThreat(village, defender));
    }

    public boolean shouldHold(UUID village, UUID defender) {
        VillageDefenseState st = villages.get(village);
        return st != null && st.state().reserveHolds() && st.reserve.contains(defender);
    }

    public boolean shouldShelter(UUID village, double x, double y, double z) {
        VillageDefenseState st = villages.get(village);
        if (st == null || st.doctrine == null) {
            return false;
        }
        return ShelterPolicy.shouldShelter(st.state(), st.doctrine.doctrine().shelterRadius(), new DefenseCoordinator.Pos(x, y, z),
                st.threatPositions);
    }

    /**
     * A civilian already sheltering keeps sheltering while the village is ALERT or ENGAGED: the
     * shelter radius decides who <em>starts</em> sheltering, so a civilian walking to a shelter
     * that lies outside the radius does not turn back halfway.
     */
    public boolean shelterContinues(UUID village) {
        return state(village).civiliansShelter();
    }

    public void prune(Collection<UUID> knownVillages) {
        villages.keySet().retainAll(knownVillages);
    }

    private static DefenseCoordinator.Pos pos(BlockPos p) {
        return new DefenseCoordinator.Pos(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
    }
}

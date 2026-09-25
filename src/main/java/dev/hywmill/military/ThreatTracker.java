package dev.hywmill.military;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.PerfCounters;
import dev.hywmill.military.defense.AssistPolicy;
import dev.hywmill.military.defense.DefenseArea;
import dev.hywmill.military.defense.DefenseService;
import dev.hywmill.military.doctrine.Doctrine;
import dev.hywmill.core.VillageScheduler;
import dev.hywmill.core.Services;
import dev.hywmill.faction.CombatFactionService;
import dev.hywmill.settlement.ResidentInfo;
import dev.hywmill.settlement.SettlementSnapshot;
import dev.hywmill.settlement.SettlementSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Per-village snapshot of hostile HYW units, rebuilt every threatScanInterval ticks for active
 * villages only (staggered), or on the next tick after a resident is attacked. The scan covers
 * the doctrine's defense radius around the village center (horizontal). Each scan result is
 * handed to the {@link DefenseService}. Goal decorators query it every tick, so all queries are
 * map lookups.
 *
 * <p>Classification only OBSERVES HYW state; it never changes HYW relations. One instance per server,
 * owned by {@link dev.hywmill.core.HywMillRuntime}.
 */
public final class ThreatTracker {
    public enum Reason {
        /** HYW itself considers the unit's owner an enemy of the village faction (null-owner units such as summoned bandits, or an explicit HOSTILE relation). */
        HYW_ENEMY,
        /** The unit's current target is a resident of this village. */
        ATTACKING_RESIDENT,
        /** The incident ledger shows the unit damaged a resident recently. */
        RECENT_ATTACKER,
        /** The unit is attacking a player inside the village who qualifies for assistance. */
        ATTACKING_ALLY_PLAYER
    }

    public record Threat(LivingEntity entity, Set<Reason> reasons) {}

    private static final class VillageState {
        final UUID village;
        UUID faction;
        String name = "";
        AABB bounds;
        boolean active;
        BlockPos center;
        int defenseRadius;
        Doctrine doctrine;
        UUID controller;
        List<Threat> threats = List.of();
        Set<UUID> threatIds = Set.of();

        VillageState(UUID village) {
            this.village = village;
        }
    }

    private final Map<UUID, VillageState> states = new ConcurrentHashMap<>();
    private final Set<UUID> urgent = ConcurrentHashMap.newKeySet();
    private final IncidentLedger incidents;
    private final VillageScheduler scheduler;
    private final DefenseService defense;
    private final PerfCounters perf;

    public ThreatTracker(IncidentLedger incidents, VillageScheduler scheduler, DefenseService defense, PerfCounters perf) {
        this.incidents = incidents;
        this.scheduler = scheduler;
        this.defense = defense;
        this.perf = perf;
    }

    public void updateVillage(SettlementSnapshot s, UUID faction, Doctrine doctrine, @Nullable UUID controller) {
        VillageState st = states.computeIfAbsent(s.id(), VillageState::new);
        st.faction = faction;
        st.name = s.name();
        st.bounds = s.bounds();
        st.active = s.active();
        st.center = s.center();
        st.defenseRadius = doctrine.defenseRadius();
        st.doctrine = doctrine;
        st.controller = controller;
    }

    /** Scan this village on the next tick regardless of its staggered slot (a resident was just attacked). */
    public void requestScan(UUID village) {
        urgent.add(village);
    }

    public void markInactive(UUID village) {
        VillageState st = states.get(village);
        if (st != null) {
            st.active = false;
            st.threats = List.of();
            st.threatIds = Set.of();
        }
    }

    public void reset() {
        states.clear();
    }

    /** Scans the villages whose staggered slot is due this tick (each village every threatScanInterval ticks). */
    public void scan(ServerLevel level) {
        CombatFactionService factions = Services.factions();
        SettlementSource source = Services.settlements();
        if (factions == null || source == null) {
            return;
        }
        int margin = HywMillConfig.THREAT_MARGIN.get();
        long now = level.getGameTime();
        int interval = HywMillConfig.THREAT_SCAN_INTERVAL.get();
        for (VillageState st : states.values()) {
            boolean forced = urgent.remove(st.village);
            if (!st.active || st.bounds == null || st.faction == null || st.doctrine == null
                    || !(forced || scheduler.isDue(st.village, now, interval))) {
                continue;
            }
            long t0 = perf.start();
            int r = st.defenseRadius;
            AABB box = new AABB(st.center.getX() - r, st.bounds.minY - margin, st.center.getZ() - r,
                    st.center.getX() + r + 1, st.bounds.maxY + margin, st.center.getZ() + r + 1);
            List<Threat> found = new ArrayList<>();
            for (LivingEntity unit : factions.findCombatUnits(level, box)) {
                if (!DefenseArea.inside(st.center.getX() + 0.5, st.center.getZ() + 0.5, r, unit.getX(), unit.getZ())) {
                    continue;
                }
                EnumSet<Reason> reasons = classify(level, source, factions, st, unit, now);
                if (!reasons.isEmpty()) {
                    found.add(new Threat(unit, reasons));
                }
            }
            boolean had = !st.threats.isEmpty();
            st.threats = List.copyOf(found);
            st.threatIds = found.stream().map(t -> t.entity().getUUID()).collect(Collectors.toUnmodifiableSet());
            if (!had && !found.isEmpty()) {
                HmLog.info("Threat detected in village '{}': {}", st.name, describe(factions, found));
            } else if (had && found.isEmpty()) {
                HmLog.info("Threat cleared in village '{}'", st.name);
            } else if (!found.isEmpty()) {
                HmLog.diagThrottled("threat-" + st.village, 10_000L, "Ongoing threat in village '{}': {}", st.name, describe(factions, found));
            }
            perf.stop("scan.village", t0);
            defense.onScan(level, st.village, st.threats, now);
        }
    }

    private EnumSet<Reason> classify(ServerLevel level, SettlementSource source, CombatFactionService factions,
                                            VillageState st, LivingEntity unit, long now) {
        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        if (factions.isEnemyOfIdentity(unit, st.faction)) {
            reasons.add(Reason.HYW_ENEMY);
        }
        LivingEntity target = factions.currentTarget(unit);
        if (target != null && target.isAlive()) {
            Optional<ResidentInfo> info = source.residentInfo(target);
            if (info.isPresent() && !info.get().raider() && info.get().settlementId().equals(st.village)) {
                reasons.add(Reason.ATTACKING_RESIDENT);
            } else if (target instanceof Player player && qualifiesForAssistance(level, source, st, unit, player, now)) {
                reasons.add(Reason.ATTACKING_ALLY_PLAYER);
            }
        }
        if (incidents.recentlyAttackedVillage(unit.getUUID(), st.village, now)) {
            reasons.add(Reason.RECENT_ATTACKER);
        }
        return reasons;
    }

    private boolean qualifiesForAssistance(ServerLevel level, SettlementSource source, VillageState st,
                                                  LivingEntity unit, Player player, long now) {
        if (!HywMillConfig.GUARDS_ASSIST_PLAYERS.get() || player.isCreative() || player.isSpectator()) {
            return false;
        }
        if (!DefenseArea.inside(st.center.getX() + 0.5, st.center.getZ() + 0.5, st.defenseRadius, player.getX(), player.getZ())) {
            return false;
        }
        int reputation = source.playerReputation(level, st.village, player.getUUID());
        boolean struckFirst = player.getUUID().equals(incidents.firstStriker(player.getUUID(), unit.getUUID(), now));
        return AssistPolicy.qualifies(st.doctrine, player.getUUID(), st.controller, reputation, struckFirst);
    }

    private static String describe(CombatFactionService factions, List<Threat> threats) {
        return threats.stream()
                .map(t -> factions.describe(t.entity()) + t.reasons())
                .collect(Collectors.joining(", "));
    }

    // ---- queries (called every tick by goal decorators; keep O(1)/O(threats)) ----

    public boolean hasThreat(UUID village) {
        VillageState st = states.get(village);
        return st != null && !st.threats.isEmpty();
    }

    public boolean isThreat(UUID village, Entity entity) {
        VillageState st = states.get(village);
        return st != null && st.threatIds.contains(entity.getUUID());
    }

    @Nullable
    public LivingEntity nearestThreat(UUID village, Vec3 from, double maxDistance) {
        VillageState st = states.get(village);
        if (st == null) {
            return null;
        }
        LivingEntity best = null;
        double bestSq = maxDistance * maxDistance;
        for (Threat t : st.threats) {
            LivingEntity e = t.entity();
            if (!e.isAlive() || e.isRemoved()) {
                continue;
            }
            double d = e.distanceToSqr(from);
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        return best;
    }

    /** A current threat of the village by entity UUID (alive), or null. */
    @Nullable
    public LivingEntity threatEntity(UUID village, @Nullable UUID id) {
        VillageState st = states.get(village);
        if (st == null || id == null || !st.threatIds.contains(id)) {
            return null;
        }
        for (Threat t : st.threats) {
            if (t.entity().getUUID().equals(id)) {
                return t.entity().isAlive() && !t.entity().isRemoved() ? t.entity() : null;
            }
        }
        return null;
    }

    public int defenseRadius(UUID village) {
        VillageState st = states.get(village);
        return st == null ? 0 : st.defenseRadius;
    }

    public List<Threat> threats(UUID village) {
        VillageState st = states.get(village);
        return st == null ? List.of() : st.threats;
    }

    /** Village whose building bounds contain the position (tracked villages only), or null. */
    @Nullable
    public UUID villageContaining(BlockPos pos) {
        Vec3 p = Vec3.atCenterOf(pos);
        for (VillageState st : states.values()) {
            if (st.bounds != null && st.bounds.contains(p)) {
                return st.village;
            }
        }
        return null;
    }
}

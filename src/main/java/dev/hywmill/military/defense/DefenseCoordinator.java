package dev.hywmill.military.defense;

import dev.hywmill.military.ThreatTracker.Reason;
import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.military.doctrine.Doctrine;
import dev.hywmill.military.doctrine.MilitiaPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides which loaded defenders are committed to which threat, and which are held in reserve.
 * Pure and deterministic (unit-tested); runs once per village scan while the village is not CALM.
 *
 * <ol>
 *   <li><b>Eligible:</b> SOLDIER and LEADER; MILITIA only as the {@link MilitiaPolicy} allows.
 *       CIVILIAN and OUTLAW never.</li>
 *   <li><b>Reserve:</b> {@code reserve} defenders, only when at least {@code commitPerThreat + 2}
 *       are eligible. Previous reserve members stay if still eligible; the rest are chosen by
 *       professionals first, then distance to the defending position, then UUID.</li>
 *   <li><b>Actionable threats:</b> ATTACKING_RESIDENT, RECENT_ATTACKER or ATTACKING_ALLY_PLAYER;
 *       HYW_ENEMY alone only when {@code proactive=true}. Ordered by distance to the defending
 *       position, then UUID.</li>
 *   <li><b>Commit:</b> previous assignments are kept while still valid (no thrashing), then each
 *       threat is filled up to {@code commitPerThreat} from the unassigned, non-reserve pool by
 *       distance to that threat, then UUID. A defender is assigned to at most one threat.</li>
 * </ol>
 * Self-defense is not decided here: a defender that is itself attacked may always fight back.
 */
public final class DefenseCoordinator {
    private DefenseCoordinator() {}

    public static final Set<Reason> REACTIVE = EnumSet.of(Reason.ATTACKING_RESIDENT, Reason.RECENT_ATTACKER, Reason.ATTACKING_ALLY_PLAYER);

    public record Pos(double x, double y, double z) {
        double distSq(Pos o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    public record DefenderView(UUID id, VillagerRole role, Pos pos) {}

    public record ThreatView(UUID id, Pos pos, Set<Reason> reasons) {}

    public record Input(Doctrine doctrine, AlertState state, List<DefenderView> defenders, List<ThreatView> threats,
                        Pos defendingPos, Map<UUID, UUID> previousAssignments, Set<UUID> previousReserve) {}

    public record Result(Map<UUID, UUID> assignments, Set<UUID> reserve, Map<UUID, List<UUID>> byThreat, int eligible) {
        public static final Result EMPTY = new Result(Map.of(), Set.of(), Map.of(), 0);
    }

    public static boolean isActionable(Set<Reason> reasons, boolean proactive) {
        for (Reason r : reasons) {
            if (REACTIVE.contains(r)) {
                return true;
            }
        }
        return proactive && reasons.contains(Reason.HYW_ENEMY);
    }

    /** Residents are "actually under attack" when a threat damaged one recently. */
    public static boolean residentsUnderAttack(List<ThreatView> threats) {
        return threats.stream().anyMatch(t -> t.reasons().contains(Reason.RECENT_ATTACKER));
    }

    public static boolean militiaAvailable(MilitiaPolicy policy, AlertState state, boolean residentsUnderAttack) {
        return switch (policy) {
            case NEVER -> false;
            case WHEN_ATTACKED -> residentsUnderAttack && state != AlertState.CALM;
            case ON_ENGAGED -> state == AlertState.ENGAGED;
            case ALWAYS -> state == AlertState.ALERT || state == AlertState.ENGAGED;
        };
    }

    public static Result assign(Input in) {
        if (in.state() == AlertState.CALM) {
            return Result.EMPTY;
        }
        Doctrine d = in.doctrine();
        boolean militia = militiaAvailable(d.militiaPolicy(), in.state(), residentsUnderAttack(in.threats()));
        List<DefenderView> eligible = new ArrayList<>();
        for (DefenderView v : in.defenders()) {
            if (v.role() == VillagerRole.SOLDIER || v.role() == VillagerRole.LEADER
                    || (militia && v.role() == VillagerRole.MILITIA)) {
                eligible.add(v);
            }
        }
        eligible.sort(Comparator.comparing(DefenderView::id));
        Map<UUID, DefenderView> byId = new LinkedHashMap<>();
        eligible.forEach(v -> byId.put(v.id(), v));

        // Reserve
        Set<UUID> reserve = new LinkedHashSet<>();
        if (d.reserve() > 0 && eligible.size() >= d.commitPerThreat() + Doctrine.RESERVE_MIN_EXTRA) {
            in.previousReserve().stream().filter(byId::containsKey).sorted().limit(d.reserve()).forEach(reserve::add);
            List<DefenderView> candidates = new ArrayList<>(eligible);
            candidates.sort(Comparator.<DefenderView>comparingInt(v -> v.role() == VillagerRole.MILITIA ? 1 : 0)
                    .thenComparingDouble(v -> v.pos().distSq(in.defendingPos()))
                    .thenComparing(DefenderView::id));
            for (DefenderView v : candidates) {
                if (reserve.size() >= d.reserve()) {
                    break;
                }
                reserve.add(v.id());
            }
        }

        // Actionable threats, deterministic order
        List<ThreatView> threats = new ArrayList<>();
        for (ThreatView t : in.threats()) {
            if (isActionable(t.reasons(), d.proactive())) {
                threats.add(t);
            }
        }
        threats.sort(Comparator.<ThreatView>comparingDouble(t -> t.pos().distSq(in.defendingPos())).thenComparing(ThreatView::id));
        Map<UUID, ThreatView> threatById = new HashMap<>();
        threats.forEach(t -> threatById.put(t.id(), t));

        Map<UUID, UUID> assignments = new LinkedHashMap<>();
        Map<UUID, List<UUID>> byThreat = new LinkedHashMap<>();
        threats.forEach(t -> byThreat.put(t.id(), new ArrayList<>()));
        // 1. keep valid previous assignments (sorted for determinism)
        in.previousAssignments().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    UUID def = e.getKey();
                    UUID thr = e.getValue();
                    if (byId.containsKey(def) && !reserve.contains(def) && threatById.containsKey(thr)
                            && byThreat.get(thr).size() < d.commitPerThreat()) {
                        assignments.put(def, thr);
                        byThreat.get(thr).add(def);
                    }
                });
        // 2. fill each threat up to commitPerThreat
        for (ThreatView t : threats) {
            List<UUID> committed = byThreat.get(t.id());
            if (committed.size() >= d.commitPerThreat()) {
                continue;
            }
            List<DefenderView> pool = new ArrayList<>();
            for (DefenderView v : eligible) {
                if (!reserve.contains(v.id()) && !assignments.containsKey(v.id())) {
                    pool.add(v);
                }
            }
            pool.sort(Comparator.<DefenderView>comparingDouble(v -> v.pos().distSq(t.pos())).thenComparing(DefenderView::id));
            for (DefenderView v : pool) {
                if (committed.size() >= d.commitPerThreat()) {
                    break;
                }
                assignments.put(v.id(), t.id());
                committed.add(v.id());
            }
        }
        return new Result(assignments, reserve, byThreat, eligible.size());
    }
}

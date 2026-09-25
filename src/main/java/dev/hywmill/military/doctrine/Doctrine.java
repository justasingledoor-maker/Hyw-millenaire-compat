package dev.hywmill.military.doctrine;

import java.util.EnumMap;
import java.util.Map;

/**
 * A village's effective doctrine: every field resolved, plus the final defense radius
 * ({@code villageRadius + radiusOffset + tier radius}, clamped to 40..160).
 */
public record Doctrine(
        int radiusOffset,
        int defenseRadius,
        boolean proactive,
        int commitPerThreat,
        int reserve,
        MilitiaPolicy militiaPolicy,
        int shelterRadius,
        AssistMode assistPlayers,
        int assistMinReputation,
        boolean assistProvokingPlayer,
        ControllerAssist assistController,
        int alertTicks,
        int engagedTicks,
        int recoveryTicks
) {
    public static final int MIN_DEFENSE_RADIUS = 40;
    public static final int MAX_DEFENSE_RADIUS = 160;
    /** The reserve is only withheld when at least {@code commitPerThreat + RESERVE_MIN_EXTRA} defenders are eligible. */
    public static final int RESERVE_MIN_EXTRA = 2;
    /** shelterRadius value meaning "every civilian of the village". */
    public static final int VILLAGE_WIDE = -1;

    static Doctrine of(Map<DoctrineField, Object> v, int defenseRadius, int commit, int reserve) {
        return new Doctrine(
                (Integer) v.get(DoctrineField.RADIUS_OFFSET),
                defenseRadius,
                (Boolean) v.get(DoctrineField.PROACTIVE),
                commit,
                reserve,
                (MilitiaPolicy) v.get(DoctrineField.MILITIA_POLICY),
                (Integer) v.get(DoctrineField.SHELTER_RADIUS),
                (AssistMode) v.get(DoctrineField.ASSIST_PLAYERS),
                (Integer) v.get(DoctrineField.ASSIST_MIN_REPUTATION),
                (Boolean) v.get(DoctrineField.ASSIST_PROVOKING_PLAYER),
                (ControllerAssist) v.get(DoctrineField.ASSIST_CONTROLLER),
                (Integer) v.get(DoctrineField.ALERT_TICKS),
                (Integer) v.get(DoctrineField.ENGAGED_TICKS),
                (Integer) v.get(DoctrineField.RECOVERY_TICKS));
    }

    /** Field values as displayed and compared (commit/reserve are the final, tier-adjusted values). */
    public Map<DoctrineField, Object> asMap() {
        Map<DoctrineField, Object> m = new EnumMap<>(DoctrineField.class);
        m.put(DoctrineField.RADIUS_OFFSET, radiusOffset);
        m.put(DoctrineField.PROACTIVE, proactive);
        m.put(DoctrineField.COMMIT_PER_THREAT, commitPerThreat);
        m.put(DoctrineField.RESERVE, reserve);
        m.put(DoctrineField.MILITIA_POLICY, militiaPolicy);
        m.put(DoctrineField.SHELTER_RADIUS, shelterRadius);
        m.put(DoctrineField.ASSIST_PLAYERS, assistPlayers);
        m.put(DoctrineField.ASSIST_MIN_REPUTATION, assistMinReputation);
        m.put(DoctrineField.ASSIST_PROVOKING_PLAYER, assistProvokingPlayer);
        m.put(DoctrineField.ASSIST_CONTROLLER, assistController);
        m.put(DoctrineField.ALERT_TICKS, alertTicks);
        m.put(DoctrineField.ENGAGED_TICKS, engagedTicks);
        m.put(DoctrineField.RECOVERY_TICKS, recoveryTicks);
        return m;
    }
}

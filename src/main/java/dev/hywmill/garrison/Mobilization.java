package dev.hywmill.garrison;

import dev.hywmill.military.MilitaryTier;

/**
 * Wartime mobilization (post-M5): the pure rules. A village that is not a stronghold fills its garrison up to its current
 * target (never past its tier's unit cap) when it goes to war; the fresh troops are equipped a little below its regulars.
 */
public final class Mobilization {
    private Mobilization() {}

    /** Whether a village of this tier mobilizes at all (strongholds keep a standing army; lone buildings and NONE have none). */
    public static boolean mobilizes(MilitaryTier tier, boolean loneBuilding) {
        return !loneBuilding && tier != MilitaryTier.STRONGHOLD && tier != MilitaryTier.NONE;
    }

    /** Soldiers to raise: the gap between the living garrison and the current target, capped by the tier's maximum. */
    public static int count(int live, int target, int tierMax) {
        return Math.max(0, Math.min(target, tierMax) - live);
    }

    /** Levy weights: the village's composition plus the extra levy units (shieldmen, spearmen). */
    public static java.util.Map<String, Integer> weights(java.util.Map<String, Integer> composition, java.util.Map<String, Integer> levyUnits) {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>(composition);
        levyUnits.forEach((k, v) -> m.merge(k, v, Integer::sum));
        return m;
    }

    /** Levies to raise now while at war: up to {@code batch} if the last top-up is at least {@code interval} ago and below target. */
    public static int topUp(int live, int target, int tierMax, long now, long lastLevy, long interval, int batch) {
        if (lastLevy >= 0 && now - lastLevy < interval) {
            return 0;
        }
        return Math.min(Math.max(0, batch), count(live, target, tierMax));
    }

    /** Equipment level of a mobilized soldier: a step below the regulars, but never below {@code floor} (no clubs). */
    public static int equipmentLevel(int regular, int floor, int drop) {
        return Math.max(floor, regular - drop);
    }
}

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

    /** Equipment level of a mobilized soldier: a step below the regulars, but never below {@code floor} (no clubs). */
    public static int equipmentLevel(int regular, int floor, int drop) {
        return Math.max(floor, regular - drop);
    }
}

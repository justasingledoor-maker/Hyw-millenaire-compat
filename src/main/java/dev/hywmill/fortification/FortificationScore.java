package dev.hywmill.fortification;

import dev.hywmill.settlement.SettlementSnapshot;

/**
 * Fortification score, computed only from buildings the settlement mod reports as
 * operational (complete). Under-construction buildings count for nothing.
 *
 * <pre>
 *   score = 1 x wall segments
 *         + 2 x wall towers            (wall segments that are also patrol-tagged, on top of the 1 above)
 *         + 3 x defensive buildings    (non-wall, patrol-tagged: guardhouses, watchtowers, fort towers)
 *         + 5 if the town hall is a fort
 * </pre>
 */
public final class FortificationScore {
    public static final int WALL_SEGMENT = 1;
    public static final int WALL_TOWER_BONUS = 2;
    public static final int DEFENSIVE_BUILDING = 3;
    public static final int FORT_TOWNHALL = 5;

    private FortificationScore() {}

    public static int compute(SettlementSnapshot s) {
        return s.wallSegments() * WALL_SEGMENT
                + s.wallTowers() * WALL_TOWER_BONUS
                + s.defensiveBuildings() * DEFENSIVE_BUILDING
                + (s.fortTownhall() ? FORT_TOWNHALL : 0);
    }
}

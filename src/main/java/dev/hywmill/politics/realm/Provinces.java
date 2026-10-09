package dev.hywmill.politics.realm;

/**
 * Provinces and military allies at war (docs/realm-design.md §1, §4): how much of a province's garrison is of its sovereign's
 * culture, and how much of its (or an ally's) free garrison marches with the sovereign's (the ally's) siege host. Pure.
 */
public final class Provinces {
    private Provinces() {}

    /** Share of a province's recruits of its sovereign's culture (the rest are of its own). */
    public static final double SOVEREIGN_SHARE = 0.7;
    public static final double PROVINCE_MIN = 0.3, PROVINCE_MAX = 0.5, ALLY_MIN = 0.2, ALLY_MAX = 0.35;

    /** A province's recruit number {@code seq} is of its sovereign's people (its unit table, kit and colours): 7 in 10. */
    public static boolean sovereignPick(java.util.UUID village, int seq) {
        return new java.util.SplittableRandom(village.getLeastSignificantBits() ^ (seq * 0x9E3779B97F4A7C15L)).nextDouble() < SOVEREIGN_SHARE;
    }

    /** How many of {@code available} free soldiers march with the host: 30-50% for a province, 20-35% for an ally (0 if none). */
    public static int levy(int available, double draw, boolean province) {
        if (available <= 0) {
            return 0;
        }
        double lo = province ? PROVINCE_MIN : ALLY_MIN, hi = province ? PROVINCE_MAX : ALLY_MAX;
        double share = lo + (hi - lo) * Math.max(0, Math.min(1, draw));
        return Math.max(1, Math.min(available, (int) Math.round(available * share)));
    }
}

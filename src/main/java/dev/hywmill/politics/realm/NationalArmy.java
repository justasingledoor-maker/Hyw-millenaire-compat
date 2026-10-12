package dev.hywmill.politics.realm;

/**
 * The national army (docs/realm-design.md §9): a realm whose capital has at least {@link #MIN_SUBJECTS} subjects has one army,
 * a muster roll rather than a standing host. Each member pledges a share of its free garrison (at home, on a standing duty):
 * the capital {@link #CAPITAL}, a province {@link #PROVINCE}, a vassal {@link #VASSAL} at loyalty 50 (more or less with its
 * loyalty, within {@link #VASSAL_MIN}..{@link #VASSAL_MAX}). The pledged men march together: with the capital's sieges, and to
 * break a siege of any member. Pure.
 */
public final class NationalArmy {
    private NationalArmy() {}

    public static final int MIN_SUBJECTS = 2;
    public static final double CAPITAL = 0.6, PROVINCE = 0.4, VASSAL = 0.25, VASSAL_MIN = 0.1, VASSAL_MAX = 0.4;

    /** A realm with this many subjects raises a national army. */
    public static boolean raised(int subjects) {
        return subjects >= MIN_SUBJECTS;
    }

    /** A vassal's share: {@link #VASSAL} at loyalty 50, in proportion to its loyalty, within its bounds. */
    public static double vassalShare(double loyalty) {
        return Math.max(VASSAL_MIN, Math.min(VASSAL_MAX, VASSAL * loyalty / 50.0));
    }

    /** The share a member pledges: the capital's, a province's, or a vassal's (by its loyalty). */
    public static double share(boolean capital, boolean province, double loyalty) {
        return capital ? CAPITAL : province ? PROVINCE : vassalShare(loyalty);
    }

    /** Men pledged from {@code free} soldiers at home: the share, rounded, at least one while any are free. */
    public static int pledge(int free, double share) {
        if (free <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(free, (int) Math.round(free * share)));
    }
}

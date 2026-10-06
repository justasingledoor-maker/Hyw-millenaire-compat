package dev.hywmill.politics.war;

/**
 * Alliances in war (post-M5). When a village is attacked, each of its allies (a relation of at least {@link #ALLY} to it,
 * or its vassal, or its overlord) must choose: honour the alliance and declare war on the attacker, or break it. A village
 * that is the ally of both sides stays out (it helps neither). An ally that joins is attacked in turn, so its own allies are
 * called, and so on, up to {@link #MAX_DEPTH} steps from the first war and {@link #MAX_JOINS} joiners. Pure.
 */
public final class Alliance {
    private Alliance() {}

    public static final int ALLY = 70;
    /** The relation an ally that broke the alliance is left at (cold, not hostile). */
    public static final int BROKEN = 10;
    public static final int MAX_DEPTH = 2, MAX_JOINS = 8;

    public enum Choice { JOIN, BREAK, NEUTRAL }

    /**
     * The chance that an ally honours the alliance: 40% at the threshold, more the closer the friendship; much more for a
     * vassal (sworn to it) and for one that already dislikes the attacker; less the further the chain of alliances runs.
     */
    public static double joinChance(int relationToAttacked, int relationToAttacker, boolean sworn, int depth) {
        double p = 0.40 + Math.max(0, relationToAttacked - ALLY) / 60.0;
        if (relationToAttacker < 0) {
            p += 0.20;
        }
        if (sworn) {
            p += 0.40;
        }
        p -= 0.15 * Math.max(0, depth);
        return Math.max(0.10, Math.min(0.95, p));
    }

    /** The ally's choice: an ally of both sides stays out; else it joins at {@link #joinChance}, or breaks (draw in [0, 1)). */
    public static Choice choose(int relationToAttacked, int relationToAttacker, boolean sworn, int depth, double draw) {
        if (relationToAttacker >= ALLY && !sworn) {
            return Choice.NEUTRAL;
        }
        return draw < joinChance(relationToAttacked, relationToAttacker, sworn, depth) ? Choice.JOIN : Choice.BREAK;
    }
}

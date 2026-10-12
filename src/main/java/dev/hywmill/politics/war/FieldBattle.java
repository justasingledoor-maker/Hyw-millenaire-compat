package dev.hywmill.politics.war;

/**
 * Field battles (docs/realm-design.md §13): a realm's council sends an army to meet an enemy host on the road before it
 * reaches one of its villages. It goes only when its army is at least the host's match ({@link #MARGIN}) and there is time
 * to meet it before the walls; the battle is fought halfway along what is left of the march (no walls, no engines). Each
 * player in the realm's service who stands at the meeting point adds {@link #PLAYER} to its strength. Pure.
 */
public final class FieldBattle {
    private FieldBattle() {}

    public static final double MARGIN = 1.0;
    /** The battle must be at least this long (ticks) before the host would reach the walls, and this long from now. */
    public static final long LEAD = 1200;
    public static final double PLAYER = 12;
    /** Players count at the meeting point within this distance (blocks). */
    public static final int REACH = 48;

    /** The army would go: a match for the host. */
    public static boolean worthIt(double ours, double host) {
        return host > 0 && ours >= host * MARGIN;
    }

    /**
     * When (in the siege's elapsed time) the armies meet, or -1 if they cannot before the walls: halfway along the march still
     * to come, and not sooner than {@link #LEAD} from now.
     */
    public static long meetAt(long elapsedNow, long arriveAt, long march) {
        long marchStart = arriveAt - march;
        long from = Math.max(elapsedNow, marchStart);
        long at = Math.max(elapsedNow + LEAD, from + (arriveAt - from) / 2);
        return arriveAt - at >= LEAD ? at : -1;
    }

    /** How far along its road (0 at home, 1 at the walls) the host is at elapsed time {@code at}. */
    public static double along(long at, long arriveAt, long march) {
        if (march <= 0) {
            return 1;
        }
        return Math.max(0, Math.min(1, (at - (arriveAt - march)) / (double) march));
    }
}

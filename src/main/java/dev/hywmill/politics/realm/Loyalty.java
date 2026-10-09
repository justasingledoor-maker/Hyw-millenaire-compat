package dev.hywmill.politics.realm;

/**
 * Loyalty of a vassal or province (docs/realm-design.md §5), 0-100. Pure.
 */
public final class Loyalty {
    private Loyalty() {}

    public static final double START_PROVINCE = 50, START_VASSAL = 40;
    public static final double REST_PROVINCE = 60, REST_VASSAL = 50, DRIFT = 2;
    public static final double FOREIGN = 0.5, FAR = 0.5, FAR_BLOCKS = 1000;
    public static final double PER_MAN_LOST = 0.5, MAX_LOSS_PER_SIEGE = 15;
    public static final double FELL = -20, HELD = 10, VICTORY = 3;
    public static final double REBEL_BELOW = 30, REBEL_VASSAL = 0.25, REBEL_PROVINCE = 0.06;

    public static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    /** One day: a drift towards its resting point, less for a foreign culture and for distance from the sovereign. */
    public static double daily(double loyalty, boolean province, boolean sameCulture, double distance) {
        double rest = province ? REST_PROVINCE : REST_VASSAL;
        double v = loyalty + Math.max(-DRIFT, Math.min(DRIFT, rest - loyalty));
        v -= sameCulture ? 0 : FOREIGN;
        v -= distance > FAR_BLOCKS ? FAR : 0;
        return clamp(v);
    }

    /** The loyalty lost for {@code killed} of its soldiers dying in one of the sovereign's sieges. */
    public static double losses(int killed) {
        return -Math.min(MAX_LOSS_PER_SIEGE, Math.max(0, killed) * PER_MAN_LOST);
    }

    /** The day's chance of rebellion (0 at or above {@link #REBEL_BELOW}); doubled when the sovereign is the weaker. */
    public static double rebelChance(double loyalty, boolean province, boolean sovereignWeaker) {
        if (loyalty >= REBEL_BELOW) {
            return 0;
        }
        double p = (REBEL_BELOW - loyalty) / REBEL_BELOW * (province ? REBEL_PROVINCE : REBEL_VASSAL);
        return Math.min(1, p * (sovereignWeaker ? 2 : 1));
    }
}

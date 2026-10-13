package dev.hywmill.politics.war;

/**
 * Supply trains (1.10, docs/siege-design.md "War on the roads"): a siege lives on what the roads bring. The besieger's realm
 * sends trains of carts to its host's camp through the siege's days; the besieged's friends send theirs before the host is at
 * the walls. A train that arrives stiffens its side; one cut on the road weakens it, and a host whose trains are cut more than
 * they arrive cannot keep the field as long. Whoever cuts a train takes its carts and a bounty from his side. Pure.
 */
public final class Supply {
    private Supply() {}

    /** Resolve a train brings (at most two count), and what a cut one takes away (at most two count). */
    public static final double FED = 0.02, STARVED = 0.03;
    /** Guards with a train: four to seven. */
    public static final int GUARDS_MIN = 4, GUARDS_MAX = 7;
    /** Trains a side sends: one, and one more for each friend that can spare them, at most three. */
    public static final int MAX_TRAINS = 3;
    /** The bounty for cutting a train, per cart (deniers: 8 deniers argent). */
    public static final int BOUNTY_PER_CART = 512;
    /** Supply trains go at a cart's pace (blocks a tick). */
    public static final double SPEED = 0.12;

    /** What a side's supply does to its resolve: trains arrived stiffen it, trains cut weaken it. */
    public static double resolve(int arrived, int cut) {
        return FED * Math.min(2, Math.max(0, arrived)) - STARVED * Math.min(2, Math.max(0, cut));
    }

    /** A host keeps the field a day less when more of its trains were cut than arrived (never less than one day). */
    public static int days(int days, int arrived, int cut) {
        return cut > arrived ? Math.max(1, days - 1) : days;
    }

    /** How many trains a side sends: one, and one for each friend that can spare one, at most {@link #MAX_TRAINS}. */
    public static int trains(int friends) {
        return Math.max(1, Math.min(MAX_TRAINS, 1 + Math.max(0, friends)));
    }

    /** The guards of a train ({@code draw} in 0..1). */
    public static int guards(double draw) {
        return GUARDS_MIN + (int) Math.floor(Math.max(0, Math.min(0.999, draw)) * (GUARDS_MAX - GUARDS_MIN + 1));
    }

    /** The bounty for cutting a train of {@code carts} carts. */
    public static int bounty(int carts) {
        return BOUNTY_PER_CART * Math.max(1, carts);
    }
}

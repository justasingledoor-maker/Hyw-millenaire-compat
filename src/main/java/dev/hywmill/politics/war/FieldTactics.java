package dev.hywmill.politics.war;

import java.util.SplittableRandom;

/**
 * Field battles fought in the world (1.8, docs/realm-design.md §16): the roles in an army, the doctrines a side may fight by
 * and which beats which, how a side chooses one against what its scouts saw of the other, and each group's order at each
 * moment of the battle. Pure.
 */
public final class FieldTactics {
    private FieldTactics() {}

    /** The armies' lines stand this far apart while they face each other (blocks). */
    public static final int GAP = 120;
    /** The ceasefire before the battle (ticks: 20 min). */
    public static final long CEASEFIRE = 24000;
    /** A side whose standing men are down to this share of what it brought routs. */
    public static final double ROUT = 0.4;
    /** A side whose foe is down to this share of its men pursues with everything. */
    public static final double PURSUE = 0.55;
    /** A side on the attack that is down to this share of its men rallies into a shield wall (once). */
    public static final double RALLY = 0.65;
    /** The longest the fighting lasts (ticks: 6 min); then the side with more of its men standing holds the field. */
    public static final long FIGHT_MAX = 7200;

    public enum Role { SHIELD, MELEE, RANGED, CAVALRY, ENGINE }

    /** A unit's role from its HYW entity type ({@code arsenal}: a war engine or its crew). */
    public static Role role(String entityType, boolean arsenal) {
        if (arsenal) {
            return Role.ENGINE;
        }
        String t = entityType == null ? "" : entityType;
        if (t.contains("_rider")) {
            return Role.CAVALRY;
        }
        if (t.contains("archer") || t.contains("crossbow") || t.contains("handgonne") || t.contains("matchlock")) {
            return Role.RANGED;
        }
        if (t.contains("shield") || t.contains("spear")) {
            return Role.SHIELD;
        }
        return Role.MELEE;
    }

    public enum Doctrine {
        HAMMER("the hammer", "the warriors charge first with the archers close behind; the shield line closes in after them, the riders strike a flank"),
        SHIELD_WALL("the shield wall", "a wall of shields and spears holds the field with the archers shooting over it; the warriors fall on whoever breaks on it"),
        SKIRMISH("the skirmish", "the archers go forward and shoot, screened by the shields; they give ground before a charge and the warriors counter-charge"),
        FLANK("the pincer", "the shield line and the archers hold the centre while the warriors and riders swing round both flanks");

        public final String label, plan;

        Doctrine(String label, String plan) {
            this.label = label;
            this.plan = plan;
        }

        public boolean aggressive() {
            return this == HAMMER || this == FLANK;
        }
    }

    //                               HAMMER  WALL   SKIRM  FLANK
    private static final double[][] E = {{0, -1, 1, 0.5},      // the hammer breaks on a wall, catches skirmishers, hits a thinned centre
                                          {1, 0, -1, -0.5},     // the wall stops a charge, is shot to pieces, is turned
                                          {-1, 1, 0, -0.5},     // skirmishers are caught by a charge, whittle a wall, are run down on the wings
                                          {-0.5, 0.5, 0.5, 0}}; // the pincer turns a wall and runs down skirmishers, loses its centre to a charge

    /** How much doctrine {@code a} beats {@code b}: +1 it wins the clash of plans, -1 it loses it. */
    public static double edge(Doctrine a, Doctrine b) {
        return E[a.ordinal()][b.ordinal()];
    }

    /** An army's make-up by role. */
    public record Mix(int shield, int melee, int ranged, int cavalry, int engines) {
        public int men() {
            return shield + melee + ranged + cavalry;
        }

        double share(int n) {
            return men() <= 0 ? 0 : n / (double) men();
        }
    }

    /** How well an army of {@code m} can fight by {@code d} (0..about 1). */
    public static double suits(Doctrine d, Mix m) {
        double sh = m.share(m.shield()), me = m.share(m.melee()), ra = m.share(m.ranged()), ca = m.share(m.cavalry());
        return switch (d) {
            case HAMMER -> me + 0.5 * ca + 0.3 * sh;
            case SHIELD_WALL -> sh + 0.5 * ra;
            case SKIRMISH -> ra + 0.4 * ca + 0.3 * sh;
            case FLANK -> ca + 0.6 * me + 0.1 * sh;
        };
    }

    /**
     * The doctrine a side fights by: the one that does best against what the other side would likely choose (its scouts saw
     * its make-up), weighed by how well its own men suit it; the stronger side leans to attack, the weaker to defend, and its
     * marshal's temper ({@code temper} &gt; 0 bold, &lt; 0 cautious) leans it further. A little chance (seeded) breaks ties.
     */
    public static Doctrine choose(Mix ours, Mix theirs, double ratio, int temper, long seed) {
        Doctrine[] ds = Doctrine.values();
        double[] p = new double[ds.length];
        double sum = 0;
        for (int i = 0; i < ds.length; i++) {
            p[i] = Math.exp(4 * suits(ds[i], theirs));
            sum += p[i];
        }
        SplittableRandom rnd = new SplittableRandom(seed);
        Doctrine best = Doctrine.SHIELD_WALL;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Doctrine d : ds) {
            double score = 1.5 * suits(d, ours);
            for (int i = 0; i < ds.length; i++) {
                score += p[i] / sum * edge(d, ds[i]);
            }
            double lean = (ratio > 1.2 ? 0.15 : ratio < 0.8 ? -0.15 : 0) + 0.15 * Integer.signum(temper);
            score += d.aggressive() ? lean : -lean;
            score += rnd.nextDouble() * 0.05;
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    /** What a group does now. */
    public enum Move {
        /** Stand at its place in the line, {@code depth} blocks ahead of the line. */
        HOLD,
        /** Go forward to {@code depth} ahead of the line, or (depth &lt; 0) to -depth short of the enemy's front. */
        ADVANCE,
        /** Keep {@code depth} behind the group it follows (the warriors in the hammer, else the shield line). */
        FOLLOW,
        /** Stand {@code depth} in front of the archers, between them and the enemy. */
        SCREEN,
        /** Swing round a flank of the enemy ({@code depth}: how wide), each man to his side, and fall on it from there. */
        WHEEL,
        /** Seek out the nearest foe and fight. */
        CHARGE,
        /** Give ground: back behind the shield line. */
        FALL_BACK,
        /** A war engine: shoots at what is in reach, moves up when nothing is. */
        BOMBARD
    }

    /** An order: the move, its depth (see {@link Move}) and how near a foe must come before the men turn on him. */
    public record Order(Move move, double depth, int engage) {}

    /**
     * Where the battle stands for one side: ticks since it began, whether its men have come to blows, its standing share and
     * the foe's (of what each brought), and how near the nearest foe is to its archers and to its shield line.
     */
    public record Situation(long t, boolean contact, double ours, double theirs, double foeToRanged, double foeToShield) {}

    /** The order for one role of a side fighting by {@code d}. */
    public static Order order(Doctrine d, Role role, Situation s) {
        if (role == Role.ENGINE) {
            return new Order(Move.BOMBARD, 0, 0);
        }
        if (s.theirs() <= PURSUE) {
            return new Order(Move.CHARGE, 0, 64); // the foe is breaking: everyone after him
        }
        return switch (d) {
            case HAMMER -> switch (role) {
                case MELEE -> new Order(Move.CHARGE, 0, 64);
                case RANGED -> s.foeToRanged() < 8 ? new Order(Move.FALL_BACK, 8, 6) : new Order(Move.FOLLOW, 16, 36);
                case SHIELD -> s.t() < 600 && !s.contact() ? new Order(Move.HOLD, 10, 8) : new Order(Move.CHARGE, 0, 48);
                case CAVALRY -> s.contact() ? new Order(Move.WHEEL, 35, 24) : new Order(Move.HOLD, 0, 10);
                default -> new Order(Move.BOMBARD, 0, 0);
            };
            case SHIELD_WALL -> switch (role) {
                case SHIELD -> new Order(Move.ADVANCE, 20, 8);
                case RANGED -> s.foeToRanged() < 8 ? new Order(Move.FALL_BACK, 8, 6) : new Order(Move.FOLLOW, 6, 36);
                case MELEE -> s.foeToShield() < 12 ? new Order(Move.WHEEL, 12, 20) : new Order(Move.HOLD, 10, 8);
                case CAVALRY -> s.theirs() < 0.7 ? new Order(Move.CHARGE, 0, 64) : new Order(Move.HOLD, 0, 12);
                default -> new Order(Move.BOMBARD, 0, 0);
            };
            case SKIRMISH -> switch (role) {
                case RANGED -> s.foeToRanged() < 12 ? new Order(Move.FALL_BACK, 10, 6) : new Order(Move.ADVANCE, -30, 40);
                case SHIELD -> new Order(Move.SCREEN, 8, 8);
                case MELEE -> s.foeToRanged() < 16 || s.foeToShield() < 10 ? new Order(Move.CHARGE, 0, 48) : new Order(Move.HOLD, 0, 8);
                case CAVALRY -> new Order(Move.WHEEL, 30, 30);
                default -> new Order(Move.BOMBARD, 0, 0);
            };
            case FLANK -> switch (role) {
                case SHIELD -> new Order(Move.ADVANCE, 30, 8);
                case RANGED -> s.foeToRanged() < 8 ? new Order(Move.FALL_BACK, 8, 6) : new Order(Move.FOLLOW, 6, 36);
                case MELEE -> s.t() < 900 && !s.contact() ? new Order(Move.WHEEL, 40, 16) : new Order(Move.CHARGE, 0, 48);
                case CAVALRY -> s.t() < 600 ? new Order(Move.WHEEL, 55, 20) : new Order(Move.CHARGE, 0, 64);
                default -> new Order(Move.BOMBARD, 0, 0);
            };
        };
    }

    /** A side on the attack, down to {@link #RALLY} of its men, rallies once into a shield wall. */
    public static boolean rallies(Doctrine d, double ours, boolean rallied) {
        return !rallied && d.aggressive() && ours <= RALLY;
    }

    /** A side routs: its standing men are down to {@link #ROUT} of what it brought. */
    public static boolean routs(int standing, int start) {
        return start > 0 && standing <= ROUT * start;
    }

    /** The chance (paper) that a side of strength {@code ours} fighting by {@code a} beats one of {@code theirs} fighting by {@code b}. */
    public static double winChance(double ours, double theirs, Doctrine a, Doctrine b) {
        double x = ours * (1 + 0.2 * edge(a, b)), y = theirs * (1 + 0.2 * edge(b, a));
        double p = x * x + y * y <= 0 ? 0.5 : x * x / (x * x + y * y);
        return Math.max(0.05, Math.min(0.95, p));
    }
}

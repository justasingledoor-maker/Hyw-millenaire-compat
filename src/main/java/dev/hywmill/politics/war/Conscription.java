package dev.hywmill.politics.war;

import java.util.SplittableRandom;

/**
 * Before a siege (1.9, docs/siege-design.md "Levies and scouts"): what each side knows of the other and what the besieged do
 * about it. A siege is declared, so both sides hear of it at once, but only by rumour: a host's size is off by up to two fifths
 * either way until scouts count it. The besieged then press their countryside into service: poorly armed levies, enough to
 * stand at {@link #EVEN} of the host's numbers as they believe them (their walls make up the rest), as many as the land can
 * give. Hopelessly outmatched they offer to yield; a host that finds the defence far stronger than it was told calls it off.
 * Pure.
 */
public final class Conscription {
    private Conscription() {}

    /** The besieged raise levies to stand at this share of the host's numbers. */
    public static final double EVEN = 0.85;
    /** A rumour is off by up to this much either way. */
    public static final double RUMOUR = 0.4;
    /** A side is surprised when the enemy is this much stronger than it believed (and it never scouted it). */
    public static final double SURPRISE = 1.33;
    /** The besieged offer to yield when their strength (walls, levies, help counted) is under this share of the host's. */
    public static final double HOPELESS = 0.4;
    /** The host calls the siege off when the defence turns out stronger than this share of its own. */
    public static final double OUTMATCHED = 1.25;
    public static final int MAX_LEVY = 60;

    /** How many the land can give: a few, a third of the village's people, a sixth of its realm neighbours'. */
    public static int cap(int population, int neighbours) {
        return Math.min(MAX_LEVY, 4 + Math.max(0, population) / 3 + Math.max(0, neighbours) / 6);
    }

    /** The levies to raise against a host believed {@code hostBelieved} strong, with {@code defenders} at hand already. */
    public static int levies(int hostBelieved, int defenders, int cap) {
        return Math.max(0, Math.min(cap, (int) Math.ceil(EVEN * hostBelieved) - Math.max(0, defenders)));
    }

    /** A rumour of a number: off by up to {@link #RUMOUR} either way (seeded). */
    public static int rumour(int actual, long seed) {
        double f = 1 - RUMOUR + 2 * RUMOUR * new SplittableRandom(seed).nextDouble();
        return Math.max(1, (int) Math.round(actual * f));
    }

    /** Surprised: never scouted, and the enemy came a third stronger than believed. */
    public static boolean surprised(int actual, int believed, boolean scouted) {
        return !scouted && actual > believed * SURPRISE;
    }

    public static boolean hopeless(double defense, double host) {
        return host > 0 && defense < HOPELESS * host;
    }

    public static boolean outmatched(double defense, double host) {
        return host > 0 && defense > OUTMATCHED * host;
    }

    /**
     * The host takes a surrender offered before the assault: one sent to subjugate or punish nearly always (it wins what it
     * came for without a fight), one sent to annex mostly, one sent to raze seldom (it came to destroy).
     */
    public static double accepts(String aim) {
        return switch (aim) {
            case "RAZE" -> 0.2;
            case "ANNEX" -> 0.7;
            default -> 0.9;
        };
    }
}

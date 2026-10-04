package dev.hywmill.politics.war;

import java.util.SplittableRandom;

/**
 * Sieges in waves (post-M5; docs/siege-design.md): a siege is fought over up to three days, one wave a day from dawn to
 * sundown. A soldier struck down in a wave is, by chance, only wounded: carried off the field, he misses the rest of it and
 * stands again at the next dawn (or dies of his wounds in the night). A war engine wrecked in a wave is, by the same
 * chance, repairable overnight. The first side down to {@link #BREAK} of its starting strength (alive or wounded) loses;
 * if neither is after the third wave, the siege ends in a stalemate and the attackers go home. Pure.
 */
public final class SiegeWaves {
    private SiegeWaves() {}

    public static final int WAVES = 3;
    /** Chance that a fallen soldier is only wounded: defenders fight at home (shelter, surgeons, their own beds). */
    public static final double ATTACKER_WOUND = 0.35, DEFENDER_WOUND = 0.45;
    /** Chance that a wounded soldier dies in the night. */
    public static final double SUCCUMB = 0.1;
    /** A side down to this share of its starting strength (alive or wounded) is beaten. */
    public static final double BREAK = 0.2;
    public static final long DAY = 24000;
    /** A wave lasts from dawn to sundown; at least WAVE_MIN, at most WAVE_MAX ticks (no daylight cycle: half a day). */
    public static final long WAVE_MIN = 2400, WAVE_MAX = 12000;
    /** A night lasts until dawn; at least NIGHT_MIN, at most NIGHT_MAX ticks. */
    public static final long NIGHT_MIN = 600, NIGHT_MAX = 12000;

    /** Night: from sundown (12,500) to dawn (23,500) in day time. */
    public static boolean night(long dayTime) {
        long t = Math.floorMod(dayTime, DAY);
        return t >= 12500 && t < 23500;
    }

    /** The wave is over: the sun has set (after the shortest wave), or the longest wave has been fought. */
    public static boolean waveOver(long ticksSinceDawn, long dayTime) {
        return ticksSinceDawn >= WAVE_MAX || (ticksSinceDawn >= WAVE_MIN && night(dayTime));
    }

    /** The night is over: the sun is up (after the shortest night), or the longest night has passed. */
    public static boolean nightOver(long ticksSinceSundown, long dayTime) {
        return ticksSinceSundown >= NIGHT_MAX || (ticksSinceSundown >= NIGHT_MIN && !night(dayTime));
    }

    public static double woundChance(boolean defender) {
        return defender ? DEFENDER_WOUND : ATTACKER_WOUND;
    }

    /** Whether one who fell is only wounded (draw in [0, 1)). */
    public static boolean wounded(boolean defender, double draw) {
        return draw < woundChance(defender);
    }

    /** Share of a side still in the fight or able to return to it. */
    public static double share(int aliveOrWounded, int start) {
        return start <= 0 ? 0 : Math.max(0, aliveOrWounded) / (double) start;
    }

    /**
     * The verdict after (or during) a wave: a side down to {@link #BREAK} loses (the attackers first, if both are: a host
     * that cannot hold the field gives up). After the last wave, neither beaten: a stalemate. Otherwise none yet.
     */
    public static Siege.Outcome verdict(int host, int hostStart, int defenders, int defendersStart, int wave, boolean waveDone) {
        if (host <= 0 || share(host, hostStart) <= BREAK) {
            return Siege.Outcome.LOST;
        }
        if (defenders <= 0 || share(defenders, defendersStart) <= BREAK) {
            return Siege.Outcome.WON;
        }
        if (waveDone && wave >= WAVES) {
            return Siege.Outcome.STALEMATE;
        }
        return Siege.Outcome.NONE;
    }

    /** What one wave did to a side: how many fell dead and how many were wounded. */
    public record Toll(int dead, int wounded) {
        public int fallen() {
            return dead + wounded;
        }
    }

    /** The day's result of a wave fought far from any witness. */
    public record Paper(boolean attackersHeld, Toll host, Toll defenders) {}

    /**
     * A wave decided on paper: the side that holds the field ({@code pAttackers}, the attackers' chance by strength) loses
     * 8-18% of the men it put in, the other 22-38%; each who falls is wounded at his side's chance.
     */
    public static Paper paper(int hostIn, int defendersIn, double pAttackers, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        boolean held = r.nextDouble() < pAttackers;
        double winLoss = 0.08 + r.nextDouble() * 0.10, loseLoss = 0.22 + r.nextDouble() * 0.16;
        Toll h = toll(hostIn, held ? winLoss : loseLoss, false, r);
        Toll d = toll(defendersIn, held ? loseLoss : winLoss, true, r);
        return new Paper(held, h, d);
    }

    private static Toll toll(int n, double share, boolean defender, SplittableRandom r) {
        int fallen = Math.min(n, (int) Math.round(n * share));
        int wounded = 0;
        for (int i = 0; i < fallen; i++) {
            if (wounded(defender, r.nextDouble())) {
                wounded++;
            }
        }
        return new Toll(fallen - wounded, wounded);
    }

    /** How many of {@code wounded} die in the night (each at {@link #SUCCUMB}). */
    public static int succumb(int wounded, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int n = 0;
        for (int i = 0; i < wounded; i++) {
            if (r.nextDouble() < SUCCUMB) {
                n++;
            }
        }
        return n;
    }

    /** "first", "second", "third". */
    public static String ordinal(int wave) {
        return switch (wave) {
            case 1 -> "first";
            case 2 -> "second";
            case 3 -> "third";
            default -> wave + "th";
        };
    }
}

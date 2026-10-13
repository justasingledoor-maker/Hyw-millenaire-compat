package dev.hywmill.politics.war;

import java.util.SplittableRandom;

/**
 * Sieges in days (post-M5; docs/siege-design.md): a siege is fought over a few days (1.9: as many as the host's size allows,
 * {@link Morale#days}), from dawn to sundown. A soldier struck down is, by chance, only wounded: carried off the field, he
 * misses the rest of the day and stands again at the next dawn (or dies of his wounds in the night). A war engine wrecked is,
 * by the same chance, repairable overnight. Who wins is decided by morale ({@link Morale}): a host whose assault is thrown
 * back regroups and comes on again; the defenders yield, or the host gives up, when they have lost their resolve. Pure.
 */
public final class SiegeWaves {
    private SiegeWaves() {}

    /** The days of the old sieges (1.9: {@link Morale#days} by the host's size). */
    public static final int WAVES = 3;
    /** Chance that a fallen soldier is only wounded: defenders fight at home (shelter, surgeons, their own beds). */
    public static final double ATTACKER_WOUND = 0.35, DEFENDER_WOUND = 0.45;
    /** Walls, towers and barracks round them: up to this much more of the fallen defenders are only wounded. */
    public static final double FORT_WOUND_MAX = 0.15, FORT_WOUND_PER_POINT = 0.0025;
    /** Chance that a wounded soldier dies in the night: with only field dressing, or with surgeons to tend him. */
    public static final double SUCCUMB = 0.1, SUCCUMB_SURGEONS = 0.04;
    public static final long DAY = 24000;
    /** A wave lasts from dawn to sundown; at least WAVE_MIN, at most WAVE_MAX ticks (no daylight cycle: half a day). */
    public static final long WAVE_MIN = 2400, WAVE_MAX = 12000;
    /** A night lasts until dawn; at least NIGHT_MIN, at most NIGHT_MAX ticks. */
    public static final long NIGHT_MIN = 600, NIGHT_MAX = 12000;

    /**
     * Reserves (fix52) of a wave fought in the world: the host holds back this share of its soldiers at its camp, the defenders
     * this share of their garrison inside (none for a side of fewer than {@link #RESERVE_MIN}). A reserve goes in, at most
     * {@link #FEED} a step, while its side's front is down to {@link #THIN} of what it was, and all of it from {@link #NOON}.
     */
    public static final double HOST_RESERVE = 0.45, DEFENDER_RESERVE = 0.35, THIN = 0.6;
    public static final int RESERVE_MIN = 8, FEED = 8;
    public static final long NOON = WAVE_MAX / 2;

    /** How many of {@code soldiers} are held back. */
    public static int reserve(int soldiers, double share) {
        return soldiers < RESERVE_MIN ? 0 : (int) Math.round(soldiers * share);
    }

    /** How many of the {@code reserve} go in now: the front ({@code inWorld} of {@code front}) has thinned, or it is past noon. */
    public static int feed(int reserve, int inWorld, int front, long sinceDawn) {
        if (reserve <= 0) {
            return 0;
        }
        return sinceDawn >= NOON || inWorld < front * THIN ? Math.min(FEED, reserve) : 0;
    }

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

    /**
     * The defenders' chance, raised by their village's fortification points (walls, towers, gates, guardhouses, barracks,
     * a fortified town hall): the wounded are dragged behind walls, and shelter is close. +0.25% a point, at most +15%.
     */
    public static double defenderWoundChance(int fortification) {
        return DEFENDER_WOUND + Math.min(FORT_WOUND_MAX, Math.max(0, fortification) * FORT_WOUND_PER_POINT);
    }

    /** Whether one who fell is only wounded (draw in [0, 1)). */
    public static boolean wounded(boolean defender, double draw) {
        return draw < woundChance(defender);
    }

    /**
     * Whether a side has surgeons for its wounded: the defenders if their village keeps barracks or a fortified town hall, or
     * is a garrison or stronghold town (a barber-surgeon); the attackers if their host comes from a garrison or stronghold
     * (a camp surgeon marches with it). Others have only field dressing.
     */
    public static boolean surgeons(boolean defender, boolean barracks, boolean town) {
        return town || (defender && barracks);
    }

    public static double succumbChance(boolean surgeons) {
        return surgeons ? SUCCUMB_SURGEONS : SUCCUMB;
    }

    /** Share of a side still in the fight or able to return to it. */
    public static double share(int aliveOrWounded, int start) {
        return start <= 0 ? 0 : Math.max(0, aliveOrWounded) / (double) start;
    }

    /** 1.8: how long a host that fell back to its lines regroups at least before it comes on again (2 min). */
    public static final long REGROUP = 2400;

    /** A host that fell back has regrouped: {@link #REGROUP} passed and it is day, or the longest night passed. */
    public static boolean regrouped(long ticksSinceFallBack, long dayTime) {
        return ticksSinceFallBack >= NIGHT_MAX || (ticksSinceFallBack >= REGROUP && !night(dayTime));
    }

    /** How many of {@code wounded} die in the night (each at {@link #SUCCUMB}). */
    public static int succumb(int wounded, long seed) {
        return succumb(wounded, SUCCUMB, seed);
    }

    public static int succumb(int wounded, double chance, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int n = 0;
        for (int i = 0; i < wounded; i++) {
            if (r.nextDouble() < chance) {
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

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
    /** Walls, towers and barracks round them: up to this much more of the fallen defenders are only wounded. */
    public static final double FORT_WOUND_MAX = 0.15, FORT_WOUND_PER_POINT = 0.0025;
    /** Chance that a wounded soldier dies in the night: with only field dressing, or with surgeons to tend him. */
    public static final double SUCCUMB = 0.1, SUCCUMB_SURGEONS = 0.04;
    /** A side down to this share of its starting strength (alive or wounded) is beaten. */
    public static final double BREAK = 0.2;
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

    /**
     * The final stand (1.6, the defenders' only since 1.8): a side whose standing men (not wounded) are down to {@link #BREAK}
     * of its starting strength while it still has wounded makes its final stand: the wounded rise and go back in at once. From
     * then until sundown only its standing men count, so it is beaten when they are down to {@code BREAK} again; if it holds to
     * sundown, the night and the next dawn go as ever (and it may make another stand the next day).
     */
    public static boolean finalStand(int standing, int wounded, int start, boolean made) {
        return !made && wounded > 0 && start > 0 && share(standing, start) <= BREAK;
    }

    /** 1.8: how long a host that fell back to its lines regroups at least before it comes on again (2 min). */
    public static final long REGROUP = 2400;

    /**
     * 1.8: the attackers make no final stand. A host whose standing men are down to {@link #BREAK} of its strength while its
     * wounded keep it above that (wounded counted as alive) falls back to its lines, tends its wounded, and comes on again.
     */
    public static boolean fallBack(int standing, int wounded, int start, boolean fellBack) {
        return !fellBack && start > 0 && share(standing, start) <= BREAK && share(standing + wounded, start) > BREAK;
    }

    /** A host that fell back has regrouped: {@link #REGROUP} passed and it is day, or the longest night passed. */
    public static boolean regrouped(long ticksSinceFallBack, long dayTime) {
        return ticksSinceFallBack >= NIGHT_MAX || (ticksSinceFallBack >= REGROUP && !night(dayTime));
    }

    /**
     * The verdict during a wave with the defenders' final stand: the host is counted alive (wounded included); the defenders by
     * their standing men only once they made their stand ({@code defStanding}); otherwise as {@link #verdict}.
     */
    public static Siege.Outcome verdict(int host, int hostStart, int defenders, int defStanding, boolean defStand,
                                        int defendersStart, int wave, boolean waveDone) {
        return verdict(host, hostStart, defStand ? defStanding : defenders, defendersStart, wave, waveDone);
    }

    /**
     * A final stand far from any witness: the {@code stand} men (standing and risen wounded) against the {@code foes} standing,
     * by strength (squared, as a siege): true if it holds to sundown, false if it breaks.
     */
    public static boolean standHolds(int stand, int foes, long seed) {
        double a = (double) stand * stand, b = (double) foes * foes;
        double p = a + b <= 0 ? 0.5 : a / (a + b);
        return new SplittableRandom(seed).nextDouble() < p;
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
        return paper(hostIn, defendersIn, pAttackers, DEFENDER_WOUND, seed);
    }

    /** {@code defenderWound}: the defenders' wound chance (raised by their fortifications). */
    public static Paper paper(int hostIn, int defendersIn, double pAttackers, double defenderWound, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        boolean held = r.nextDouble() < pAttackers;
        double winLoss = 0.08 + r.nextDouble() * 0.10, loseLoss = 0.22 + r.nextDouble() * 0.16;
        Toll h = toll(hostIn, held ? winLoss : loseLoss, ATTACKER_WOUND, r);
        Toll d = toll(defendersIn, held ? loseLoss : winLoss, defenderWound, r);
        return new Paper(held, h, d);
    }

    private static Toll toll(int n, double share, double woundChance, SplittableRandom r) {
        int fallen = Math.min(n, (int) Math.round(n * share));
        int wounded = 0;
        for (int i = 0; i < fallen; i++) {
            if (r.nextDouble() < woundChance) {
                wounded++;
            }
        }
        return new Toll(fallen - wounded, wounded);
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

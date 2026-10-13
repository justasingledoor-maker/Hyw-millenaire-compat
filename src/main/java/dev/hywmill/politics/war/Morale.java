package dev.hywmill.politics.war;

import java.util.SplittableRandom;

/**
 * Morale (1.9, docs/siege-design.md "Morale"): armies do not fight to the last man. Each side has a <b>resolve</b>: the share of
 * the men it brought that may fall (dead or wounded) before it breaks. Levies break after a tenth, regulars after a fifth,
 * veterans and household men after three tenths; walls, home, numbers and a ruler in the field stiffen it, surprise shakes it.
 * <ul>
 *   <li>A siege is fought in <b>assaults</b>: an assault the host cannot carry breaks when the men it threw in have lost its
 *       resolve; the host falls back, regroups and comes on again. Over the whole siege it gives up when it has lost
 *       {@link #campaign} of its strength. The defenders yield when they have lost their resolve, all assaults counted.</li>
 *   <li>A clash is not decided in its first moments: a side breaks only once the fight has lasted {@link #clash} (longer for
 *       bigger armies), unless it has lost twice its resolve.</li>
 *   <li>Far from any witness a day's fighting is drawn ({@link #paperDay}): a side's losses in a day are its resolve for a
 *       small army, a part of it for a big one (only so many men stand in the front), so big sieges last days.</li>
 *   <li>A side that breaks in the open is pursued: it loses {@link #pursuit} more as it flees, most to horsemen.</li>
 * </ul>
 * Pure.
 */
public final class Morale {
    private Morale() {}

    /** What men are worth in the line: how much of them may fall before they break. */
    public enum Grade {
        LEVY(0.10), MERCENARY(0.16), REGULAR(0.20), VETERAN(0.30);

        public final double resolve;

        Grade(double resolve) {
            this.resolve = resolve;
        }
    }

    public static final double MIN = 0.08, MAX = 0.36;
    /** Equipment this good makes a soldier a veteran. */
    public static final int VETERAN_GEAR = 4;

    /**
     * A soldier's grade: levies (mobilized, militia, conscripts, refugees) break first; the household, royal guard, ruler and
     * the best-equipped hold longest; hired swords hold less than regulars (they are not dying for their homes).
     */
    public static Grade grade(boolean mobilized, String extra, String mercLook, int equipmentLevel) {
        String x = extra == null ? "" : extra;
        if (x.equals("household") || x.equals("royal") || x.equals("ruler")) {
            return Grade.VETERAN;
        }
        if (mobilized || x.equals("militia") || x.equals("refugee") || x.equals("levy")) {
            return Grade.LEVY;
        }
        if (x.equals("merc") || mercLook != null && !mercLook.isEmpty()) {
            return Grade.MERCENARY;
        }
        return equipmentLevel >= VETERAN_GEAR ? Grade.VETERAN : Grade.REGULAR;
    }

    /** A side's make-up by grade. */
    public record Ranks(int levy, int mercenary, int regular, int veteran) {
        public static final Ranks NONE = new Ranks(0, 0, 0, 0);

        public int men() {
            return levy + mercenary + regular + veteran;
        }

        /** The side's resolve before circumstances: its men's, averaged. */
        public double base() {
            int n = men();
            return n <= 0 ? Grade.REGULAR.resolve
                    : (levy * Grade.LEVY.resolve + mercenary * Grade.MERCENARY.resolve + regular * Grade.REGULAR.resolve
                    + veteran * Grade.VETERAN.resolve) / n;
        }

        public Ranks plus(Grade g, int n) {
            return switch (g) {
                case LEVY -> new Ranks(levy + n, mercenary, regular, veteran);
                case MERCENARY -> new Ranks(levy, mercenary + n, regular, veteran);
                case REGULAR -> new Ranks(levy, mercenary, regular + n, veteran);
                case VETERAN -> new Ranks(levy, mercenary, regular, veteran + n);
            };
        }
    }

    /**
     * The circumstances of a side: it defends its home (and how strong its walls are: fortification points), its strength
     * against the enemy's, its ruler fights with it, it was surprised (the enemy far stronger than its scouts said).
     */
    public record Circumstances(boolean home, int fortification, double ratio, boolean ruler, boolean surprised) {
        public static final Circumstances OPEN = new Circumstances(false, 0, 1, false, false);
    }

    public static final double HOME = 0.03, WALLS_PER_POINT = 0.0015, WALLS_MAX = 0.08, RULER = 0.05, SURPRISE = 0.04, NUMBERS = 0.05;

    /** A side's resolve: its men's, stiffened by home, walls, numbers and a ruler, shaken by surprise; within [MIN, MAX]. */
    public static double resolve(Ranks ranks, Circumstances c) {
        double r = ranks.base();
        if (c.home()) {
            r += HOME + Math.min(WALLS_MAX, Math.max(0, c.fortification()) * WALLS_PER_POINT);
        }
        if (c.ratio() > 0) {
            r += Math.max(-NUMBERS, Math.min(NUMBERS, 0.04 * Math.log(c.ratio()) / Math.log(2)));
        }
        r += (c.ruler() ? RULER : 0) - (c.surprised() ? SURPRISE : 0);
        return Math.max(MIN, Math.min(MAX, r));
    }

    /** How many of {@code men} may fall before a side of this resolve breaks (at least one). */
    public static int breakAt(int men, double resolve) {
        return Math.max(1, (int) Math.ceil(men * resolve));
    }

    /** A host gives up a siege when it has lost this much of its strength, all assaults counted: 1.6 times its resolve, at most 45%. */
    public static double campaign(double resolve) {
        return Math.min(0.45, 1.6 * resolve);
    }

    /**
     * The first clash is not decided at once: it lasts at least a minute, and a second and a half more for every man in it,
     * up to five minutes (ticks). Big armies fight longer.
     */
    public static long clash(int men) {
        return Math.max(1200, Math.min(6000, 1200 + 30L * Math.max(0, men)));
    }

    /**
     * A side has broken: {@code fallen} of the {@code engaged} it brought are down, past its resolve, and the clash has lasted
     * its time ({@code ripe}), or the side has lost twice its resolve whatever the time.
     */
    public static boolean broken(int fallen, int engaged, double resolve, boolean ripe) {
        if (engaged <= 0) {
            return fallen > 0;
        }
        int at = breakAt(engaged, resolve);
        return fallen >= at && (ripe || fallen >= 2 * at);
    }

    /** How many days a host keeps the field before it gives up a siege: more for a big host (it has the men to keep at it). */
    public static int days(int hostStart) {
        return Math.max(2, Math.min(4, 1 + hostStart / 30));
    }

    /** Close to breaking: a side has lost three quarters of its resolve (a capital's ruler comes out then). */
    public static boolean wavering(int fallen, int engaged, double resolve) {
        return engaged > 0 && fallen >= 0.75 * breakAt(engaged, resolve);
    }

    /** Spirit left: 1 fresh, 0 broken (for the marshals' orders). */
    public static double spirit(int fallen, int engaged, double resolve) {
        return engaged <= 0 ? 0 : Math.max(0, 1 - fallen / (double) breakAt(engaged, resolve));
    }

    /** A broken side flees: this much more of it falls to the pursuit (4-12%, up to 10% more with the victors' horse). */
    public static double pursuit(double victorsCavalryShare, double draw) {
        return 0.04 + 0.08 * Math.max(0, Math.min(1, draw)) + 0.10 * Math.max(0, Math.min(1, victorsCavalryShare));
    }

    /** Men in the front of a fight: a side bigger than this loses, in a day, only a part of what it can bear. */
    public static final int FRONT = 60;

    /** What one day's fighting did to a side. */
    public record Toll(int dead, int wounded) {
        public int fallen() {
            return dead + wounded;
        }
    }

    /** A day of siege fought far from any witness: who held, what each side lost. */
    public record Day(boolean attackersHeld, Toll host, Toll defenders) {}

    /**
     * A day of siege drawn far from any witness. The side that loses the day ({@code pAttackers}: the attackers' chance by
     * strength) loses its resolve's worth of men, only part of it if it is big ({@code FRONT}); the winner loses less: behind
     * their walls the defenders a third of what they inflict, the attackers two thirds (walls are costly to carry). Each who
     * falls is wounded at his side's chance.
     */
    public static Day paperDay(int hostIn, int defIn, double pAttackers, double hostResolve, double defResolve, double defWound, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        boolean held = r.nextDouble() < pAttackers;
        int loserMen = held ? defIn : hostIn, winnerMen = held ? hostIn : defIn;
        double loserResolve = held ? defResolve : hostResolve;
        double front = Math.min(1, Math.sqrt(FRONT / (double) Math.max(1, loserMen)));
        int loserFallen = Math.min(loserMen, (int) Math.ceil(loserMen * loserResolve * front * (0.95 + r.nextDouble() * 0.3)));
        double exchange = held ? 0.45 + r.nextDouble() * 0.3 : 0.25 + r.nextDouble() * 0.2;
        int winnerFallen = Math.min(winnerMen, (int) Math.round(loserFallen * exchange));
        Toll host = toll(held ? winnerFallen : loserFallen, SiegeWaves.ATTACKER_WOUND, r);
        Toll def = toll(held ? loserFallen : winnerFallen, defWound, r);
        return new Day(held, host, def);
    }

    static Toll toll(int fallen, double woundChance, SplittableRandom r) {
        int wounded = 0;
        for (int i = 0; i < fallen; i++) {
            if (r.nextDouble() < woundChance) {
                wounded++;
            }
        }
        return new Toll(fallen - wounded, wounded);
    }

    /**
     * A battle in the open far from any witness ({@code pOurs}: our side's chance): the beaten side loses its resolve's worth
     * (part of it if big) and then the pursuit; the victor about half of what it inflicted before the rout.
     */
    public record Field(boolean oursWon, int ourFallen, int theirFallen) {}

    public static Field paperField(int ours, int theirs, double pOurs, double ourResolve, double theirResolve, double ourCav, double theirCav, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        boolean won = r.nextDouble() < pOurs;
        int loser = won ? theirs : ours, winner = won ? ours : theirs;
        double resolve = won ? theirResolve : ourResolve;
        double front = Math.min(1, Math.sqrt(FRONT / (double) Math.max(1, loser)));
        int beaten = (int) Math.ceil(loser * resolve * Math.max(front, 0.7));
        int fled = (int) Math.round((loser - beaten) * pursuit(won ? ourCav : theirCav, r.nextDouble()));
        int loserFallen = Math.min(loser, beaten + fled);
        int winnerFallen = Math.min(winner, (int) Math.round(beaten * (0.4 + r.nextDouble() * 0.3)));
        return won ? new Field(true, winnerFallen, loserFallen) : new Field(false, loserFallen, winnerFallen);
    }

    /**
     * The head price of a soldier killed in war, in deniers: a levy 1 denier argent (64), a regular or hired sword 2 argent, a
     * veteran 6 argent; a soldier costs about 1 denier or (4,096) on the Muster Roll, so a few dozen heads pay for one.
     */
    public static int headPrice(Grade g) {
        return switch (g) {
            case LEVY -> 64;
            case MERCENARY, REGULAR -> 128;
            case VETERAN -> 384;
        };
    }

    public static final int ENGINE_PRICE = 512, ROYAL_PRICE = 1024, RULER_PRICE = 16384;

    /**
     * Spoils by merit: of {@code pool} deniers among {@code n} helpers, three tenths shared equally (they were there), the rest
     * by each one's merit (the head prices he and his men earned) over all of theirs. With no merit at all, equal shares.
     */
    public static int spoils(int pool, int n, int merit, int totalMerit) {
        if (n <= 0 || pool <= 0) {
            return 0;
        }
        if (totalMerit <= 0) {
            return pool / n;
        }
        return (int) Math.round(pool * 0.3 / n + pool * 0.7 * Math.max(0, merit) / (double) totalMerit);
    }
}

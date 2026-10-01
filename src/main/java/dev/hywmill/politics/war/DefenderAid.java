package dev.hywmill.politics.war;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Help for the besieged (post-M5), rolled about a minute before the attackers arrive, like their mercenaries. Each is a
 * separate chance:
 * <ul>
 *   <li><b>militia</b>: the village's people take up arms: 5-15 HYW soldiers in levy kit, more for a larger population;</li>
 *   <li><b>mercenaries</b>: the village hires a free company of its own (see {@link Mercenaries});</li>
 *   <li><b>the lord's household</b>: in a garrison or a stronghold village, the lord's guard (6-8 elite soldiers in the
 *       culture's best look) is at home and joins the defence.</li>
 * </ul>
 * They are temporary: they defend this siege only and leave when it ends. Pure: every draw comes from the siege's seed.
 */
public final class DefenderAid {
    private DefenderAid() {}

    public static final double MILITIA_CHANCE = 0.5;
    public static final double MERC_CHANCE = 0.2;
    public static final double HOUSEHOLD_CHANCE = 0.3;
    public static final int MILITIA_MIN = 5, MILITIA_MAX = 15;
    public static final int HOUSEHOLD_MIN = 6, HOUSEHOLD_MAX = 8;
    public static final long SALT = 0x646566656EL;

    /** What came by chance: militia size (0: none), a mercenary company (null: none), household size (0: none). */
    public record Aid(int militia, Mercenaries.Hire mercs, int household) {
        public boolean any() {
            return militia > 0 || mercs != null || household > 0;
        }
    }

    /**
     * The rolls for one siege. {@code population}: the village's people; {@code lordly}: a garrison or stronghold village (only
     * those have a lord's household); {@code force}: every kind of help comes (an admin's test).
     */
    public static Aid roll(long seed, int population, boolean lordly, boolean force) {
        SplittableRandom r = new SplittableRandom(seed ^ SALT);
        int militia = force || r.nextDouble() < MILITIA_CHANCE ? militiaSize(population, r.nextDouble()) : 0;
        Mercenaries.Hire mercs = force ? Mercenaries.hire(seed ^ SALT) : Mercenaries.roll(seed ^ SALT, MERC_CHANCE);
        int household = lordly && (force || r.nextDouble() < HOUSEHOLD_CHANCE) ? HOUSEHOLD_MIN + r.nextInt(HOUSEHOLD_MAX - HOUSEHOLD_MIN + 1) : 0;
        return new Aid(militia, mercs, household);
    }

    /** Militia: 5 + a sixth of the population, up to 15, give or take two (drawn). */
    public static int militiaSize(int population, double draw) {
        int base = MILITIA_MIN + Math.max(0, population) / 6;
        int jitter = (int) Math.round((Math.max(0, Math.min(1, draw)) - 0.5) * 4);
        return Math.max(MILITIA_MIN, Math.min(MILITIA_MAX, base + jitter));
    }

    /** {@code n} soldiers drawn from {@code pool} (by weight: repeat a unit to weight it), stable for the seed. */
    public static List<String> draw(List<String> pool, int n, long seed) {
        List<String> out = new ArrayList<>(n);
        if (pool.isEmpty()) {
            return out;
        }
        SplittableRandom r = new SplittableRandom(seed ^ 0x6472617721L);
        for (int i = 0; i < n; i++) {
            out.add(pool.get(r.nextInt(pool.size())));
        }
        return out;
    }
}

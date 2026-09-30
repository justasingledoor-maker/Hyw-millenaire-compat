package dev.hywmill.politics.war;

import java.util.SplittableRandom;

/**
 * Siege deployment in groups (post-M5): instead of one block before the village, the host lands as several groups of 4-8
 * round it, the main group on the side facing its home and the others spread over the near half of the village's rim (a
 * host marching in does not appear behind the village). Pure: every draw comes from the siege's seed.
 */
public final class SiegeGroups {
    private SiegeGroups() {}

    public static final int MIN_GROUP = 4, MAX_GROUP = 8, TYPICAL = 6;
    /** Groups land within this angle (degrees) either side of the main landing. */
    public static final double SPREAD = 110;

    /** Group sizes for {@code n} soldiers: groups of about {@link #TYPICAL}, never more than {@link #MAX_GROUP}. */
    public static int[] sizes(int n) {
        if (n <= 0) {
            return new int[0];
        }
        int groups = Math.max(1, (int) Math.round(n / (double) TYPICAL));
        while (groups > 1 && n / groups < MIN_GROUP) {
            groups--;
        }
        while ((n + groups - 1) / groups > MAX_GROUP) {
            groups++;
        }
        int[] out = new int[groups];
        for (int i = 0; i < n; i++) {
            out[i % groups]++;
        }
        return out;
    }

    /** The group a soldier (by its index in the host) belongs to, for the sizes above. */
    public static int groupOf(int index, int[] sizes) {
        int acc = 0;
        for (int g = 0; g < sizes.length; g++) {
            acc += sizes[g];
            if (index < acc) {
                return g;
            }
        }
        return Math.max(0, sizes.length - 1);
    }

    /**
     * Bearings of the groups, in degrees from the main landing's bearing: group 0 at 0, the others alternately either side,
     * evenly spread over {@link #SPREAD} with some jitter, so no two groups land together.
     */
    public static double[] bearings(int groups, long seed) {
        double[] out = new double[groups];
        if (groups <= 1) {
            return out;
        }
        SplittableRandom r = new SplittableRandom(seed ^ 0x67726F75L);
        int perSide = (groups) / 2;
        double step = SPREAD / Math.max(1, perSide);
        for (int g = 1; g < groups; g++) {
            int k = (g + 1) / 2;
            double side = g % 2 == 1 ? 1 : -1;
            double jitter = (r.nextDouble() - 0.5) * step * 0.4;
            out[g] = side * Math.min(SPREAD, k * step - step * 0.3 + jitter);
        }
        return out;
    }

    /** Extra distance (blocks, 0-12) a group lands beyond the staging ring, so the landings are not on one circle. */
    public static int depth(int group, long seed) {
        return group == 0 ? 0 : new SplittableRandom(seed ^ (0x9E3779B97F4A7C15L * (group + 1))).nextInt(13);
    }
}

package dev.hywmill.politics.realm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The land each state holds (1.10, the map's borders): every village holds the chunks within its walls and {@link #REACH}
 * blocks beyond them; where two villages' land meets, a chunk goes to the one it is nearer, counted from their walls (a
 * greater village, with wider walls, holds more). A state's land is its villages' together. Pure.
 */
public final class Territory {
    private Territory() {}

    /** How far beyond its walls a village holds the land (blocks). */
    public static final int REACH = 128;

    /**
     * A village on the map: its centre, how far its walls reach (blocks) and its {@code owner}, a number standing for the state
     * (or part of a state) whose land it is.
     */
    public record Site(int x, int z, int radius, int owner) {}

    /** How far a village of this tier ({@code MilitaryTier} ordinal) is taken to reach: Millénaire's 90 blocks, more for a great one. */
    public static int radius(int tier) {
        return 90 + 15 * Math.max(0, tier);
    }

    public static long chunk(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int chunkX(long c) {
        return (int) (c >> 32);
    }

    public static int chunkZ(long c) {
        return (int) c;
    }

    /** Each owner's chunks ({@link #chunk} keys). */
    public static Map<Integer, Set<Long>> land(List<Site> sites) {
        return land(sites, REACH);
    }

    public static Map<Integer, Set<Long>> land(List<Site> sites, int reach) {
        Map<Long, double[]> best = new HashMap<>(); // chunk -> {score, owner}
        for (Site s : sites) {
            int r = s.radius() + reach;
            for (int cx = (s.x() - r) >> 4; cx <= (s.x() + r) >> 4; cx++) {
                for (int cz = (s.z() - r) >> 4; cz <= (s.z() + r) >> 4; cz++) {
                    double d = Math.hypot((cx << 4) + 8 - s.x(), (cz << 4) + 8 - s.z());
                    if (d > r) {
                        continue;
                    }
                    double score = d - s.radius();
                    long key = chunk(cx, cz);
                    double[] b = best.get(key);
                    if (b == null || score < b[0] || (score == b[0] && s.owner() < b[1])) {
                        best.put(key, new double[]{score, s.owner()});
                    }
                }
            }
        }
        Map<Integer, Set<Long>> out = new HashMap<>();
        best.forEach((k, b) -> out.computeIfAbsent((int) b[1], o -> new HashSet<>()).add(k));
        return out;
    }
}

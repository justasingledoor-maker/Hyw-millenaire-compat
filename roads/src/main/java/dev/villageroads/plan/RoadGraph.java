package dev.villageroads.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The road network as travellers see it: every way's points are places, joined along the way; two ways meet where their points
 * come within {@link #JOIN} blocks (a crossing, a junction, or the same village centre where village roads begin and end). A
 * journey leaves its start across country for the nearest road within reach, follows the roads, and leaves them for its end;
 * off the road every block counts {@code offroad} times. Pure.
 */
public final class RoadGraph {
    /** Ways whose points come this close meet (blocks). */
    public static final int JOIN = 10;
    /** How far a traveller goes across country to reach a road, at either end (blocks). */
    public static final int ENTER = 160;
    /** A block across country weighs this many on the road when choosing the way. */
    public static final double OFFROAD = 2.0;
    /** The roads are not taken when they make the journey longer than this, times the straight distance (and 64 blocks more). */
    public static final double DETOUR = 1.8;

    private final int[] xs, zs;
    private final List<List<int[]>> adj = new ArrayList<>(); // {to, cost * 16}
    private final Map<Long, Integer> index = new HashMap<>();
    private final Map<Long, List<Integer>> buckets = new HashMap<>();

    public RoadGraph(Collection<int[]> ways) {
        List<Integer> px = new ArrayList<>(), pz = new ArrayList<>();
        for (int[] folded : ways) {
            int[] w = dense(folded, STEP);
            int prev = -1;
            for (int i = 0; i + 1 < w.length; i += 2) {
                long key = Ways.cell(w[i], w[i + 1]);
                Integer at = index.get(key);
                if (at == null) {
                    at = px.size();
                    index.put(key, at);
                    px.add(w[i]);
                    pz.add(w[i + 1]);
                    adj.add(new ArrayList<>());
                }
                if (prev >= 0 && prev != at) {
                    link(prev, at, px, pz);
                }
                prev = at;
            }
        }
        xs = px.stream().mapToInt(Integer::intValue).toArray();
        zs = pz.stream().mapToInt(Integer::intValue).toArray();
        for (int i = 0; i < xs.length; i++) {
            buckets.computeIfAbsent(bucket(xs[i] >> 4, zs[i] >> 4), k -> new ArrayList<>()).add(i);
        }
        // junctions: points of the ways within JOIN of each other
        for (int i = 0; i < xs.length; i++) {
            for (int j : near(xs[i], zs[i], JOIN)) {
                if (j > i && adj.get(i).stream().noneMatch(e -> e[0] == j)) {
                    link(i, j, null, null);
                }
            }
        }
    }

    private void link(int a, int b, List<Integer> px, List<Integer> pz) {
        double d = px != null ? Math.hypot(px.get(a) - px.get(b), pz.get(a) - pz.get(b)) : Math.hypot(xs[a] - xs[b], zs[a] - zs[b]);
        int c = (int) Math.round(d * 16);
        adj.get(a).add(new int[]{b, c});
        adj.get(b).add(new int[]{a, c});
    }

    private static long bucket(int bx, int bz) {
        return Ways.cell(bx, bz);
    }

    /** The places within {@code r} of (x, z). */
    List<Integer> near(int x, int z, int r) {
        List<Integer> out = new ArrayList<>();
        for (int bx = (x - r) >> 4; bx <= (x + r) >> 4; bx++) {
            for (int bz = (z - r) >> 4; bz <= (z + r) >> 4; bz++) {
                for (int i : buckets.getOrDefault(bucket(bx, bz), List.of())) {
                    if (Math.hypot(xs[i] - x, zs[i] - z) <= r) {
                        out.add(i);
                    }
                }
            }
        }
        return out;
    }

    public int places() {
        return xs.length;
    }

    /** The journey from (fx, fz) to (tx, tz) by the roads, as points from the start to the end; null when no road serves it. */
    public int[] route(int fx, int fz, int tx, int tz) {
        return route(fx, fz, tx, tz, ENTER, OFFROAD, DETOUR);
    }

    public int[] route(int fx, int fz, int tx, int tz, int enter, double offroad, double detour) {
        double straight = Math.hypot(tx - fx, tz - fz);
        if (xs.length == 0 || straight < 32) {
            return null;
        }
        List<Integer> starts = near(fx, fz, enter), ends = near(tx, tz, enter);
        if (starts.isEmpty() || ends.isEmpty()) {
            return null;
        }
        int n = xs.length;
        long[] dist = new long[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Long.MAX_VALUE);
        Arrays.fill(prev, -2);
        PriorityQueue<long[]> q = new PriorityQueue<>((p, r) -> Long.compare(p[0], r[0]));
        for (int i : starts) {
            long c = Math.round(Math.hypot(xs[i] - fx, zs[i] - fz) * offroad * 16);
            if (c < dist[i]) {
                dist[i] = c;
                prev[i] = -1;
                q.add(new long[]{c, i});
            }
        }
        double[] leave = new double[n];
        Arrays.fill(leave, -1);
        for (int i : ends) {
            leave[i] = Math.hypot(xs[i] - tx, zs[i] - tz) * offroad * 16;
        }
        long best = Long.MAX_VALUE;
        int last = -1;
        while (!q.isEmpty()) {
            long[] top = q.poll();
            int i = (int) top[1];
            if (top[0] > dist[i] || top[0] >= best) {
                continue;
            }
            if (leave[i] >= 0 && dist[i] + (long) leave[i] < best) {
                best = dist[i] + (long) leave[i];
                last = i;
            }
            for (int[] e : adj.get(i)) {
                long c = dist[i] + e[1];
                if (c < dist[e[0]]) {
                    dist[e[0]] = c;
                    prev[e[0]] = i;
                    q.add(new long[]{c, e[0]});
                }
            }
        }
        if (last < 0) {
            return null;
        }
        List<Integer> path = new ArrayList<>();
        for (int i = last; i >= 0; i = prev[i]) {
            path.add(i);
        }
        double onRoad = 0;
        for (int p = 1; p < path.size(); p++) {
            onRoad += Math.hypot(xs[path.get(p)] - xs[path.get(p - 1)], zs[path.get(p)] - zs[path.get(p - 1)]);
        }
        if (onRoad < 48 || onRoad < straight / 4) {
            return null; // the roads hardly serve it: across country
        }
        int[] out = new int[2 * path.size() + 4];
        out[0] = fx;
        out[1] = fz;
        int k = 2;
        for (int p = path.size() - 1; p >= 0; p--) {
            out[k++] = xs[path.get(p)];
            out[k++] = zs[path.get(p)];
        }
        out[k++] = tx;
        out[k] = tz;
        out = Ways.simplify(dedupe(out), Ways.TOLERANCE);
        return Ways.length(out) > straight * detour + 64 ? null : out;
    }

    /** Points along a way at most this far apart (so a road crossing a long straight stretch meets it). */
    static final int STEP = 8;

    /** The way with points put in along its long stretches, at most {@code step} apart. */
    static int[] dense(int[] xz, int step) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i + 1 < xz.length; i += 2) {
            if (i >= 2) {
                int ax = xz[i - 2], az = xz[i - 1], bx = xz[i], bz = xz[i + 1];
                int parts = (int) Math.ceil(Math.hypot(bx - ax, bz - az) / step);
                for (int k = 1; k < parts; k++) {
                    out.add((int) Math.round(ax + (bx - ax) * k / (double) parts));
                    out.add((int) Math.round(az + (bz - az) * k / (double) parts));
                }
            }
            out.add(xz[i]);
            out.add(xz[i + 1]);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    static int[] dedupe(int[] xz) {
        int[] out = new int[xz.length];
        int k = 0;
        for (int i = 0; i + 1 < xz.length; i += 2) {
            if (k >= 2 && out[k - 2] == xz[i] && out[k - 1] == xz[i + 1]) {
                continue;
            }
            out[k++] = xz[i];
            out[k++] = xz[i + 1];
        }
        return Arrays.copyOf(out, k);
    }
}

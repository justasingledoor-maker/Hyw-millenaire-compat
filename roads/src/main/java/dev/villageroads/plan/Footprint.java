package dev.villageroads.plan;

import dev.villageroads.plan.RoadPlanner.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What is built where along a road. Over land: the road's crown in the middle, its edges, then a shoulder at the road's height
 * and a slope a block lower, both grassed like the land around, so the road sits in the landscape. Over water: the deck, a
 * rail each side, pillars every five blocks down to the bed and a lantern at the ends and every ten. Each cell takes the
 * height of the nearest point of the way. Cells come in the order of the way, so a road is built from one end to the other. Pure.
 */
public final class Footprint {
    private Footprint() {}

    public enum Part { CORE, EDGE, SHOULDER, SLOPE, DECK, RAIL, PILLAR, LAMP }

    /**
     * A column of the road: where, at what height, what it is, the index of the way's node it belongs to, and whether it is a
     * half step (a slab over the surface, where the road climbs a block between two nodes).
     */
    public record Cell(int x, int z, int y, Part part, int at, boolean half) {
        public Cell(int x, int z, int y, Part part, int at) {
            this(x, z, y, part, at, false);
        }
    }

    /** Pillars stand every this many bridge cells; lanterns every {@code 2 *} that, and at both ends. */
    static final int PILLARS = 5;

    /**
     * Each column near the way takes its part from its distance to the way's line (not to its nearest cell, so a diagonal
     * stretch is as even as a straight one), and its height from along the line: where the road climbs a block from one node
     * to the next, the columns between are half steps.
     */
    public static List<Cell> stamp(List<Node> nodes, int[] ys, int width) {
        double half = (width - 1) / 2.0;
        int reach = (int) Math.ceil(half + 2.5), n = nodes.size();
        Map<Long, double[]> nearest = new HashMap<>(); // cell -> {distance squared, segment start, t}
        for (int i = 0; i < n; i++) {
            Node a = nodes.get(i), b = nodes.get(Math.min(i + 1, n - 1));
            double dx = b.x() - a.x(), dz = b.z() - a.z(), l2 = dx * dx + dz * dz;
            for (int z = Math.min(a.z(), b.z()) - reach; z <= Math.max(a.z(), b.z()) + reach; z++) {
                for (int x = Math.min(a.x(), b.x()) - reach; x <= Math.max(a.x(), b.x()) + reach; x++) {
                    double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.x()) * dx + (z - a.z()) * dz) / l2));
                    double px = a.x() + t * dx - x, pz = a.z() + t * dz - z, d2 = px * px + pz * pz;
                    long key = key(x, z);
                    double[] best = nearest.get(key);
                    if (best == null) {
                        nearest.put(key, new double[]{d2, i, t});
                    } else if (d2 < best[0] - 1e-9) {
                        best[0] = d2;
                        best[1] = i;
                        best[2] = t;
                    }
                }
            }
        }
        List<Cell> out = new ArrayList<>(nearest.size());
        for (Map.Entry<Long, double[]> e : nearest.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
            double[] b = e.getValue();
            double d = Math.sqrt(b[0]), t = b[2];
            int i = (int) b[1], j = Math.min(i + 1, n - 1), at = t < 0.5 ? i : j;
            Part part;
            int y = ys[at];
            boolean step = false;
            if (nodes.get(at).bridge()) {
                part = d <= half + 0.5 ? Part.DECK : d <= half + 1.5 ? Part.RAIL : null;
            } else {
                part = d <= half - 0.5 ? Part.CORE : d <= half + 0.5 ? Part.EDGE : d <= half + 1.5 ? Part.SHOULDER : d <= half + 2.5 ? Part.SLOPE : null;
                if (!nodes.get(i).bridge() && !nodes.get(j).bridge()) {
                    double h = ys[i] + t * (ys[j] - ys[i]);
                    int base = (int) Math.floor(h);
                    double frac = h - base;
                    y = frac >= 0.75 ? base + 1 : base;
                    step = frac >= 0.25 && frac < 0.75;
                }
            }
            if (part != null) {
                out.add(new Cell(x, z, y, part, at, step && (part == Part.CORE || part == Part.EDGE)));
            }
        }
        pillars(nodes, ys, half, out);
        out.sort(Comparator.comparingInt(Cell::at).thenComparing(Cell::part));
        return out;
    }

    /** Pillars and lanterns of each bridge, on its rail lines either side. */
    static void pillars(List<Node> nodes, int[] ys, double half, List<Cell> out) {
        int n = nodes.size();
        for (int s = 0; s < n; s++) {
            if (!nodes.get(s).bridge()) {
                continue;
            }
            int e = s;
            while (e + 1 < n && nodes.get(e + 1).bridge()) {
                e++;
            }
            for (int i = s; i <= e; i++) {
                boolean end = i == s || i == e;
                if ((i - s) % PILLARS != 0 && !end) {
                    continue;
                }
                Node a = nodes.get(Math.max(0, i - 1)), b = nodes.get(Math.min(n - 1, i + 1));
                double dx = b.x() - a.x(), dz = b.z() - a.z(), len = Math.hypot(dx, dz);
                if (len == 0) {
                    dx = 1;
                    len = 1;
                }
                double off = half + 1;
                int px = (int) Math.round(-dz / len * off), pz = (int) Math.round(dx / len * off);
                boolean lamp = end || (i - s) % (2 * PILLARS) == 0;
                Node c = nodes.get(i);
                for (int side = -1; side <= 1; side += 2) {
                    out.add(new Cell(c.x() + side * px, c.z() + side * pz, ys[i], Part.PILLAR, i));
                    if (lamp) {
                        out.add(new Cell(c.x() + side * px, c.z() + side * pz, ys[i], Part.LAMP, i));
                    }
                }
            }
            s = e;
        }
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}

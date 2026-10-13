package dev.villageroads.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * A road's way: its centre line as points {x0, z0, x1, z1, ...} from one end to the other, what travellers follow. Kept short
 * (the straight stretches folded), and traced again from a road's columns for roads built before ways were kept. Pure.
 */
public final class Ways {
    private Ways() {}

    /** How far (blocks) a folded way may stray from the road's centre. */
    public static final double TOLERANCE = 1.5;

    public static long cell(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** The way through these points with the straight stretches folded (Douglas-Peucker); the ends are always kept. */
    public static int[] simplify(int[] xz, double tolerance) {
        int n = xz.length / 2;
        if (n <= 2) {
            return xz.clone();
        }
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] s = stack.pop();
            int a = s[0], b = s[1];
            double best = -1;
            int at = -1;
            for (int i = a + 1; i < b; i++) {
                double d = offLine(xz[2 * i], xz[2 * i + 1], xz[2 * a], xz[2 * a + 1], xz[2 * b], xz[2 * b + 1]);
                if (d > best) {
                    best = d;
                    at = i;
                }
            }
            if (at >= 0 && best > tolerance) {
                keep[at] = true;
                stack.push(new int[]{a, at});
                stack.push(new int[]{at, b});
            }
        }
        int k = 0;
        for (boolean b : keep) {
            k += b ? 1 : 0;
        }
        int[] out = new int[2 * k];
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                out[j++] = xz[2 * i];
                out[j++] = xz[2 * i + 1];
            }
        }
        return out;
    }

    static double offLine(double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / l2));
        return Math.hypot(x - (ax + t * dx), z - (az + t * dz));
    }

    /** The length of a way (blocks). */
    public static double length(int[] xz) {
        double l = 0;
        for (int i = 2; i + 1 < xz.length; i += 2) {
            l += Math.hypot(xz[i] - xz[i - 2], xz[i + 1] - xz[i - 1]);
        }
        return l;
    }

    /**
     * The way of a road known only by its columns ({@code road}): the shortest walk over road columns (each to its eight
     * neighbours) from one within {@code reachA} of the first end to one within {@code reachB} of the second, inside the box
     * [x0, x1] x [z0, z1] and at most {@code budget} columns looked at. The two ends are put at the way's ends. Null: none.
     */
    public static int[] trace(LongPredicate road, int ax, int az, int reachA, int bx, int bz, int reachB, int x0, int z0, int x1, int z1, int budget) {
        int w = x1 - x0 + 1, h = z1 - z0 + 1;
        if (w <= 0 || h <= 0 || (long) w * h > 64_000_000L) {
            return null;
        }
        int[] from = new int[w * h];
        java.util.Arrays.fill(from, -2);
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int z = Math.max(z0, az - reachA); z <= Math.min(z1, az + reachA); z++) {
            for (int x = Math.max(x0, ax - reachA); x <= Math.min(x1, ax + reachA); x++) {
                if (Math.hypot(x - ax, z - az) <= reachA && road.test(cell(x, z))) {
                    int i = (z - z0) * w + (x - x0);
                    from[i] = -1;
                    q.add(i);
                }
            }
        }
        int seen = 0, goal = -1;
        while (!q.isEmpty() && seen++ < budget) {
            int i = q.poll();
            int x = x0 + i % w, z = z0 + i / w;
            if (Math.hypot(x - bx, z - bz) <= reachB) {
                goal = i;
                break;
            }
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, nz = z + dz;
                    if ((dx | dz) == 0 || nx < x0 || nx > x1 || nz < z0 || nz > z1) {
                        continue;
                    }
                    int j = (nz - z0) * w + (nx - x0);
                    if (from[j] == -2 && road.test(cell(nx, nz))) {
                        from[j] = i;
                        q.add(j);
                    }
                }
            }
        }
        if (goal < 0) {
            return null;
        }
        List<Integer> path = new ArrayList<>();
        for (int i = goal; i >= 0; i = from[i]) {
            path.add(i);
        }
        int[] xz = new int[2 * path.size() + 4];
        xz[0] = ax;
        xz[1] = az;
        int k = 2;
        for (int p = path.size() - 1; p >= 0; p--) {
            int i = path.get(p);
            xz[k++] = x0 + i % w;
            xz[k++] = z0 + i / w;
        }
        xz[k++] = bx;
        xz[k] = bz;
        return xz;
    }
}

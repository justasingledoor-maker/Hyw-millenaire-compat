package dev.villageroads.plan;

import dev.villageroads.plan.RoadPlanner.Node;

import java.util.Arrays;
import java.util.List;

/**
 * A road's heights along its way. Over land it follows the ground, smoothed (small dips are filled and bumps cut); it never
 * rises or falls more than a block a step, so walkers and riders keep going. A bridge's deck stands at least two blocks above
 * the water (a boat passes under it) and level with the lower bank, the road ramping up to it. Pure.
 */
public final class Profile {
    private Profile() {}

    /** Ground heights are averaged over this many cells each side (within one stretch of land). */
    static final int WINDOW = 3;
    /** A deck stands this far above the water's top block. */
    public static final int CLEARANCE = 2;

    /** The road's surface height at each node of a planned road. */
    public static int[] heights(List<Node> nodes) {
        int n = nodes.size();
        int[] y = new int[n], floor = new int[n];
        Arrays.fill(floor, Integer.MIN_VALUE);
        for (int i = 0; i < n; i++) {
            if (nodes.get(i).bridge()) {
                continue;
            }
            long sum = 0;
            int count = 0;
            for (int j = i; j >= Math.max(0, i - WINDOW) && !nodes.get(j).bridge(); j--) {
                sum += nodes.get(j).y();
                count++;
            }
            for (int j = i + 1; j <= Math.min(n - 1, i + WINDOW) && !nodes.get(j).bridge(); j++) {
                sum += nodes.get(j).y();
                count++;
            }
            y[i] = (int) Math.round(sum / (double) count);
        }
        for (int s = 0; s < n; s++) {
            if (!nodes.get(s).bridge()) {
                continue;
            }
            int e = s, water = Integer.MIN_VALUE;
            while (e + 1 < n && nodes.get(e + 1).bridge()) {
                e++;
            }
            for (int k = s; k <= e; k++) {
                water = Math.max(water, nodes.get(k).y());
            }
            int low = water + CLEARANCE;
            int before = s > 0 ? y[s - 1] : low, after = e < n - 1 ? y[e + 1] : low;
            int deck = Math.max(low, Math.min(before, after));
            for (int k = s; k <= e; k++) {
                y[k] = deck;
                floor[k] = deck;
            }
            s = e;
        }
        limit(y, floor);
        return y;
    }

    /**
     * A straight bridge's deck (the Surveyor's Staff): from one end's height to the other's evenly, but at least two above water
     * and one above the ground under it, never more than a block a step.
     */
    public static int[] deck(List<Node> nodes, boolean[] water, int yStart, int yEnd) {
        int n = nodes.size();
        int[] y = new int[n], floor = new int[n];
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0 : i / (double) (n - 1);
            floor[i] = nodes.get(i).y() + (water[i] ? CLEARANCE : 1);
            if (i == 0 || i == n - 1) {
                floor[i] = Math.min(floor[i], i == 0 ? yStart : yEnd);
            }
            y[i] = Math.max(floor[i], (int) Math.round(yStart + (yEnd - yStart) * t));
        }
        limit(y, floor);
        return y;
    }

    /** No more than a block up or down a step; a cell with a floor never goes under it (the road ramps up to it instead). */
    static void limit(int[] y, int[] floor) {
        int n = y.length;
        for (int pass = 0; pass < 4 * n + 4; pass++) {
            boolean changed = false;
            for (int i = 1; i < n; i++) {
                changed |= clamp(y, floor, i, y[i - 1]);
            }
            for (int i = n - 2; i >= 0; i--) {
                changed |= clamp(y, floor, i, y[i + 1]);
            }
            if (!changed) {
                return;
            }
        }
    }

    private static boolean clamp(int[] y, int[] floor, int i, int neighbour) {
        int v = Math.max(floor[i], Math.max(neighbour - 1, Math.min(neighbour + 1, y[i])));
        if (v == y[i]) {
            return false;
        }
        y[i] = v;
        return true;
    }
}

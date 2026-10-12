package dev.villageroads.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Finds a road's way over a surveyed {@link Grid}: an A* search from any start cell to any goal cell. A step costs its length,
 * more uphill or downhill (a step of more than {@link Settings#maxStep} blocks is a cliff), through trees or near a shore (the
 * road keeps a margin from water, so its edges never fill a pond's rim),
 * less along an old road (it is followed where it goes the right way); buildings and their surroundings are closed, so the road
 * snakes round them. Water is never walked: from a shore the road may jump straight across it to the far bank, a bridge,
 * costing {@link Settings#bridgeFixed} plus {@link Settings#bridgePerBlock} a block. So a lake is crossed by a bridge exactly
 * when going round it costs more. The found way is then straightened where a straight line costs about as much. Pure.
 */
public final class RoadPlanner {
    private RoadPlanner() {}

    /**
     * {@code maxSpan}: the longest bridge (blocks of water); {@code maxStep}: the steepest step the road takes; {@code budget}: the
     * most cells searched before giving up.
     */
    public record Settings(int maxSpan, double bridgePerBlock, double bridgeFixed, int maxStep, int budget) {
        public static final Settings DEFAULT = new Settings(48, 1.3, 6, 3, 3_000_000);
    }

    /** One cell of the road: where, its height (the ground's; for a bridge, the water's top) and whether it is bridge. */
    public record Node(int x, int z, int y, boolean bridge) {}

    /** A road's way: its cells in order, how many bridges it has, and what it cost. */
    public record Route(List<Node> nodes, int bridges, double cost) {}

    /**
     * An estimate of the cost left from a cell to the goal (A*'s heuristic). Scaled by {@link #WEIGHT}: an estimate of the full
     * distance would make the search too sure of the straight line to find the cheaper way along an old road.
     */
    public interface Estimate {
        double to(int x, int z);
    }

    static final double INF = Double.POSITIVE_INFINITY, SQRT2 = Math.sqrt(2);
    /** The cost added per block of a step that climbs 0, 1, 2 or 3 blocks. */
    static final double[] SLOPE = {0, 1.0, 4.0, 12.0};
    static final double ROAD = 0.35, TREE = 1.5, SHORE = 2.0, NEAR_WATER = 1.0, INTERIOR = 1.5;
    /** What a block of distance left is reckoned to cost in the estimate (an old road costs {@link #ROAD} a block). */
    public static final double WEIGHT = 0.5;
    /** How far ahead a bend is straightened (cells). */
    static final int PULL = 24;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1}, DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    /** The way from the cheapest start to the nearest goal, or null if there is none in the grid. */
    public static Route plan(Grid g, int[] starts, double[] startCost, BitSet goal, Estimate est, Settings s) {
        int n = g.size();
        double[] best = new double[n];
        Arrays.fill(best, INF);
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        BitSet jumped = new BitSet(n), closed = new BitSet(n);
        Heap open = new Heap();
        for (int k = 0; k < starts.length; k++) {
            int i = starts[k];
            if (startCost[k] < best[i]) {
                best[i] = startCost[k];
                open.push(i, best[i] + est.to(g.x(i), g.z(i)));
            }
        }
        int found = -1, searched = 0;
        while (!open.isEmpty()) {
            int c = open.pop();
            if (closed.get(c)) {
                continue;
            }
            closed.set(c);
            if (goal.get(c)) {
                found = c;
                break;
            }
            if (++searched > s.budget()) {
                break;
            }
            int cx = g.x(c), cz = g.z(c);
            for (int d = 0; d < 8; d++) {
                int nx = cx + DX[d], nz = cz + DZ[d];
                if (!g.in(nx, nz)) {
                    continue;
                }
                int ni = g.idx(nx, nz);
                if (closed.get(ni)) {
                    continue;
                }
                boolean diagonal = DX[d] != 0 && DZ[d] != 0;
                if (diagonal && (!walkable(g, g.idx(nx, cz), goal) || !walkable(g, g.idx(cx, nz), goal))) {
                    continue; // never squeezes between two corners
                }
                double cost = step(g, c, ni, diagonal ? SQRT2 : 1, goal, s);
                if (cost < INF && best[c] + cost < best[ni]) {
                    best[ni] = best[c] + cost;
                    parent[ni] = c;
                    jumped.clear(ni);
                    open.push(ni, best[ni] + est.to(nx, nz));
                }
            }
            if (g.has(c, Grid.SHORE)) {
                for (int d = 0; d < 8; d++) {
                    int land = across(g, c, DX[d], DZ[d], goal, s);
                    if (land < 0 || closed.get(land)) {
                        continue;
                    }
                    int span = Math.max(Math.abs(g.x(land) - cx), Math.abs(g.z(land) - cz));
                    double cost = s.bridgeFixed() + span * (DX[d] != 0 && DZ[d] != 0 ? SQRT2 : 1) * s.bridgePerBlock();
                    if (best[c] + cost < best[land]) {
                        best[land] = best[c] + cost;
                        parent[land] = c;
                        jumped.set(land);
                        open.push(land, best[land] + est.to(g.x(land), g.z(land)));
                    }
                }
            }
        }
        if (found < 0) {
            return null;
        }
        List<Integer> back = new ArrayList<>();
        for (int i = found; i >= 0; i = parent[i]) {
            back.add(i);
        }
        List<Node> nodes = new ArrayList<>();
        int bridges = 0;
        for (int k = back.size() - 1; k >= 0; k--) {
            int i = back.get(k);
            if (jumped.get(i) && !nodes.isEmpty()) {
                bridges++;
                Node from = nodes.get(nodes.size() - 1);
                int sx = Integer.signum(g.x(i) - from.x()), sz = Integer.signum(g.z(i) - from.z());
                for (int x = from.x() + sx, z = from.z() + sz; x != g.x(i) || z != g.z(i); x += sx, z += sz) {
                    nodes.add(new Node(x, z, g.y(g.idx(x, z)), true));
                }
            }
            nodes.add(new Node(g.x(i), g.z(i), g.y(i), false));
        }
        return new Route(smooth(g, nodes, goal, s), bridges, best[found]);
    }

    /** A road may step onto it: passable, or a goal cell on land (the end may lie by a building, as a village's path does). */
    public static boolean walkable(Grid g, int i, BitSet goal) {
        byte k = g.kind(i);
        return g.passable(i) || goal.get(i) && (k == Grid.LAND || k == Grid.ROAD);
    }

    /** The cost of one step to a neighbouring cell ({@code len}: 1 or the diagonal's), or INF if the road cannot go there. */
    static double step(Grid g, int from, int to, double len, BitSet goal, Settings s) {
        if (!walkable(g, to, goal)) {
            return INF;
        }
        int dy = Math.abs(g.y(to) - g.y(from));
        if (dy > s.maxStep()) {
            return INF;
        }
        boolean road = g.kind(to) == Grid.ROAD;
        double c = len * (road ? ROAD : 1) + len * SLOPE[Math.min(dy, SLOPE.length - 1)];
        if (!road) {
            c += (g.has(to, Grid.TREE) ? TREE * len : 0) + (g.has(to, Grid.SHORE) ? SHORE * len : g.has(to, Grid.NEAR_WATER) ? NEAR_WATER * len : 0);
        }
        return g.has(to, Grid.INTERIOR) ? c * INTERIOR : c;
    }

    /** From a shore cell, straight across water in one direction to the far bank: its cell, or -1 if no bridge there. */
    static int across(Grid g, int c, int dx, int dz, BitSet goal, Settings s) {
        int cx = g.x(c), cz = g.z(c);
        for (int k = 1; k <= s.maxSpan() + 1; k++) {
            int x = cx + dx * k, z = cz + dz * k;
            if (!g.in(x, z)) {
                return -1;
            }
            int i = g.idx(x, z);
            if (g.kind(i) == Grid.WATER) {
                if (g.has(i, Grid.NEAR_STRUCTURE)) {
                    return -1; // a quay, a dock, a mill race: not over it
                }
                continue;
            }
            return k >= 2 && walkable(g, i, goal) && Math.abs(g.y(i) - g.y(c)) <= 8 ? i : -1;
        }
        return -1;
    }

    /** Straightens the way over land: each stretch is replaced by a straight line where one costs no more than 10% extra. */
    static List<Node> smooth(Grid g, List<Node> path, BitSet goal, Settings s) {
        int n = path.size();
        double[] cum = new double[n];
        int[] nextBridge = new int[n + 1];
        nextBridge[n] = n;
        for (int i = n - 1; i >= 0; i--) {
            nextBridge[i] = path.get(i).bridge() ? i : nextBridge[i + 1];
        }
        for (int i = 1; i < n; i++) {
            Node a = path.get(i - 1), b = path.get(i);
            double c = a.bridge() || b.bridge() ? 1 : step(g, g.idx(a.x(), a.z()), g.idx(b.x(), b.z()), a.x() != b.x() && a.z() != b.z() ? SQRT2 : 1, goal, s);
            cum[i] = cum[i - 1] + (c == INF ? 1 : c);
        }
        List<Node> out = new ArrayList<>();
        out.add(path.get(0));
        int i = 0;
        while (i < n - 1) {
            int next = i + 1;
            if (!path.get(i).bridge() && !path.get(i + 1).bridge()) {
                int far = Math.min(Math.min(n - 1, i + PULL), nextBridge[i + 1] - 1);
                for (int j = far; j >= i + 2; j--) {
                    List<Node> line = line(g, path.get(i), path.get(j));
                    double cost = lineCost(g, line, goal, s);
                    if (cost <= 1.1 * (cum[j] - cum[i]) + 0.5) {
                        out.addAll(line.subList(1, line.size()));
                        next = j;
                        break;
                    }
                }
            }
            if (next == i + 1) {
                out.add(path.get(i + 1));
            }
            i = next;
        }
        return out;
    }

    /** The cells of a straight line between two cells (8-connected), with their heights. */
    static List<Node> line(Grid g, Node a, Node b) {
        List<Node> out = new ArrayList<>();
        int x = a.x(), z = a.z(), dx = Math.abs(b.x() - x), dz = -Math.abs(b.z() - z);
        int sx = Integer.signum(b.x() - x), sz = Integer.signum(b.z() - z), err = dx + dz;
        while (true) {
            out.add(new Node(x, z, g.in(x, z) ? g.y(g.idx(x, z)) : a.y(), false));
            if (x == b.x() && z == b.z()) {
                return out;
            }
            int e2 = 2 * err;
            if (e2 >= dz) {
                err += dz;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                z += sz;
            }
        }
    }

    static double lineCost(Grid g, List<Node> line, BitSet goal, Settings s) {
        double c = 0;
        for (int k = 1; k < line.size(); k++) {
            Node a = line.get(k - 1), b = line.get(k);
            if (!g.in(b.x(), b.z()) || k < line.size() - 1 && g.has(g.idx(b.x(), b.z()), Grid.SHORE)) {
                return INF; // a straightened stretch never cuts along a shore
            }
            boolean diagonal = a.x() != b.x() && a.z() != b.z();
            if (diagonal && (!walkable(g, g.idx(b.x(), a.z()), goal) || !walkable(g, g.idx(a.x(), b.z()), goal))) {
                return INF;
            }
            double st = step(g, g.idx(a.x(), a.z()), g.idx(b.x(), b.z()), diagonal ? SQRT2 : 1, goal, s);
            if (st == INF) {
                return INF;
            }
            c += st;
        }
        return c;
    }

    /** A straight bridge from one end to the other, every cell of it bridge (the Surveyor's Staff's bridge). */
    public static Route straight(Grid g, int ax, int az, int bx, int bz) {
        List<Node> line = line(g, new Node(ax, az, 0, false), new Node(bx, bz, 0, false));
        List<Node> nodes = new ArrayList<>(line.size());
        for (Node c : line) {
            nodes.add(new Node(c.x(), c.z(), c.y(), true));
        }
        return new Route(nodes, 1, line.size());
    }

    /** A binary min-heap of cells by priority (duplicates allowed; stale entries are skipped by the closed set). */
    static final class Heap {
        private int[] cell = new int[1024];
        private double[] key = new double[1024];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        void push(int c, double k) {
            if (size == cell.length) {
                cell = Arrays.copyOf(cell, size * 2);
                key = Arrays.copyOf(key, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (key[p] <= k) {
                    break;
                }
                cell[i] = cell[p];
                key[i] = key[p];
                i = p;
            }
            cell[i] = c;
            key[i] = k;
        }

        int pop() {
            int top = cell[0];
            int c = cell[--size];
            double k = key[size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= size) {
                    break;
                }
                int m = l + 1 < size && key[l + 1] < key[l] ? l + 1 : l;
                if (key[m] >= k) {
                    break;
                }
                cell[i] = cell[m];
                key[i] = key[m];
                i = m;
            }
            cell[i] = c;
            key[i] = k;
            return top;
        }
    }
}

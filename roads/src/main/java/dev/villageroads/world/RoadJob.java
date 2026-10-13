package dev.villageroads.world;

import dev.villageroads.plan.Footprint;
import dev.villageroads.plan.Footprint.Cell;
import dev.villageroads.plan.Grid;
import dev.villageroads.plan.Profile;
import dev.villageroads.plan.RoadPlanner;
import dev.villageroads.plan.RoadPlanner.Node;
import dev.villageroads.plan.RoadPlanner.Route;
import dev.villageroads.world.Styles.BridgeStyle;
import dev.villageroads.world.Styles.RoadStyle;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * One road (or bridge) being made: the land along the way is surveyed chunk by chunk (loaded, or generated, as needed), the
 * way is found and the road laid out on a worker thread, then it is built from one end to the other, a few columns a tick.
 */
public final class RoadJob {
    public enum Phase { SURVEY, PLAN, BUILD, DONE, FAILED }

    public enum Mode { ROAD, BRIDGE }

    /** What to build: a road (finding its way, bridging where it must) or a straight bridge; its surface, bridges and width. */
    public record Spec(Mode mode, RoadStyle road, BridgeStyle bridge, int width) {}

    /** Where: between two points (the staff), or between two villages' edges (the network). */
    public sealed interface Ends permits Points, Villages {}

    public record Points(BlockPos a, BlockPos b) implements Ends {}

    /** {@code others}: villages nearby the road must keep out of. */
    public record Villages(VillageInfo a, VillageInfo b, List<VillageInfo> others) implements Ends {}

    /** The survey's settings: how far either side of the line the road may wander; the planner's. */
    public record Survey(int corridor, RoadPlanner.Settings planner) {}

    /** {@code from}, {@code to}, {@code middle}: the road's ends and middle; {@code bridge}: the middle of its longest bridge, or null. */
    record Planned(List<Cell> cells, int length, int bridges, int longest, int[] span, LongOpenHashSet footprint, BlockPos from, BlockPos to,
                   BlockPos middle, BlockPos bridge, int[] way) {}

    /** Chunks asked for at once while surveying; sampled per tick; how long a chunk may take to load before the job gives up. */
    static final int INFLIGHT = 24, SAMPLE_PER_TICK = 6, TIMEOUT = 2400;
    /** A road starts at a village's edge, or (cheaper) on one of its paths near the edge. */
    static final double EDGE_START = 20;
    static final int PATH_BAND = 24;
    /** Chunks ahead of the builder asked for early. */
    static final int AHEAD = 12;

    final int id;
    final UUID owner;
    final ServerLevel level;
    final Ends ends;
    final Spec spec;
    final Survey survey;
    final RoadsData data;
    private final ChunkGate gate;
    /** What the road replaced, to take it up again (the staff's roads), or null. */
    final Map<BlockPos, BlockState> undo;

    Phase phase = Phase.SURVEY;
    String note = "";
    private int ax, az, bx, bz, half;
    private Grid grid;
    private List<Long> chunks;
    private int next, sampled;
    private final List<Long> inflight = new ArrayList<>();
    private long progressAt = -1;
    private CompletableFuture<Planned> future;
    private Planned planned;
    private RoadBuilder builder;
    private int cursor;

    RoadJob(int id, UUID owner, ServerLevel level, Ends ends, Spec spec, Survey survey) {
        this.id = id;
        this.owner = owner;
        this.level = level;
        this.ends = ends;
        this.spec = spec;
        this.survey = survey;
        this.data = RoadsData.get(level);
        this.gate = new ChunkGate(level);
        this.undo = owner != null ? new LinkedHashMap<>() : null;
        lay();
    }

    /** The corridor: the line between the two ends, and the chunks within reach of it, from the first end to the second. */
    private void lay() {
        switch (ends) {
            case Points pts -> {
                ax = pts.a().getX();
                az = pts.a().getZ();
                bx = pts.b().getX();
                bz = pts.b().getZ();
                half = spec.mode() == Mode.BRIDGE ? spec.width() + 4 : survey.corridor();
            }
            case Villages v -> {
                ax = v.a().center().getX();
                az = v.a().center().getZ();
                bx = v.b().center().getX();
                bz = v.b().center().getZ();
                half = survey.corridor();
            }
        }
        int x0 = Math.min(ax, bx) - half, z0 = Math.min(az, bz) - half;
        grid = new Grid(x0, z0, Math.abs(ax - bx) + 2 * half + 1, Math.abs(az - bz) + 2 * half + 1);
        chunks = new ArrayList<>();
        for (int cx = x0 >> 4; cx <= (x0 + grid.w) >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= (z0 + grid.h) >> 4; cz++) {
                if (dist((cx << 4) + 8, (cz << 4) + 8) <= half + 12) {
                    chunks.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
        chunks.sort(Comparator.comparingDouble(k -> along(ChunkPos.getX(k) * 16 + 8, ChunkPos.getZ(k) * 16 + 8)));
        next = 0;
        sampled = 0;
    }

    /** How far a point lies from the line between the ends. */
    private double dist(double x, double z) {
        double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / l2));
        return Math.hypot(x - (ax + t * dx), z - (az + t * dz));
    }

    private double along(double x, double z) {
        double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
        return l2 == 0 ? 0 : ((x - ax) * dx + (z - az) * dz) / l2;
    }

    /** A tick of work; {@code budget}: columns it may build. The columns built. */
    int tick(long now, int budget, Executor planner) {
        if (progressAt < 0) {
            progressAt = now;
        }
        gate.sweep(now);
        return switch (phase) {
            case SURVEY -> {
                survey(now, planner);
                yield 0;
            }
            case PLAN -> {
                awaitPlan(now);
                yield 0;
            }
            case BUILD -> build(now, budget);
            default -> 0;
        };
    }

    private void survey(long now, Executor planner) {
        while (inflight.size() < INFLIGHT && next < chunks.size()) {
            inflight.add(chunks.get(next++));
        }
        int done = 0;
        for (Iterator<Long> it = inflight.iterator(); it.hasNext() && done < SAMPLE_PER_TICK; ) {
            long k = it.next();
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            LevelChunk chunk = gate.ask(cx, cz, now);
            if (chunk == null) {
                continue;
            }
            sample(chunk, cx, cz);
            gate.release(cx, cz);
            it.remove();
            done++;
            sampled++;
            progressAt = now;
        }
        if (now - progressAt > TIMEOUT) {
            fail("the land along the way would not load");
            return;
        }
        if (next >= chunks.size() && inflight.isEmpty()) {
            phase = Phase.PLAN;
            Grid g = grid;
            future = CompletableFuture.supplyAsync(() -> plan(g), planner);
        }
    }

    private void sample(LevelChunk chunk, int cx, int cz) {
        for (int x = cx << 4; x < (cx << 4) + 16; x++) {
            for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                if (!grid.in(x, z) || dist(x, z) > half) {
                    continue;
                }
                Terrain.Surface s = Terrain.surface(chunk, x, z, data.ours(x, z));
                grid.set(x, z, s.kind(), s.y(), s.tree());
            }
        }
    }

    /**
     * The road's way for travellers: its centre line folded, from the first end to the second; a village road's from one
     * village's centre to the other's (where the roads of a village meet).
     */
    private int[] way(List<Node> nodes) {
        int[] xz = new int[nodes.size() * 2];
        for (int i = 0; i < nodes.size(); i++) {
            xz[2 * i] = nodes.get(i).x();
            xz[2 * i + 1] = nodes.get(i).z();
        }
        if (xz.length >= 4 && Math.hypot(xz[0] - ax, xz[1] - az) > Math.hypot(xz[xz.length - 2] - ax, xz[xz.length - 1] - az)) {
            for (int i = 0, k = xz.length - 2; i < k; i += 2, k -= 2) {
                int x = xz[i], z = xz[i + 1];
                xz[i] = xz[k];
                xz[i + 1] = xz[k + 1];
                xz[k] = x;
                xz[k + 1] = z;
            }
        }
        if (ends instanceof Villages) {
            int[] full = new int[xz.length + 4];
            full[0] = ax;
            full[1] = az;
            System.arraycopy(xz, 0, full, 2, xz.length);
            full[full.length - 2] = bx;
            full[full.length - 1] = bz;
            xz = full;
        }
        return dev.villageroads.plan.Ways.simplify(xz, dev.villageroads.plan.Ways.TOLERANCE);
    }

    /** The way travellers follow, once built (null before). */
    int[] way() {
        return planned != null && phase == Phase.DONE ? planned.way() : null;
    }

    /** The key a staff road's way is kept under (set when it is built). */
    String wayKey;

    /** On the planner's thread: the way, its heights and its footprint (null: no way). */
    private Planned plan(Grid g) {
        g.finish(2);
        Route route;
        int[] ys;
        if (spec.mode() == Mode.BRIDGE && ends instanceof Points pts) {
            route = RoadPlanner.straight(g, ax, az, bx, bz);
            boolean[] water = new boolean[route.nodes().size()];
            for (int i = 0; i < water.length; i++) {
                Node n = route.nodes().get(i);
                water[i] = g.in(n.x(), n.z()) && g.kind(g.idx(n.x(), n.z())) == Grid.WATER;
            }
            ys = Profile.deck(route.nodes(), water, pts.a().getY(), pts.b().getY());
        } else {
            route = route(g);
            if (route == null) {
                return null;
            }
            ys = Profile.heights(route.nodes());
        }
        List<Node> nodes = route.nodes();
        int[] span = new int[nodes.size()];
        int longest = 0;
        BlockPos bridgeAt = null;
        for (int s = 0; s < nodes.size(); s++) {
            if (!nodes.get(s).bridge()) {
                continue;
            }
            int e = s;
            while (e + 1 < nodes.size() && nodes.get(e + 1).bridge()) {
                e++;
            }
            for (int k = s; k <= e; k++) {
                span[k] = e - s + 1;
            }
            if (e - s + 1 > longest) {
                longest = e - s + 1;
                Node m = nodes.get((s + e) / 2);
                bridgeAt = new BlockPos(m.x(), ys[(s + e) / 2], m.z());
            }
            s = e;
        }
        Node first = nodes.get(0), lastNode = nodes.get(nodes.size() - 1), mid = nodes.get(nodes.size() / 2);
        List<Cell> cells = Footprint.stamp(nodes, ys, spec.width());
        LongOpenHashSet footprint = new LongOpenHashSet();
        for (Cell c : cells) {
            footprint.add(RoadsData.cell(c.x(), c.z()));
        }
        return new Planned(cells, nodes.size(), route.bridges(), longest, span, footprint, new BlockPos(first.x(), ys[0], first.z()),
                new BlockPos(lastNode.x(), ys[ys.length - 1], lastNode.z()), new BlockPos(mid.x(), ys[nodes.size() / 2], mid.z()), bridgeAt, way(nodes));
    }

    private Route route(Grid g) {
        List<Integer> starts = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        BitSet goal = new BitSet(g.size());
        RoadPlanner.Estimate est;
        switch (ends) {
            case Points pts -> {
                starts.add(g.idx(ax, az));
                costs.add(0.0);
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int x = bx + dx, z = bz + dz;
                        if (g.in(x, z) && (dx == 0 && dz == 0 || RoadPlanner.walkable(g, g.idx(x, z), new BitSet()))) {
                            goal.set(g.idx(x, z));
                        }
                    }
                }
                est = (x, z) -> RoadPlanner.WEIGHT * Math.hypot(x - bx, z - bz);
            }
            case Villages v -> {
                int[] r2 = edges(v.a().radius(), v.b().radius(), Math.hypot(bx - ax, bz - az));
                VillageInfo va = withRadius(v.a(), r2[0]), vb = withRadius(v.b(), r2[1]);
                g.village(ax, az, va.radius(), true);
                g.village(bx, bz, vb.radius(), true);
                for (VillageInfo o : v.others()) {
                    g.village(o.center().getX(), o.center().getZ(), o.radius(), false);
                }
                edge(g, va, (i, c) -> {
                    starts.add(i);
                    costs.add(c);
                });
                edge(g, vb, (i, c) -> goal.set(i));
                int r = vb.radius();
                est = (x, z) -> RoadPlanner.WEIGHT * Math.max(0, Math.hypot(x - bx, z - bz) - r);
            }
        }
        if (starts.isEmpty() || goal.isEmpty()) {
            return null;
        }
        int[] st = starts.stream().mapToInt(Integer::intValue).toArray();
        double[] sc = costs.stream().mapToDouble(Double::doubleValue).toArray();
        return RoadPlanner.plan(g, st, sc, goal, est, survey.planner());
    }

    /** Two villages' edges, drawn in where they would meet or nearly, so at least {@link #GAP} blocks of road run between them. */
    static int[] edges(int ra, int rb, double d) {
        if (d >= ra + rb + GAP) {
            return new int[]{ra, rb};
        }
        double f = Math.max(0, d - GAP) / (ra + rb);
        return new int[]{Math.max(MIN_EDGE, (int) (ra * f)), Math.max(MIN_EDGE, (int) (rb * f))};
    }

    static final int GAP = 16, MIN_EDGE = 12;

    static VillageInfo withRadius(VillageInfo v, int r) {
        return new VillageInfo(v.id(), v.name(), v.center(), r, v.lone());
    }

    interface EdgeCell {
        void accept(int index, double cost);
    }

    /** Where a road may start or end at a village: its edge (a ring just outside it), or one of its paths near the edge. */
    static void edge(Grid g, VillageInfo v, EdgeCell out) {
        int cx = v.center().getX(), cz = v.center().getZ(), r = v.radius();
        int reach = r + 2;
        for (int z = cz - reach; z <= cz + reach; z++) {
            for (int x = cx - reach; x <= cx + reach; x++) {
                if (!g.in(x, z)) {
                    continue;
                }
                int i = g.idx(x, z);
                double d = Math.hypot(x - cx, z - cz);
                if (g.kind(i) == Grid.ROAD && d >= r - PATH_BAND && d <= r + 2) {
                    out.accept(i, 0);
                } else if (d >= r && d < r + 1.5 && g.passable(i)) {
                    out.accept(i, EDGE_START);
                }
            }
        }
    }

    private void awaitPlan(long now) {
        if (!future.isDone()) {
            return;
        }
        try {
            planned = future.join();
        } catch (RuntimeException e) {
            fail("the planner broke down (" + e.getMessage() + ")");
            return;
        }
        if (planned == null) {
            fail("no way found (water wider than " + survey.planner().maxSpan() + " blocks, cliffs or buildings all across)");
            return;
        }
        builder = new RoadBuilder(level, spec.road(), spec.bridge(), planned.span(), planned.footprint(), undo, data);
        phase = Phase.BUILD;
        progressAt = now;
    }

    private int build(long now, int budget) {
        List<Cell> cells = planned.cells();
        int built = 0;
        while (cursor < cells.size() && built < budget) {
            int at = cells.get(cursor).at(), end = cursor;
            while (end < cells.size() && cells.get(end).at() == at) {
                end++;
            }
            boolean ready = loaded(cursor, end, now);
            int ahead = end;
            for (int k = 0; k < AHEAD && ahead < cells.size(); k++) {
                int a = cells.get(ahead).at(), e2 = ahead;
                while (e2 < cells.size() && cells.get(e2).at() == a) {
                    e2++;
                }
                loaded(ahead, e2, now);
                ahead = e2;
            }
            if (!ready) {
                if (now - progressAt > TIMEOUT) {
                    fail("the land along the way would not load");
                }
                return built;
            }
            for (int k = cursor; k < end; k++) {
                builder.apply(cells.get(k));
            }
            built += end - cursor;
            cursor = end;
            progressAt = now;
        }
        if (cursor >= cells.size()) {
            phase = Phase.DONE;
            note = summary();
            data.setDirty();
            gate.releaseAll();
        }
        return built;
    }

    /** Asks for the chunks the cells [from, to) and the trees by them stand in; whether all are loaded. */
    private boolean loaded(int from, int to, long now) {
        List<Cell> cells = planned.cells();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int k = from; k < to; k++) {
            Cell c = cells.get(k);
            minX = Math.min(minX, c.x());
            maxX = Math.max(maxX, c.x());
            minZ = Math.min(minZ, c.z());
            maxZ = Math.max(maxZ, c.z());
        }
        int pad = RoadBuilder.TREE_REACH + 1;
        boolean all = true;
        for (int cx = (minX - pad) >> 4; cx <= (maxX + pad) >> 4; cx++) {
            for (int cz = (minZ - pad) >> 4; cz <= (maxZ + pad) >> 4; cz++) {
                all &= gate.ask(cx, cz, now) != null;
            }
        }
        return all;
    }

    String summary() {
        if (planned == null) {
            return "";
        }
        String what = spec.mode() == Mode.BRIDGE ? "a bridge of " + planned.length() + " blocks" : planned.length() + (planned.length() == 1 ? " block" : " blocks") + " of road";
        String bridges = spec.mode() == Mode.BRIDGE || planned.bridges() == 0 ? ""
                : planned.bridges() == 1 ? ", a bridge of " + planned.longest() + " blocks" : ", " + planned.bridges() + " bridges (the longest " + planned.longest() + " blocks)";
        return what + bridges;
    }

    /** Where it runs, for the log: its ends, middle and longest bridge. */
    String where() {
        if (planned == null) {
            return "";
        }
        return "from " + planned.from().toShortString() + " to " + planned.to().toShortString() + ", middle " + planned.middle().toShortString()
                + (planned.bridge() != null ? ", bridge at " + planned.bridge().toShortString() : "");
    }

    void fail(String why) {
        phase = Phase.FAILED;
        note = why;
        gate.releaseAll();
        if (future != null) {
            future.cancel(false);
        }
    }

    /** How far along it is, in words. */
    String progress() {
        return switch (phase) {
            case SURVEY -> "surveying the land: " + (chunks.isEmpty() ? 100 : 100 * sampled / chunks.size()) + "%";
            case PLAN -> "finding the way...";
            case BUILD -> "building: " + (planned.cells().isEmpty() ? 100 : 100 * cursor / planned.cells().size()) + "% (" + summary() + ")";
            case DONE -> "done: " + note;
            case FAILED -> "failed: " + note;
        };
    }

    /** The columns of the road the network counts as road (to untrack on undo). */
    LongOpenHashSet footprint() {
        return planned != null ? planned.footprint() : new LongOpenHashSet();
    }

    boolean finished() {
        return phase == Phase.DONE || phase == Phase.FAILED;
    }

    void releaseAll() {
        gate.releaseAll();
    }

    ChunkGate gate() {
        return gate;
    }
}

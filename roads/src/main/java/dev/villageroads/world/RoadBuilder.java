package dev.villageroads.world;

import dev.villageroads.plan.Footprint.Cell;
import dev.villageroads.plan.Grid;
import dev.villageroads.world.Styles.BridgeStyle;
import dev.villageroads.world.Styles.RoadStyle;
import dev.villageroads.world.Terrain.Surface;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lays a road's cells in the world. Over land: what grows on the way is cleared (a tree on it is felled whole), the land is cut
 * down or filled up with earth to the road's height, the surface laid, and the banks either side grassed with the land's own
 * top, a step lower further out, so the road sits in the country; water that would run into a cutting is banked off. Over
 * water: the deck, its rails, pillars down to the bed and lanterns. Only what nature made is ever cut, filled or cleared: a
 * building's blocks are never touched, and an old road (a village's path) is kept as it is and joined.
 */
final class RoadBuilder {
    /** Headroom cleared over a road (a rider passes). */
    static final int HEAD = 4;
    /** The deepest a hollow under a road is filled, and a pillar goes down. */
    static final int FILL = 16, PILLAR = 64;
    /** A tree is felled within this many blocks of where it was struck, and at most this many logs. */
    static final int TREE_REACH = 6, TREE_LOGS = 320;

    private final ServerLevel level;
    private final RoadStyle road;
    private final BridgeStyle bridge;
    private final int[] span;
    private final LongOpenHashSet footprint;
    private final Map<BlockPos, BlockState> undo;
    private final RoadsData data;
    private final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
    private BlockState soil = Blocks.GRASS_BLOCK.defaultBlockState();
    int changed;

    /** {@code span}: per node of the way, the length of the bridge it is on (0: land); {@code undo}: where to keep what was there, or null. */
    RoadBuilder(ServerLevel level, RoadStyle road, BridgeStyle bridge, int[] span, LongOpenHashSet footprint, Map<BlockPos, BlockState> undo, RoadsData data) {
        this.level = level;
        this.road = road;
        this.bridge = bridge;
        this.span = span;
        this.footprint = footprint;
        this.undo = undo;
        this.data = data;
    }

    void apply(Cell c) {
        switch (c.part()) {
            case CORE -> land(c, true);
            case EDGE -> land(c, false);
            case SHOULDER -> bank(c, false);
            case SLOPE -> bank(c, true);
            case DECK -> deck(c, false);
            case RAIL -> deck(c, true);
            case PILLAR -> pillar(c);
            case LAMP -> lamp(c);
        }
    }

    Surface surface(int x, int z) {
        return Terrain.surface(level.getChunk(x >> 4, z >> 4), x, z, data.ours(x, z));
    }

    private void land(Cell c, boolean core) {
        int x = c.x(), y = c.y(), z = c.z();
        Surface s = surface(x, z);
        if (s.kind() == Grid.STRUCTURE || s.kind() == Grid.LAVA || s.kind() == Grid.UNKNOWN) {
            return;
        }
        if (s.kind() == Grid.ROAD && Math.abs(s.y() - y) <= 1) {
            clearPlants(x, s.y() + 1, z, 2); // a village's path or an old road: kept, joined
            return;
        }
        learn(s);
        clearAbove(x, y, z, Math.max(y + HEAD, s.y() + 1));
        BlockState top = core ? road.core(Styles.noise(x, z, 1)) : road.edge(Styles.noise(x, z, 2));
        if (top == null) {
            top = s.kind() == Grid.LAND && s.y() == y && Terrain.soil(s.state()) ? s.state() : soil;
        }
        place(x, y, z, top);
        if (c.half() && road.slab() != null) {
            place(x, y + 1, z, road.slab()); // a half step where the road climbs
        }
        fillBelow(x, y - 1, z);
        seal(x, y + (c.half() ? 1 : 0), z);
        data.cells.add(RoadsData.cell(x, z));
    }

    /** A bank beside the road: level with it ({@code slope}: a step lower further out), filled or cut to it and grassed. */
    private void bank(Cell c, boolean slope) {
        int x = c.x(), y = c.y(), z = c.z();
        Surface s = surface(x, z);
        if (s.kind() == Grid.STRUCTURE || s.kind() == Grid.ROAD || s.kind() == Grid.LAVA || s.kind() == Grid.UNKNOWN) {
            return;
        }
        learn(s);
        int low = slope ? y - 1 : y, high = slope ? y + 1 : y;
        if (s.y() < low) {
            fillBelow(x, low - 1, z);
            place(x, low, z, soil);
            clearPlants(x, low + 1, z, 2);
        } else if (s.y() > high) {
            clearAbove(x, high, z, s.y() + 1);
            p.set(x, high, z);
            if (Terrain.soil(level.getBlockState(p)) || level.getBlockState(p).is(Blocks.DIRT)) {
                place(x, high, z, soil);
            }
            seal(x, high, z); // a cutting: water above it beside is banked off (water level with the land is left alone)
        }
    }

    private void deck(Cell c, boolean rail) {
        int x = c.x(), y = c.y(), z = c.z();
        BridgeStyle st = bridge.forSpan(span[c.at()]);
        Surface s = surface(x, z);
        if (s.kind() == Grid.STRUCTURE && s.y() >= y && !data.ours(x, z)) {
            return;
        }
        clearAbove(x, y, z, y + HEAD);
        place(x, y, z, st.deck(Styles.noise(x, z, 3)));
        if (rail) {
            shaped(x, y + 1, z, st.rail().defaultBlockState());
        } else {
            data.cells.add(RoadsData.cell(x, z));
        }
    }

    private void pillar(Cell c) {
        int x = c.x(), y = c.y(), z = c.z();
        BridgeStyle st = bridge.forSpan(span[c.at()]);
        p.set(x, y, z);
        if (Terrain.natural(level.getBlockState(p))) {
            place(x, y, z, st.deck(Styles.noise(x, z, 3)));
        }
        p.set(x, y + 1, z);
        if (Terrain.natural(level.getBlockState(p))) {
            shaped(x, y + 1, z, st.rail().defaultBlockState());
        }
        BlockState post = st.pillar();
        for (int yy = y - 1; yy > y - PILLAR && yy > level.getMinBuildHeight(); yy--) {
            p.set(x, yy, z);
            BlockState b = level.getBlockState(p);
            if (!(b.isAir() || replaceableFluid(b) || Terrain.vegetation(b))) {
                break;
            }
            place(x, yy, z, post);
        }
    }

    private void lamp(Cell c) {
        p.set(c.x(), c.y() + 2, c.z());
        if (level.getBlockState(p).isAir()) {
            place(c.x(), c.y() + 2, c.z(), Blocks.LANTERN.defaultBlockState());
        }
    }

    /** Remembers the land's own top (grass, sand, podzol, snow...) to grass the banks with. */
    private void learn(Surface s) {
        if (s.kind() == Grid.LAND && Terrain.soil(s.state()) && !s.state().is(Blocks.GRAVEL)) {
            soil = s.state().is(Blocks.DIRT) || s.state().is(Blocks.COARSE_DIRT) || s.state().is(Blocks.ROOTED_DIRT)
                    ? Blocks.GRASS_BLOCK.defaultBlockState() : s.state().getBlock().defaultBlockState();
        }
    }

    /** Clears what nature put over {@code y} up to {@code top}: plants and earth cut away, trees felled, water banked off. */
    private void clearAbove(int x, int y, int z, int top) {
        for (int yy = y + 1; yy <= top; yy++) {
            p.set(x, yy, z);
            BlockState b = level.getBlockState(p);
            if (b.isAir() || !Terrain.natural(b)) {
                continue;
            }
            if (b.is(BlockTags.LOGS)) {
                fell(p.immutable());
            } else if (!b.getFluidState().isEmpty()) {
                place(x, yy, z, Blocks.DIRT.defaultBlockState());
            } else {
                place(x, yy, z, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private void clearPlants(int x, int y, int z, int height) {
        for (int yy = y; yy < y + height; yy++) {
            p.set(x, yy, z);
            BlockState b = level.getBlockState(p);
            if (!b.isAir() && b.canBeReplaced() && b.getFluidState().isEmpty()) {
                place(x, yy, z, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** Fills the hollow under {@code y} with earth, down to the ground (or the bed of shallow water). */
    private void fillBelow(int x, int y, int z) {
        for (int yy = y; yy > y - FILL && yy > level.getMinBuildHeight(); yy--) {
            p.set(x, yy, z);
            BlockState b = level.getBlockState(p);
            if (!(b.isAir() || replaceableFluid(b) || Terrain.vegetation(b))) {
                return;
            }
            if (b.is(BlockTags.LOGS)) {
                fell(p.immutable());
            }
            place(x, yy, z, Blocks.DIRT.defaultBlockState());
        }
    }

    /** Water beside the road above its surface would run onto it: banked off with earth (unless it is road there too). */
    private void seal(int x, int y, int z) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (footprint.contains(RoadsData.cell(nx, nz))) {
                continue;
            }
            for (int yy = y + 1; yy <= y + 3; yy++) {
                p.set(nx, yy, nz);
                BlockState b = level.getBlockState(p);
                if (b.getFluidState().is(FluidTags.WATER) && Terrain.natural(b)) {
                    place(nx, yy, nz, Blocks.DIRT.defaultBlockState());
                }
            }
        }
    }

    /** Fells a tree struck at {@code start}: its connected logs, near it; its leaves then fall as leaves do. */
    private void fell(BlockPos start) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        int n = 0;
        while (!queue.isEmpty() && n < TREE_LOGS) {
            BlockPos at = queue.poll();
            if (!level.getBlockState(at).is(BlockTags.LOGS)) {
                continue;
            }
            set(at, Blocks.AIR.defaultBlockState());
            n++;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        BlockPos q = at.offset(dx, dy, dz);
                        if (Math.abs(q.getX() - start.getX()) > TREE_REACH || Math.abs(q.getZ() - start.getZ()) > TREE_REACH
                                || q.getY() < start.getY() - 1 || !seen.add(q)) {
                            continue;
                        }
                        if (level.getBlockState(q).is(BlockTags.LOGS)) {
                            queue.add(q);
                        }
                    }
                }
            }
        }
    }

    private static boolean replaceableFluid(BlockState b) {
        return !b.getFluidState().isEmpty() && b.canBeReplaced();
    }

    private void place(int x, int y, int z, BlockState state) {
        set(new BlockPos(x, y, z), state);
    }

    /** A rail or post, joined to its neighbours. */
    private void shaped(int x, int y, int z, BlockState state) {
        BlockPos at = new BlockPos(x, y, z);
        set(at, Block.updateFromNeighbourShapes(state, level, at));
    }

    private void set(BlockPos at, BlockState state) {
        BlockState old = level.getBlockState(at);
        if (old == state) {
            return;
        }
        if (undo != null) {
            undo.putIfAbsent(at.immutable(), old);
        }
        level.setBlock(at, state, Block.UPDATE_ALL);
        changed++;
    }
}

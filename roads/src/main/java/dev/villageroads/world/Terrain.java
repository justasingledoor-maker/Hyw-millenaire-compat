package dev.villageroads.world;

import dev.villageroads.plan.Grid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.Tags;

/**
 * What the land is made of, as a road builder sees it: the ground (what nature laid: soil, sand, rock, ice), what grows on it
 * (trees, plants: cleared away), water, roads (vanilla dirt paths, Millénaire's paths and this mod's own) and everything
 * else, which some builder put there: a building, to be kept clear of and never touched.
 */
public final class Terrain {
    private Terrain() {}

    /** What a column's top is: its kind ({@link Grid}), height, the block, and whether a tree stands on it. */
    public record Surface(byte kind, int y, BlockState state, boolean tree) {}

    /** The deepest a column is searched below its top (tall trees, deep water). */
    static final int DEPTH = 64;

    public static boolean vegetation(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
                || s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.REPLACEABLE_BY_TREES)
                || s.is(Blocks.CACTUS) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.BAMBOO) || s.is(Blocks.BAMBOO_SAPLING)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.RED_MUSHROOM)
                || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM)
                || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.COCOA) || s.is(Blocks.BEE_NEST)
                || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.PINK_PETALS) || s.is(Blocks.LILY_PAD) || s.is(Blocks.KELP)
                || s.is(Blocks.KELP_PLANT) || s.is(Blocks.SEA_PICKLE) || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.BIG_DRIPLEAF)
                || s.is(Blocks.BIG_DRIPLEAF_STEM) || s.is(Blocks.SMALL_DRIPLEAF) || s.is(Blocks.POINTED_DRIPSTONE)
                || s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT) || s.is(Blocks.SPORE_BLOSSOM)
                || s.is(Blocks.PITCHER_PLANT) || s.is(Blocks.TORCHFLOWER) || s.is(Blocks.HANGING_ROOTS);
    }

    public static boolean ground(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.TERRACOTTA)
                || s.is(Tags.Blocks.ORES) || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)
                || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.OBSIDIAN) || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.SUSPICIOUS_SAND) || s.is(Blocks.SUSPICIOUS_GRAVEL) || s.is(Blocks.BEDROCK) || s.is(Blocks.MUD)
                || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.AMETHYST_BLOCK) || s.is(Blocks.BUDDING_AMETHYST);
    }

    /** A road block: vanilla's dirt path and Millénaire's paths (dirt, gravel, slabs, sandstone, ochre tiles, snow). */
    public static boolean road(BlockState s) {
        if (s.is(Blocks.DIRT_PATH)) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        return "millenaire".equals(id.getNamespace()) && id.getPath().startsWith("path");
    }

    /** What nature made (the builder may cut, fill and clear it); anything else is a building's and is never touched. */
    public static boolean natural(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() && s.canBeReplaced() || vegetation(s) || ground(s);
    }

    /** Ground good to grass a road's banks with (its top kept where the land had it). */
    public static boolean soil(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.GRAVEL);
    }

    /**
     * A column's top as a surveyor sees it: down from its highest block past plants and trees to the ground, water, a road or
     * a building. {@code ours}: the column is one of this mod's roads (its stone and planks count as road).
     */
    public static Surface surface(ChunkAccess chunk, int x, int z, boolean ours) {
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int bottom = Math.max(chunk.getMinBuildHeight(), top - DEPTH);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
        boolean tree = false;
        for (int y = top; y > bottom; y--) {
            p.setY(y);
            BlockState s = chunk.getBlockState(p);
            if (s.isAir()) {
                continue;
            }
            if (!s.getFluidState().isEmpty()) {
                if (s.getFluidState().is(FluidTags.WATER)) {
                    return new Surface(Grid.WATER, y, s, tree);
                }
                if (s.getFluidState().is(FluidTags.LAVA)) {
                    return new Surface(Grid.LAVA, y, s, tree);
                }
            }
            if (ours && !vegetation(s) && (!ground(s) || s.is(Blocks.GRAVEL) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ANDESITE)
                    || s.is(Blocks.MOSSY_COBBLESTONE))) {
                return new Surface(Grid.ROAD, y, s, tree); // still our road (dug up and grassed over, it is land again)
            }
            if (road(s)) {
                return new Surface(Grid.ROAD, y, s, tree);
            }
            if (vegetation(s)) {
                tree |= s.is(BlockTags.LOGS);
                continue;
            }
            return new Surface(ground(s) ? Grid.LAND : Grid.STRUCTURE, y, s, tree);
        }
        return new Surface(Grid.UNKNOWN, bottom, Blocks.AIR.defaultBlockState(), tree);
    }
}

package dev.villageroads.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/** What roads and bridges are made of. Each draws its blocks by a column's own random number, so a road is the same if rebuilt. */
public final class Styles {
    private Styles() {}

    /**
     * A road's surface: its crown, and its edges, rougher and mixed with the land's own top ({@code null}: the land's), so the
     * road reads clearly yet blends into the country.
     */
    public enum RoadStyle {
        PATH("Dirt path"), GRAVEL("Gravel"), COBBLESTONE("Cobblestone"), STONE_BRICK("Stone bricks");

        public final String label;

        RoadStyle(String label) {
            this.label = label;
        }

        public BlockState core(double r) {
            return switch (this) {
                case PATH -> r < 0.94 ? Blocks.DIRT_PATH.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
                case GRAVEL -> r < 0.92 ? Blocks.GRAVEL.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
                case COBBLESTONE -> r < 0.8 ? Blocks.COBBLESTONE.defaultBlockState() : r < 0.9 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                        : r < 0.96 ? Blocks.ANDESITE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
                case STONE_BRICK -> r < 0.74 ? Blocks.STONE_BRICKS.defaultBlockState() : r < 0.86 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                        : r < 0.95 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            };
        }

        public BlockState edge(double r) {
            return switch (this) {
                case PATH -> r < 0.74 ? Blocks.DIRT_PATH.defaultBlockState() : r < 0.92 ? Blocks.COARSE_DIRT.defaultBlockState() : null;
                case GRAVEL -> r < 0.6 ? Blocks.GRAVEL.defaultBlockState() : r < 0.82 ? Blocks.COARSE_DIRT.defaultBlockState()
                        : r < 0.95 ? Blocks.DIRT_PATH.defaultBlockState() : null;
                case COBBLESTONE -> r < 0.58 ? Blocks.COBBLESTONE.defaultBlockState() : r < 0.72 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                        : r < 0.84 ? Blocks.GRAVEL.defaultBlockState() : r < 0.95 ? Blocks.COARSE_DIRT.defaultBlockState() : null;
                case STONE_BRICK -> r < 0.55 ? Blocks.COBBLESTONE.defaultBlockState() : r < 0.72 ? Blocks.STONE_BRICKS.defaultBlockState()
                        : r < 0.84 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : r < 0.95 ? Blocks.GRAVEL.defaultBlockState() : null;
            };
        }

        /** A half step's slab: stone for stone roads; for earth roads Millénaire's path slabs, if it is installed (else none). */
        public BlockState slab() {
            return switch (this) {
                case COBBLESTONE -> Blocks.COBBLESTONE_SLAB.defaultBlockState();
                case STONE_BRICK -> Blocks.STONE_BRICK_SLAB.defaultBlockState();
                case PATH -> optional("millenaire", "path_dirt_slab");
                case GRAVEL -> optional("millenaire", "path_gravel_slab");
            };
        }
    }

    /** A bridge: its deck, rail, pillars. {@link #AUTO}: timber for a short span, stone for a long one. */
    public enum BridgeStyle {
        AUTO("Auto (timber, stone if long)"), OAK("Oak"), SPRUCE("Spruce"), DARK_OAK("Dark oak"), STONE("Stone");

        public final String label;

        BridgeStyle(String label) {
            this.label = label;
        }

        /** The style a bridge of this many blocks is built in. */
        public BridgeStyle forSpan(int span) {
            return this != AUTO ? this : span <= 16 ? SPRUCE : STONE;
        }

        public BlockState deck(double r) {
            return switch (this) {
                case OAK -> Blocks.OAK_PLANKS.defaultBlockState();
                case SPRUCE, AUTO -> Blocks.SPRUCE_PLANKS.defaultBlockState();
                case DARK_OAK -> Blocks.DARK_OAK_PLANKS.defaultBlockState();
                case STONE -> r < 0.8 ? Blocks.STONE_BRICKS.defaultBlockState() : r < 0.9 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                        : Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
            };
        }

        public Block rail() {
            return switch (this) {
                case OAK -> Blocks.OAK_FENCE;
                case SPRUCE, AUTO -> Blocks.SPRUCE_FENCE;
                case DARK_OAK -> Blocks.DARK_OAK_FENCE;
                case STONE -> Blocks.STONE_BRICK_WALL;
            };
        }

        public BlockState pillar() {
            Block b = switch (this) {
                case OAK -> Blocks.OAK_LOG;
                case SPRUCE, AUTO -> Blocks.SPRUCE_LOG;
                case DARK_OAK -> Blocks.DARK_OAK_LOG;
                case STONE -> Blocks.STONE_BRICKS;
            };
            BlockState s = b.defaultBlockState();
            return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y) : s;
        }
    }

    static BlockState optional(String ns, String path) {
        Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ns, path));
        return b == Blocks.AIR ? null : b.defaultBlockState();
    }

    /** A column's own random number in [0, 1) (the same each time). */
    public static double noise(int x, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
}

package dev.villageroads.api;

import dev.villageroads.world.RoadsData;
import net.minecraft.server.level.ServerLevel;

/**
 * For other mods: the road network as travellers use it. A journey goes across country to the nearest road (within 160
 * blocks), along the roads (through the villages they link, over their bridges, by junctions and players' roads) and off them
 * to its end; it is not taken when the roads would make it much longer than going straight. Plain Java types only, so a mod
 * may call it by reflection without depending on this one. Server side.
 */
public final class Roads {
    private Roads() {}

    /** The journey by the roads from (fromX, fromZ) to (toX, toZ): points {x0, z0, x1, z1, ...} from the start to the end; null when no road serves it. */
    public static int[] route(ServerLevel level, int fromX, int fromZ, int toX, int toZ) {
        return RoadsData.get(level).route(level, fromX, fromZ, toX, toZ);
    }

    /** Whether the column (x, z) is part of one of this mod's roads. */
    public static boolean onRoad(ServerLevel level, int x, int z) {
        return RoadsData.get(level).ours(x, z);
    }

    /** How many roads travellers can follow in this level. */
    public static int roads(ServerLevel level) {
        return RoadsData.get(level).wayCount(level);
    }
}

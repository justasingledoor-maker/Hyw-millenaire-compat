package dev.villageroads;

import dev.villageroads.world.Styles.BridgeStyle;
import dev.villageroads.world.Styles.RoadStyle;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server settings (serverconfig/villageroads-server.toml). */
public final class RoadsConfig {
    private RoadsConfig() {}

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue AUTO_ROADS, LONE_BUILDINGS, ANNOUNCE, STAFF_CREATIVE_ONLY;
    public static final ModConfigSpec.IntValue AUTO_REACH, AUTO_WIDTH, CORRIDOR, SCAN_TICKS, COLUMNS_PER_TICK, MAX_SPAN, STAFF_REACH;
    public static final ModConfigSpec.EnumValue<RoadStyle> AUTO_STYLE;
    public static final ModConfigSpec.EnumValue<BridgeStyle> AUTO_BRIDGE;
    public static final ModConfigSpec.DoubleValue BRIDGE_COST;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("villages");
        AUTO_ROADS = b.comment("Link each Millénaire village to its neighbours by road (built in the background, one at a time).")
                .define("autoRoads", true);
        AUTO_REACH = b.comment("Villages farther apart than this (blocks, centre to centre) are not linked.")
                .defineInRange("maxDistance", 700, 64, 4000);
        LONE_BUILDINGS = b.comment("Link Millénaire's lone buildings (a hermit's hut, a lone farm) too.").define("loneBuildings", false);
        AUTO_STYLE = b.comment("The village roads' surface.").defineEnum("roadStyle", RoadStyle.COBBLESTONE);
        AUTO_BRIDGE = b.comment("The village roads' bridges (AUTO: timber up to 16 blocks, stone if longer).").defineEnum("bridgeStyle", BridgeStyle.AUTO);
        AUTO_WIDTH = b.comment("The village roads' width (blocks; odd).").defineInRange("width", 3, 1, 7);
        SCAN_TICKS = b.comment("How often the villages are looked over for new links (ticks).").defineInRange("scanTicks", 6000, 200, 720000);
        ANNOUNCE = b.comment("Tell the players when a road between villages is finished.").define("announce", true);
        b.pop();
        b.push("survey");
        CORRIDOR = b.comment("How far either side of the straight line between two villages a road may wander (blocks).")
                .defineInRange("corridor", 80, 16, 256);
        MAX_SPAN = b.comment("The longest bridge (blocks of water). Wider water is gone round, or the road fails.").defineInRange("maxBridge", 48, 2, 128);
        BRIDGE_COST = b.comment("What a block of bridge costs against a block of road when choosing between a bridge and the way round.")
                .defineInRange("bridgeCost", 1.3, 0.5, 10.0);
        COLUMNS_PER_TICK = b.comment("Road columns built per tick (all jobs together).").defineInRange("columnsPerTick", 48, 1, 2000);
        b.pop();
        b.push("staff");
        STAFF_REACH = b.comment("The farthest apart the Surveyor's Staff's two points may be (blocks).").defineInRange("maxDistance", 512, 8, 4000);
        STAFF_CREATIVE_ONLY = b.comment("Only players in creative mode may build with the staff.").define("creativeOnly", false);
        b.pop();
        SPEC = b.build();
    }
}

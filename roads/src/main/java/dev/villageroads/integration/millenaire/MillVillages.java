package dev.villageroads.integration.millenaire;

import dev.villageroads.world.VillageInfo;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.util.List;

/** The villages a road network links: Millénaire's, if it is installed (its classes are touched only then). */
public final class MillVillages {
    private MillVillages() {}

    public static boolean available() {
        return ModList.get().isLoaded("millenaire");
    }

    public static List<VillageInfo> list(ServerLevel level) {
        return available() ? MillenaireAccess.list(level) : List.of();
    }
}

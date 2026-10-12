package dev.villageroads.integration.millenaire;

import dev.villageroads.world.VillageInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads Millénaire's villages (VillageSavedData.get, VillageManager.getAllVillages, Village.getId/getVillageName/getCenter/
 * isLoneBuilding/computeBounds/getVillageTypeId, ModCultures.getVillageType, VillageType.radius). Loaded only when Millénaire is.
 */
final class MillenaireAccess {
    private MillenaireAccess() {}

    /** Millénaire's default village radius, if the type is not loaded. */
    static final int DEFAULT_RADIUS = 90;
    /** A road ends this far beyond a village's outermost building (within its radius). */
    static final int MARGIN = 10, SMALLEST = 24;

    static List<VillageInfo> list(ServerLevel level) {
        List<VillageInfo> out = new ArrayList<>();
        for (Village v : VillageSavedData.get(level).getVillageManager().getAllVillages()) {
            BlockPos c = v.getCenter();
            if (c == null || v.getId() == null) {
                continue;
            }
            VillageType vt = v.getVillageTypeId() != null ? ModCultures.getVillageType(v.getVillageTypeId()) : null;
            int radius = vt != null ? vt.radius() : DEFAULT_RADIUS;
            out.add(new VillageInfo(v.getId().uuid().toString(), v.getVillageName() != null ? v.getVillageName() : "a village", c,
                    reach(v, c, radius), v.isLoneBuilding()));
        }
        return out;
    }

    /** How far the village's buildings reach from its centre, plus a margin; at most its radius (a young village is small). */
    static int reach(Village v, BlockPos c, int radius) {
        AABB b;
        try {
            b = v.computeBounds();
        } catch (RuntimeException e) {
            return radius;
        }
        if (b == null) {
            return radius;
        }
        double far = Math.max(Math.max(Math.abs(b.minX - c.getX()), Math.abs(b.maxX - c.getX())),
                Math.max(Math.abs(b.minZ - c.getZ()), Math.abs(b.maxZ - c.getZ())));
        return (int) Math.max(SMALLEST, Math.min(radius, far + MARGIN));
    }
}

package dev.hywmill.integration.roads;

import dev.hywmill.core.HmLog;
import dev.hywmill.politics.war.Route;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * Optional Village Roads (1.10, docs/siege-design.md "War on the roads"): columns, hosts and supply trains follow the roads it
 * builds between villages. No compile dependency: its public {@code dev.villageroads.api.Roads.route} takes and returns plain
 * types and is called by reflection. Without the mod (or a road that serves the journey) travellers go straight.
 */
public final class VillageRoads {
    private VillageRoads() {}

    public static final String MOD = "villageroads";

    /** The API, looked up once (class metadata, not server state). */
    private static final class Api {
        static final Method ROUTE = find();

        static Method find() {
            try {
                if (!ModList.get().isLoaded(MOD)) {
                    return null;
                }
                return Class.forName("dev.villageroads.api.Roads").getMethod("route", ServerLevel.class, int.class, int.class, int.class, int.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                HmLog.warn("Village Roads is installed but its route API was not found: {}", ex.toString());
                return null;
            }
        }
    }

    public static boolean available() {
        return Api.ROUTE != null;
    }

    /** The road points from (x0, z0) to (x1, z1), or null (no mod, no road that serves it, or an error). */
    @Nullable
    public static int[] road(ServerLevel level, int x0, int z0, int x1, int z1) {
        Method m = Api.ROUTE;
        if (m == null) {
            return null;
        }
        try {
            Object r = m.invoke(null, level, x0, z0, x1, z1);
            return r instanceof int[] xz && xz.length >= 6 ? xz : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            HmLog.warn("Village Roads: route failed: {}", ex.toString());
            return null;
        }
    }

    /** The journey from (x0, z0) to (x1, z1): by the roads when they serve it, else straight. */
    public static Route route(ServerLevel level, int x0, int z0, int x1, int z1) {
        return Route.of(road(level, x0, z0, x1, z1), x0, z0, x1, z1);
    }
}

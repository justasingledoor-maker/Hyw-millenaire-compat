package dev.hywmill.client;

import dev.hywmill.net.MapPayloads;

import java.util.List;

/**
 * The map markers last sent by the server (1.8), for a map mod's integration to show (integration.journeymap). Client
 * holder, like the HUD's.
 */
public final class MapMarkers {
    private MapMarkers() {}

    private static volatile List<MapPayloads.Marker> markers = List.of();
    private static volatile int version;

    public static void accept(MapPayloads.Markers p) {
        markers = List.copyOf(p.markers());
        version++;
    }

    public static List<MapPayloads.Marker> markers() {
        return markers;
    }

    /** Changes each time new markers arrive. */
    public static int version() {
        return version;
    }

    /** Logged out: nothing to show. */
    public static void clear() {
        markers = List.of();
        version++;
    }

    /** What is left of a marker's clock, as m:ss, or empty when it has none. */
    public static String clock(MapPayloads.Marker m, long gameTime) {
        if (m.dueAt() < 0) {
            return "";
        }
        long sec = Math.max(0, m.dueAt() - gameTime) / 20;
        return sec / 60 + ":" + String.format("%02d", sec % 60);
    }
}

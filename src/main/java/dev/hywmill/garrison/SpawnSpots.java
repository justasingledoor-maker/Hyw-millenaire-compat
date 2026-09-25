package dev.hywmill.garrison;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic spawn-spot candidates around the anchor: the anchor itself, then three points on
 * each ring of radius 1..8 (120 degrees apart), rotated by an angle derived from the roster id so
 * that units of one village spread out. At most {@link #MAX_CANDIDATES} offsets, fixed order.
 */
public final class SpawnSpots {
    public static final int MAX_RADIUS = 8;
    public static final int MAX_CANDIDATES = 24;

    private SpawnSpots() {}

    public static List<int[]> candidates(UUID rosterId) {
        List<int[]> out = new ArrayList<>(MAX_CANDIDATES);
        out.add(new int[]{0, 0});
        double base = Math.floorMod(rosterId.getLeastSignificantBits() ^ rosterId.getMostSignificantBits(), 360L) * Math.PI / 180.0;
        for (int r = 1; r <= MAX_RADIUS && out.size() < MAX_CANDIDATES; r++) {
            for (int k = 0; k < 3 && out.size() < MAX_CANDIDATES; k++) {
                double a = base + r * 0.7 + k * 2 * Math.PI / 3;
                out.add(new int[]{(int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r)});
            }
        }
        return out;
    }
}

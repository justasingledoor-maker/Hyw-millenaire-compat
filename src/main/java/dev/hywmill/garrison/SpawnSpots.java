package dev.hywmill.garrison;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
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

    /** Searches the candidates around one anchor (the caller's loaded-terrain-only safety check); null if none is safe. */
    @FunctionalInterface
    public interface Probe<T> {
        @Nullable
        T find(BlockPos anchor, UUID rosterId);
    }

    /** The chosen spot; {@code fallback} is true if it was found around the village centre, not the preferred anchor. */
    public record Choice<T>(T spot, boolean fallback) {}

    /**
     * Spawn-location choice for one spawn attempt: the existing candidate search around the
     * preferred anchor (Millénaire's defending position) first; only if it finds nothing, the same
     * bounded, deterministic search around the village centre. The centre is used for this attempt
     * only and never replaces the preferred anchor. Null if neither has a safe spot: the slot stays
     * RECRUITED and is retried on a later pass.
     */
    @Nullable
    public static <T> Choice<T> choose(BlockPos preferred, BlockPos centre, UUID rosterId, Probe<T> probe) {
        T spot = probe.find(preferred, rosterId);
        if (spot != null) {
            return new Choice<>(spot, false);
        }
        if (centre.equals(preferred)) {
            return null;
        }
        T fallback = probe.find(centre, rosterId);
        return fallback != null ? new Choice<>(fallback, true) : null;
    }
}

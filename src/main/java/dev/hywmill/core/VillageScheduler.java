package dev.hywmill.core;

import java.util.UUID;

/**
 * Staggers per-village periodic work across ticks. Each village keeps its cadence (every
 * {@code interval} ticks) but gets a stable phase derived from its id, so N villages are spread
 * over the interval instead of all running in one tick.
 */
public final class VillageScheduler {
    public boolean isDue(UUID village, long tick, int interval) {
        return phaseOf(village, interval) == Math.floorMod(tick, interval);
    }

    public static int phaseOf(UUID village, int interval) {
        long h = village.getMostSignificantBits() ^ village.getLeastSignificantBits();
        return (int) Math.floorMod(h ^ (h >>> 32), (long) interval);
    }
}

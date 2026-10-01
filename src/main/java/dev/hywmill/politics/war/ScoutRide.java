package dev.hywmill.politics.war;

import java.util.UUID;

/**
 * A scout out on a ride (post-M5): one light horseman of {@code village}, gone (stowed) until {@code back}, looking for intel
 * on the enemy. Pure; persisted at ledger level.
 */
public record ScoutRide(UUID village, UUID rider, long out, long back) {
    public static final long MIN_TICKS = 2400, MAX_TICKS = 4800;
    /** A scout dies or is taken on one ride in ten; one in two who come back found something. */
    public static final double LOST = 0.1, FINDS = 0.55;
}

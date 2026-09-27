package dev.hywmill.politics;

import java.util.UUID;

/**
 * A pending envoy mission (persisted at ledger level, bounded per player; resolved in M5-4).
 *
 * @param seed deterministic seed for the outcome roll
 */
public record EnvoyMission(UUID id, UUID player, UUID from, UUID to, EnvoyKind kind, long departTick, long arriveTick, long seed) {
    /** Maximum pending missions per player (a ledger-level bound). */
    public static final int MAX_PER_PLAYER = 3;
}

package dev.hywmill.faction;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic synthetic faction identities. A village's faction UUID is a name-based (v3)
 * UUID of a fixed namespace string plus the village UUID, so it is identical across restarts,
 * machines and ledger resets, and never collides with a real player or entity UUID space in practice.
 */
public final class FactionIds {
    private static final String NAMESPACE = "hywmill:millenaire_village:";
    private static final Map<UUID, UUID> FACTION_TO_VILLAGE = new ConcurrentHashMap<>();

    private FactionIds() {}

    public static UUID forVillage(UUID villageId) {
        UUID faction = UUID.nameUUIDFromBytes((NAMESPACE + villageId).getBytes(StandardCharsets.UTF_8));
        FACTION_TO_VILLAGE.putIfAbsent(faction, villageId);
        return faction;
    }

    /** Village for a faction UUID we generated this session, or null for any other UUID. */
    @Nullable
    public static UUID villageOf(UUID faction) {
        return faction == null ? null : FACTION_TO_VILLAGE.get(faction);
    }

    public static boolean isVillageFaction(@Nullable UUID uuid) {
        return uuid != null && FACTION_TO_VILLAGE.containsKey(uuid);
    }
}

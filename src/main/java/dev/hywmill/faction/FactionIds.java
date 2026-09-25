package dev.hywmill.faction;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic synthetic faction identities. A village's faction UUID is a name-based (v3)
 * UUID of a fixed namespace string plus the village UUID, so it is identical across restarts,
 * machines and ledger resets.
 *
 * <p>PERSISTENT CONTRACT: worlds store these UUIDs in HYW identity markers and in our ledger.
 * The namespace string must never change, and is deliberately not derived from the mod id.
 * Which faction UUIDs belong to the current server is tracked by {@link FactionRegistry}.
 */
public final class FactionIds {
    private static final String NAMESPACE = "hywmill:millenaire_village:";

    private FactionIds() {}

    public static UUID forVillage(UUID villageId) {
        return UUID.nameUUIDFromBytes((NAMESPACE + villageId).getBytes(StandardCharsets.UTF_8));
    }
}

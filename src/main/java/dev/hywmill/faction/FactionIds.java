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
    /** M5 (Option 1): residents' identity. Same persistence contract as NAMESPACE: never change it. */
    private static final String RESIDENT_NAMESPACE = "hywmill:millenaire_residents:";

    private FactionIds() {}

    public static UUID forVillage(UUID villageId) {
        return UUID.nameUUIDFromBytes((NAMESPACE + villageId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The identity carried by a village's Millénaire residents (M5, Option 1). It is distinct from
     * the faction identity the garrison owns, so war and outlaw projections on the faction never
     * make residents relation targets; HywMill keeps the two permanently FRIENDLY.
     */
    public static UUID residentsOf(UUID villageId) {
        return UUID.nameUUIDFromBytes((RESIDENT_NAMESPACE + villageId).getBytes(StandardCharsets.UTF_8));
    }
}

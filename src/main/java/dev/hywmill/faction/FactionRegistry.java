package dev.hywmill.faction;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Village factions known to the current server (from the ledger and from marked residents).
 * One instance per server, owned by {@link dev.hywmill.core.HywMillRuntime}.
 */
public final class FactionRegistry {
    private final Map<UUID, UUID> factionToVillage = new ConcurrentHashMap<>();

    /** Registers a village and returns its faction UUID. */
    public UUID register(UUID villageId) {
        UUID faction = FactionIds.forVillage(villageId);
        factionToVillage.putIfAbsent(faction, villageId);
        return faction;
    }

    @Nullable
    public UUID villageOf(@Nullable UUID faction) {
        return faction == null ? null : factionToVillage.get(faction);
    }

    public boolean isVillageFaction(@Nullable UUID uuid) {
        return uuid != null && factionToVillage.containsKey(uuid);
    }

    public Collection<UUID> factions() {
        return Collections.unmodifiableCollection(factionToVillage.keySet());
    }
}

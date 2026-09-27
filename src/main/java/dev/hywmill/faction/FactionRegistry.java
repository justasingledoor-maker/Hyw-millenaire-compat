package dev.hywmill.faction;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Village identities known to the current server (from the ledger and from marked residents):
 * each village's <b>faction</b> identity (its garrison's owner; wars and outlawry are projected on
 * it) and its <b>resident</b> identity (carried by its Millénaire residents; never HOSTILE). One
 * instance per server, owned by {@link dev.hywmill.core.HywMillRuntime}.
 */
public final class FactionRegistry {
    private final Map<UUID, UUID> factionToVillage = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> residentToVillage = new ConcurrentHashMap<>();

    /** Registers a village (both its identities) and returns its faction UUID. */
    public UUID register(UUID villageId) {
        UUID faction = FactionIds.forVillage(villageId);
        factionToVillage.putIfAbsent(faction, villageId);
        residentToVillage.putIfAbsent(FactionIds.residentsOf(villageId), villageId);
        return faction;
    }

    @Nullable
    public UUID villageOf(@Nullable UUID faction) {
        return faction == null ? null : factionToVillage.get(faction);
    }

    public boolean isVillageFaction(@Nullable UUID uuid) {
        return uuid != null && factionToVillage.containsKey(uuid);
    }

    public boolean isResidentIdentity(@Nullable UUID uuid) {
        return uuid != null && residentToVillage.containsKey(uuid);
    }

    /** Faction or resident identity of a registered village. */
    public boolean isVillageIdentity(@Nullable UUID uuid) {
        return isVillageFaction(uuid) || isResidentIdentity(uuid);
    }

    /** The village a faction or resident identity belongs to, or null. */
    @Nullable
    public UUID villageOfIdentity(@Nullable UUID uuid) {
        if (uuid == null) {
            return null;
        }
        UUID v = factionToVillage.get(uuid);
        return v != null ? v : residentToVillage.get(uuid);
    }

    /** Both identities are village identities of the same village (faction and/or residents). */
    public boolean sameVillage(@Nullable UUID a, @Nullable UUID b) {
        UUID va = villageOfIdentity(a);
        return va != null && va.equals(villageOfIdentity(b));
    }

    public Collection<UUID> factions() {
        return Collections.unmodifiableCollection(factionToVillage.keySet());
    }
}

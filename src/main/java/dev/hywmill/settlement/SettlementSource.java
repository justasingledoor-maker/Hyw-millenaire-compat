package dev.hywmill.settlement;

import dev.hywmill.military.classify.RoleClassifier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-mostly view of a settlement mod (Millénaire in M1). All methods must be cheap enough
 * for the call site noted; none may throw foreign exceptions other than LinkageError.
 */
public interface SettlementSource {
    String name();

    /** Lightweight listing of every known settlement (no expensive computation). */
    List<SettlementRef> list(ServerLevel level);

    /** Full snapshot computed from the settlement mod's own data. Called every ~200 ticks per active village. */
    Optional<SettlementSnapshot> snapshot(ServerLevel level, UUID settlementId);

    Optional<SettlementRef> nearest(ServerLevel level, BlockPos pos, double maxDistance);

    /** Residency of an entity. Called on entity join and on every damage event; must be O(1). */
    Optional<ResidentInfo> residentInfo(Entity entity);

    /** Currently loaded resident entities of a settlement (for the identity sweep). */
    List<Entity> loadedResidents(ServerLevel level, UUID settlementId);

    /** Settlement-mod reputation of a player (Millénaire combined village reputation). */
    int playerReputation(ServerLevel level, UUID settlementId, UUID playerId);

    /**
     * Loaded, living, non-raider residents with their role facts and position. Called by the
     * defense coordinator on scans of a village that is not CALM (every 20 ticks at most).
     */
    List<RosterEntry> defenseRoster(ServerLevel level, UUID settlementId);

    record RosterEntry(UUID id, RoleClassifier.VillagerFacts facts, double x, double y, double z) {}

    /** Human-readable diagnostic lines about loaded residents. */
    List<String> describeResidents(ServerLevel level, UUID settlementId);

    record SettlementRef(UUID id, String name, BlockPos center, boolean active) {}
}

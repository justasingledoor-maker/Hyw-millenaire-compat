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

    /**
     * DEV ONLY ({@code /hywmill dev remove-village}, M3 harness G3-14): removes the settlement from
     * the source's registry the way its own deletion does, so its disappearance can be tested.
     * Returns false if unsupported or unknown.
     */
    default boolean devRemove(ServerLevel level, UUID settlementId) {
        return false;
    }

    record SettlementRef(UUID id, String name, BlockPos center, boolean active) {}

    /**
     * Raid state of a settlement as its own mod keeps it (M4). {@code target} is the settlement it
     * is planning or conducting a raid against (null if none); {@code raidStart} is 0 while only
     * planning; {@code performed}/{@code suffered} are the lengths of its raid histories (they grow
     * by one when a raid ends).
     */
    record RaidInfo(@javax.annotation.Nullable UUID target, long planningStart, long raidStart, boolean underAttack,
                    int performed, int suffered) {}

    /** Raid state, read-only; empty if unknown or unsupported. Cheap (field reads). */
    default Optional<RaidInfo> raidInfo(ServerLevel level, UUID settlementId) {
        return Optional.empty();
    }

    /** Where the settlement mod lands raiders attacking {@code targetId} from {@code attackerCenter}; empty if unsupported. */
    default Optional<BlockPos> raidLandingPoint(ServerLevel level, UUID targetId, BlockPos attackerCenter) {
        return Optional.empty();
    }

    /**
     * DEV ONLY (M4-0 spike, {@code /hywmill dev negate}): runs the settlement mod's own player
     * deletion path (Millénaire: the Wand of Negation's confirmed deletion) for the settlement.
     */
    default boolean devNegate(ServerLevel level, UUID settlementId, net.minecraft.server.level.ServerPlayer player) {
        return false;
    }

    /** A military building's standing point: its defending position if near ground level, else its path anchor (else its origin). */
    record LayoutPoint(dev.hywmill.military.classify.BuildingRole role, BlockPos pos) {}

    /**
     * Operational building geometry for M4 duties. {@code military}: buildings with a military
     * role; {@code anchors}: path anchors of every operational building (patrol geometry);
     * {@code townhall}: the townhall's anchor (or the centre). Read on duty (re)planning only.
     */
    record Layout(BlockPos center, BlockPos defendingPos, BlockPos townhall, int radius, List<LayoutPoint> military,
                  List<BlockPos> anchors) {
        /** Changes whenever the geometry duties are planned from changes. */
        public long key() {
            long h = center.asLong() * 31 + defendingPos.asLong() * 17 + townhall.asLong() * 13 + radius;
            for (LayoutPoint p : military) {
                h = h * 1_000_003L + p.pos().asLong() * 7 + p.role().ordinal();
            }
            for (BlockPos p : anchors) {
                h = h * 1_000_003L + p.asLong();
            }
            return h;
        }
    }

    /** Current layout, in the settlement mod's own (stable) building order; empty if unknown or unsupported. */
    default Optional<Layout> layout(ServerLevel level, UUID settlementId) {
        return Optional.empty();
    }
}

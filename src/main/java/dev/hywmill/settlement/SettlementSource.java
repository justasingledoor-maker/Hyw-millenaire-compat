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

    /**
     * Living raiders the settlement mod has sent against {@code settlementId} inside {@code box} (Millénaire: raid clones
     * registered to the target village). Loaded entities only; empty if unsupported.
     */
    default List<net.minecraft.world.entity.LivingEntity> raidersAgainst(ServerLevel level, UUID settlementId, net.minecraft.world.phys.AABB box) {
        return List.of();
    }

    /** The player's money in the settlement mod's currency (Millénaire: deniers, purses included); -1 if unsupported. */
    default int playerMoney(net.minecraft.world.entity.player.Player player) {
        return -1;
    }

    /** Takes {@code amount} from the player; false (nothing taken) if they have less or it is unsupported. */
    default boolean takeMoney(net.minecraft.world.entity.player.Player player, int amount) {
        return false;
    }

    /** Gives {@code amount} to the player (refunds). */
    default void giveMoney(net.minecraft.world.entity.player.Player player, int amount) {
    }

    /** Current layout, in the settlement mod's own (stable) building order; empty if unknown or unsupported. */
    default Optional<Layout> layout(ServerLevel level, UUID settlementId) {
        return Optional.empty();
    }

    // ---- M5 politics ----

    /** Relation of village {@code a} towards {@code b} (Millénaire −100..100), empty if unknown. */
    default java.util.OptionalInt villageRelation(ServerLevel level, UUID a, UUID b) {
        return java.util.OptionalInt.empty();
    }

    /** Whether the player knows the village: the settlement mod's discovery, or the player has a reputation record with it. */
    default boolean discovered(ServerLevel level, UUID playerId, UUID settlementId) {
        return false;
    }

    /**
     * Appends a line to the village's history (Millénaire's chronicle). Millénaire keeps it for the
     * current server session only (M5-0); HywMill's own chronicle is the persisted record.
     */
    default void recordHistory(ServerLevel level, UUID settlementId, String text) {
    }

    /**
     * Takes {@code amount} of the player's combined reputation with the village (M5-3 weregild). The
     * adapter knows how the combined value is made up. Returns the combined reputation afterwards, or
     * empty if the village is unknown (then nothing was taken).
     */
    default java.util.OptionalInt takeReputation(ServerLevel level, UUID settlementId, UUID playerId, int amount) {
        return java.util.OptionalInt.empty();
    }

    /** Millénaire diplomacy points the player has with the village (M5-4), empty if unsupported. */
    default java.util.OptionalInt diplomacyPoints(ServerLevel level, UUID settlementId, UUID playerId) {
        return java.util.OptionalInt.empty();
    }

    /** Spends one Millénaire diplomacy point of the player with the village; false if none (M5-4). */
    default boolean consumeDiplomacyPoint(ServerLevel level, UUID settlementId, UUID playerId) {
        return false;
    }

    /** Changes the relation between two villages by {@code delta} in both directions, through Millénaire (M5-4). */
    default void adjustVillageRelation(ServerLevel level, UUID a, UUID b, int delta) {
    }

    /** Sets the relation between two villages in both directions, through Millénaire (M5-4 truce floor). */
    default void setVillageRelation(ServerLevel level, UUID a, UUID b, int value) {
    }

    /** Gives the player reputation with the village (negative takes it; M5-4 backfire/exposure). Returns the new combined value. */
    default java.util.OptionalInt adjustReputation(ServerLevel level, UUID settlementId, UUID playerId, int delta) {
        return java.util.OptionalInt.empty();
    }
}

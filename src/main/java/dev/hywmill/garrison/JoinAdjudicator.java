package dev.hywmill.garrison;

import dev.hywmill.garrison.tag.GarrisonTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Decides what to do with a tagged garrison entity that joins a level (spawned, chunk-loaded or
 * changed dimension). Pure; mutates the roster for BIND/ADOPT/RELEASE. The rule prefers losing
 * one unit to duplicating one: any claimant that cannot be the slot's single bound entity is a
 * duplicate and must not enter the world.
 */
public final class JoinAdjudicator {
    private JoinAdjudicator() {}

    public enum Decision {
        /** The slot's bound entity (re)loaded. */
        BIND,
        /** Stale roster (entity saved after the roster): the slot adopts this entity. */
        ADOPT,
        /** Not the slot's entity: cancel the join (the entity is dropped). */
        DUPLICATE,
        /** The unit's owner is no longer the village faction: not ours; strip the tag, keep the entity. */
        RELEASE,
        /** The village is gone (or unknown): apply the orphan policy. */
        ORPHAN
    }

    /**
     * @param roster  the tagged village's roster, or null if the village has no record
     * @param owner   the entity's current HYW owner
     */
    public static Decision onJoin(@Nullable GarrisonRoster roster, GarrisonTag tag, UUID entityUuid, @Nullable UUID owner,
                                  UUID faction, long tick) {
        if (roster == null) {
            return Decision.ORPHAN;
        }
        RosterEntry e = roster.entry(tag.rosterId());
        if (!faction.equals(owner)) {
            if (e != null && !e.state().terminal() && entityUuid.equals(e.entityUuid)) {
                e.transition(UnitState.LOST, tick, LossReason.CAPTURED);
                roster.totals.lost++;
            }
            return Decision.RELEASE;
        }
        if (!entityUuid.equals(tag.expectedEntityUuid())) {
            return duplicate(roster);
        }
        if (e == null) {
            // a slot id issued by this roster that no longer exists (retired): never resurrect it
            return roster.goneSinceTick >= 0 ? Decision.ORPHAN : duplicate(roster);
        }
        if (e.state().terminal()) {
            return e.lossReason() == LossReason.VILLAGE_GONE ? Decision.ORPHAN : duplicate(roster);
        }
        if (entityUuid.equals(e.entityUuid)) {
            if (e.state() == UnitState.MISSING) {
                e.transition(UnitState.RECOVERED, tick);
                roster.totals.recovered++;
            }
            return Decision.BIND;
        }
        if (e.state() == UnitState.RECRUITED && tag.generation() >= e.generation) {
            e.entityUuid = entityUuid;
            e.generation = tag.generation();
            e.transition(UnitState.RECOVERED, tick);
            roster.totals.recovered++;
            return Decision.ADOPT;
        }
        return duplicate(roster);
    }

    private static Decision duplicate(GarrisonRoster roster) {
        roster.totals.duplicatesDiscarded++;
        return Decision.DUPLICATE;
    }
}

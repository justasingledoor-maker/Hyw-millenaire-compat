package dev.hywmill.garrison;

import dev.hywmill.garrison.JoinAdjudicator.Decision;
import dev.hywmill.garrison.tag.GarrisonTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JoinAdjudicatorTest {
    static final UUID VILLAGE = GarrisonLifecycleTest.VILLAGE;
    static final UUID FACTION = ReconcilerTest.FACTION;

    final GarrisonRoster roster = new GarrisonRoster(0);

    RosterEntry spawned() {
        RosterEntry e = roster.recruit(VILLAGE, "archer", "hundred_years_war:archer", 1, 0, true);
        roster.beginSpawn(VILLAGE, e, 0);
        roster.spawned(e, 1, 0);
        return e;
    }

    static GarrisonTag tag(RosterEntry e, int gen) {
        return new GarrisonTag(VILLAGE, e.rosterId, gen);
    }

    Decision join(GarrisonTag t, UUID uuid, UUID owner) {
        return JoinAdjudicator.onJoin(roster, t, uuid, owner, FACTION, 100);
    }

    @Test
    void boundEntityReloadingBinds() {
        RosterEntry e = spawned();
        assertEquals(Decision.BIND, join(tag(e, 1), e.entityUuid, FACTION));
    }

    @Test
    void missingEntityReloadingIsRecovered() {
        RosterEntry e = spawned();
        e.transition(UnitState.MISSING, 50);
        assertEquals(Decision.BIND, join(tag(e, 1), e.entityUuid, FACTION));
        assertEquals(UnitState.RECOVERED, e.state());
        assertEquals(1, roster.totals.recovered);
    }

    @Test
    void staleRosterAdoptsTheSavedEntity() {
        RosterEntry e = spawned();
        UUID real = e.entityUuid;
        e.devRewind(60); // roster saved before the spawn: RECRUITED, generation 0
        assertEquals(Decision.ADOPT, join(tag(e, 1), real, FACTION));
        assertEquals(real, e.entityUuid);
        assertEquals(1, e.generation);
        assertEquals(UnitState.RECOVERED, e.state());
    }

    @Test
    void claimantWithDifferentUuidIsDuplicate() {
        RosterEntry e = spawned();
        GarrisonTag t = tag(e, 1);
        UUID forged = UUID.randomUUID();
        assertEquals(Decision.DUPLICATE, join(t, forged, FACTION));
        assertEquals(1, roster.totals.duplicatesDiscarded);
    }

    @Test
    void olderGenerationWhileBoundToNewerIsDuplicate() {
        RosterEntry e = spawned();
        e.devRewind(1);                   // RECRUITED at generation 0 ...
        e.generation = 1;                 // ... after a first spawn whose entity was lost
        roster.beginSpawn(VILLAGE, e, 2); // second generation spawned
        assertEquals(2, e.generation);
        GarrisonTag old = tag(e, 1);
        assertEquals(Decision.DUPLICATE, join(old, old.expectedEntityUuid(), FACTION));
        assertEquals(UnitState.SPAWNED, e.state());
    }

    @Test
    void recruitedSlotRejectsLowerGeneration() {
        RosterEntry e = spawned();
        e.devRewind(1);
        e.generation = 2;         // RECRUITED at generation 2
        GarrisonTag old = tag(e, 1);
        assertEquals(Decision.DUPLICATE, join(old, old.expectedEntityUuid(), FACTION));
        assertEquals(UnitState.RECRUITED, e.state());
    }

    @Test
    void deadOrLostSlotNeverTakesAnEntityBack() {
        RosterEntry e = spawned();
        UUID uuid = e.entityUuid;
        e.transition(UnitState.LOST, 10, LossReason.MISSING_TIMEOUT);
        assertEquals(Decision.DUPLICATE, join(tag(e, 1), uuid, FACTION));
        RosterEntry d = spawned();
        UUID u2 = d.entityUuid;
        d.transition(UnitState.DEAD, 10, LossReason.KILLED);
        assertEquals(Decision.DUPLICATE, join(tag(d, 1), u2, FACTION));
    }

    @Test
    void retiredSlotOfLiveVillageIsDuplicate() {
        GarrisonTag t = new GarrisonTag(VILLAGE, GarrisonRoster.rosterId(VILLAGE, 77), 1);
        assertEquals(Decision.DUPLICATE, join(t, t.expectedEntityUuid(), FACTION));
    }

    @Test
    void goneVillageIsOrphan() {
        RosterEntry e = spawned();
        Reconciler.villageGone(roster, false, 0, GarrisonSettings.DEFAULTS);
        Reconciler.villageGone(roster, false, GarrisonSettings.DEFAULTS.villageGoneGrace(), GarrisonSettings.DEFAULTS);
        assertEquals(Decision.ORPHAN, join(tag(e, 1), e.entityUuid, FACTION));
        assertEquals(Decision.ORPHAN, JoinAdjudicator.onJoin(null, tag(e, 1), e.entityUuid, FACTION, FACTION, 0));
    }

    @Test
    void newOwnerIsReleasedAndSlotLostCaptured() {
        RosterEntry e = spawned();
        assertEquals(Decision.RELEASE, join(tag(e, 1), e.entityUuid, UUID.randomUUID()));
        assertEquals(LossReason.CAPTURED, e.lossReason());
        assertEquals(Decision.RELEASE, join(tag(e, 1), e.entityUuid, null));
    }
}

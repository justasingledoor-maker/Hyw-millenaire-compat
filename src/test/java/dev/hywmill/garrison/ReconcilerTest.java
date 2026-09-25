package dev.hywmill.garrison;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReconcilerTest {
    static final UUID VILLAGE = GarrisonLifecycleTest.VILLAGE;
    static final UUID FACTION = UUID.fromString("1aa3e6cf-84d4-3b7b-8e61-f06352a032d8");
    static final GarrisonSettings S = GarrisonSettings.DEFAULTS;

    final GarrisonRoster roster = new GarrisonRoster(0);
    final Map<UUID, Reconciler.Observation> world = new HashMap<>();

    RosterEntry spawned(long tick) {
        RosterEntry e = roster.recruit(VILLAGE, "spear_man", "hundred_years_war:spear_man", 1, tick, true);
        roster.beginSpawn(VILLAGE, e, tick);
        roster.spawned(e, 1, tick);
        return e;
    }

    List<Reconciler.Event> run(long tick, boolean settled) {
        return Reconciler.reconcile(roster, FACTION, world::get, tick, settled, S);
    }

    @Test
    void spawnedUnitSeenBecomesGarrisoned() {
        RosterEntry e = spawned(0);
        world.put(e.entityUuid, new Reconciler.Observation(FACTION, 1, 2, 3));
        List<Reconciler.Event> ev = run(200, true);
        assertEquals(UnitState.GARRISONED, e.state());
        assertEquals(Reconciler.Kind.CONFIRMED, ev.get(0).kind());
        assertEquals(200, e.lastSeenTick);
    }

    @Test
    void unloadedUnitIsNotMissingBeforeGrace() {
        RosterEntry e = spawned(0);
        run(S.missingGrace() - 1, true);
        assertEquals(UnitState.SPAWNED, e.state());
    }

    @Test
    void unobservedAfterGraceIsMissingNeverDead() {
        RosterEntry e = spawned(0);
        run(S.missingGrace(), true);
        assertEquals(UnitState.MISSING, e.state());
        assertEquals(0, roster.totals.killed);
    }

    @Test
    void notSettledNeverMarksMissing() {
        RosterEntry e = spawned(0);
        run(S.missingGrace() * 10, false);
        assertEquals(UnitState.SPAWNED, e.state());
    }

    @Test
    void missingBecomesLostOnlyAfterTimeout() {
        RosterEntry e = spawned(0);
        run(S.missingGrace(), true);
        run(S.missingGrace() + S.lostTimeout() - 1, true);
        assertEquals(UnitState.MISSING, e.state());
        List<Reconciler.Event> ev = run(S.missingGrace() + S.lostTimeout(), true);
        assertEquals(UnitState.LOST, e.state());
        assertEquals(LossReason.MISSING_TIMEOUT, e.lossReason());
        assertEquals(Reconciler.Kind.LOST_TIMEOUT, ev.get(0).kind());
    }

    @Test
    void missingUnitSeenAgainIsRecoveredThenGarrisoned() {
        RosterEntry e = spawned(0);
        run(S.missingGrace(), true);
        world.put(e.entityUuid, new Reconciler.Observation(FACTION, 0, 0, 0));
        run(S.missingGrace() + 200, true);
        assertEquals(UnitState.RECOVERED, e.state());
        assertEquals(1, roster.totals.recovered);
        run(S.missingGrace() + 400, true);
        assertEquals(UnitState.GARRISONED, e.state());
    }

    @Test
    void ownerMismatchIsCapturedAndNeverRewritten() {
        RosterEntry e = spawned(0);
        UUID thief = UUID.randomUUID();
        world.put(e.entityUuid, new Reconciler.Observation(thief, 0, 0, 0));
        List<Reconciler.Event> ev = run(200, true);
        assertEquals(UnitState.LOST, e.state());
        assertEquals(LossReason.CAPTURED, e.lossReason());
        assertEquals(Reconciler.Kind.CAPTURED, ev.get(0).kind());
        assertEquals(thief, world.get(e.entityUuid).owner()); // the observation (world) is untouched
    }

    @Test
    void staleRecruitedSlotAdoptsItsLoadedEntity() {
        RosterEntry e = spawned(0);
        UUID uuid = e.entityUuid;
        e.devRewind(10); // stale save: RECRUITED at generation 0, but generation 1 was spawned
        world.put(uuid, new Reconciler.Observation(FACTION, 0, 0, 0));
        List<Reconciler.Event> ev = run(200, true);
        assertEquals(Reconciler.Kind.ADOPTED, ev.get(0).kind());
        assertEquals(uuid, e.entityUuid);
        assertEquals(UnitState.RECOVERED, e.state());
        assertEquals(1, e.generation);
    }

    @Test
    void recruitedEntriesAreNeverTouched() {
        RosterEntry e = roster.recruit(VILLAGE, "militia", "hundred_years_war:militia", 0, 0, true);
        assertTrue(run(1_000_000, true).isEmpty());
        assertEquals(UnitState.RECRUITED, e.state());
    }

    @Test
    void inactiveTimeIsExcludedFromTimers() {
        RosterEntry e = spawned(0);
        roster.lastAccrualTick = 0;
        long excluded = Reconciler.excludeInactive(roster, 100_000, S);
        assertEquals(100_000 - S.maxActiveStep(), excluded);
        assertEquals(excluded, e.stateSinceTick);
        run(100_000, true); // only maxActiveStep of active time has passed since the spawn
        assertEquals(UnitState.SPAWNED, e.state());
    }

    @Test
    void villageGoneOnlyAfterGrace() {
        RosterEntry e = spawned(0);
        assertTrue(Reconciler.villageGone(roster, false, 1000, S).isEmpty());
        assertEquals(1000, roster.goneSinceTick);
        assertTrue(Reconciler.villageGone(roster, false, 1000 + S.villageGoneGrace() - 1, S).isEmpty());
        assertEquals(1, Reconciler.villageGone(roster, false, 1000 + S.villageGoneGrace(), S).size());
        assertEquals(LossReason.VILLAGE_GONE, e.lossReason());
    }

    @Test
    void villageBackWithinGraceResetsTheClock() {
        RosterEntry e = spawned(0);
        Reconciler.villageGone(roster, false, 1000, S);
        Reconciler.villageGone(roster, true, 2000, S);
        assertEquals(-1, roster.goneSinceTick);
        assertTrue(Reconciler.villageGone(roster, false, 1000 + S.villageGoneGrace() + 5, S).isEmpty());
        assertEquals(UnitState.SPAWNED, e.state());
    }
}

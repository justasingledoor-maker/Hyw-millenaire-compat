package dev.hywmill.garrison;

import dev.hywmill.garrison.tag.GarrisonTag;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GarrisonLifecycleTest {
    static final UUID VILLAGE = UUID.fromString("f7edd961-3e4b-4b15-8583-ecbdac270e4a");

    static RosterEntry entry() {
        return new RosterEntry(UUID.randomUUID(), "spear_man", "hundred_years_war:spear_man", 1, 100, true);
    }

    @ParameterizedTest
    @EnumSource(value = UnitState.class, names = {"DEAD", "LOST"})
    void terminalStatesHaveNoExit(UnitState terminal) {
        for (UnitState to : UnitState.values()) {
            assertFalse(terminal.canTransition(to), terminal + " -> " + to);
        }
        assertTrue(terminal.terminal());
    }

    @Test
    void allowedTransitionTable() {
        assertTrue(UnitState.RECRUITED.canTransition(UnitState.SPAWNED));
        assertTrue(UnitState.RECRUITED.canTransition(UnitState.RECOVERED)); // adoption of a stale-roster entity
        assertFalse(UnitState.RECRUITED.canTransition(UnitState.GARRISONED));
        assertFalse(UnitState.RECRUITED.canTransition(UnitState.DEAD));
        assertTrue(UnitState.SPAWNED.canTransition(UnitState.RECRUITED)); // spawn revert only
        assertTrue(UnitState.GARRISONED.canTransition(UnitState.DEPLOYED));
        assertFalse(UnitState.GARRISONED.canTransition(UnitState.RETURNING));
        assertFalse(UnitState.GARRISONED.canTransition(UnitState.RECRUITED));
        assertTrue(UnitState.DEPLOYED.canTransition(UnitState.RETURNING));
        assertTrue(UnitState.RETURNING.canTransition(UnitState.GARRISONED));
        assertTrue(UnitState.MISSING.canTransition(UnitState.RECOVERED));
        assertFalse(UnitState.MISSING.canTransition(UnitState.GARRISONED));
        assertTrue(UnitState.RECOVERED.canTransition(UnitState.GARRISONED));
    }

    @Test
    void everyLiveStateCanDieOrBeLostExceptRecruited() {
        Set<UnitState> live = EnumSet.complementOf(EnumSet.of(UnitState.DEAD, UnitState.LOST, UnitState.RECRUITED));
        for (UnitState s : live) {
            assertTrue(s.canTransition(UnitState.LOST), s.name());
            assertTrue(s.canTransition(UnitState.DEAD), s.name());
        }
        assertTrue(UnitState.RECRUITED.canTransition(UnitState.LOST));
    }

    @Test
    void illegalTransitionThrows() {
        RosterEntry e = entry();
        assertThrows(IllegalStateException.class, () -> e.transition(UnitState.GARRISONED, 1));
        assertEquals(UnitState.RECRUITED, e.state());
    }

    @Test
    void terminalTransitionNeedsReasonAndRecordsIt() {
        RosterEntry e = entry();
        assertThrows(IllegalArgumentException.class, () -> e.transition(UnitState.LOST, 1));
        e.transition(UnitState.LOST, 5, LossReason.REMOVED);
        assertEquals(LossReason.REMOVED, e.lossReason());
        assertEquals(5, e.stateSinceTick);
    }

    @Test
    void rosterIdsAreDeterministicAndDistinct() {
        GarrisonRoster a = new GarrisonRoster(0);
        GarrisonRoster b = new GarrisonRoster(0);
        RosterEntry a1 = a.recruit(VILLAGE, "militia", "hundred_years_war:militia", 0, 0, false);
        RosterEntry a2 = a.recruit(VILLAGE, "militia", "hundred_years_war:militia", 0, 0, false);
        RosterEntry b1 = b.recruit(VILLAGE, "militia", "hundred_years_war:militia", 0, 0, false);
        assertEquals(a1.rosterId, b1.rosterId);
        assertNotEquals(a1.rosterId, a2.rosterId);
        assertEquals(2, a.nextSeq);
        assertEquals(2, a.totals.recruited);
    }

    @Test
    void beginSpawnIsRosterFirstWithDeterministicUuid() {
        GarrisonRoster r = new GarrisonRoster(0);
        RosterEntry e = r.recruit(VILLAGE, "archer", "hundred_years_war:archer", 2, 0, true);
        GarrisonTag tag = r.beginSpawn(VILLAGE, e, 10);
        assertEquals(UnitState.SPAWNED, e.state());
        assertEquals(1, e.generation);
        assertEquals(GarrisonTag.entityUuid(e.rosterId, 1), e.entityUuid);
        assertEquals(e.entityUuid, tag.expectedEntityUuid());
        assertEquals(GarrisonTag.entityUuid(e.rosterId, 1), GarrisonTag.entityUuid(e.rosterId, 1));
        assertNotEquals(GarrisonTag.entityUuid(e.rosterId, 1), GarrisonTag.entityUuid(e.rosterId, 2));
    }

    @Test
    void revertedSpawnRestoresGenerationSoRetriesReuseTheUuid() {
        GarrisonRoster r = new GarrisonRoster(0);
        RosterEntry e = r.recruit(VILLAGE, "archer", "hundred_years_war:archer", 2, 0, true);
        r.beginSpawn(VILLAGE, e, 10);
        r.revertSpawn(e, 10);
        assertEquals(UnitState.RECRUITED, e.state());
        assertNull(e.entityUuid);
        assertEquals(0, e.generation);
        r.beginSpawn(VILLAGE, e, 20);
        assertEquals(1, e.generation);
        assertEquals(GarrisonTag.entityUuid(e.rosterId, 1), e.entityUuid);
        assertEquals(0, r.totals.spawned);
    }

    @Test
    void nbtRoundTrip() {
        GarrisonRoster r = new GarrisonRoster(500);
        r.startingGranted = true;
        r.levyPoints = 3.25;
        r.paused = true;
        r.goneSinceTick = 900;
        RosterEntry e = r.recruit(VILLAGE, "shieldman", "hundred_years_war:shieldman", 2, 510, true);
        r.beginSpawn(VILLAGE, e, 520);
        r.spawned(e, 2, 520);
        e.seen(530, 1, 64, -3);
        RosterEntry d = r.recruit(VILLAGE, "militia", "hundred_years_war:militia", 0, 540, false);
        d.transition(UnitState.LOST, 541, LossReason.ADMIN);
        CompoundTag t = r.save();
        GarrisonRoster back = GarrisonRoster.load(t, 9999);
        assertEquals(t, back.save());
        assertTrue(back.startingGranted);
        assertTrue(back.paused);
        assertEquals(3.25, back.levyPoints);
        assertEquals(500, back.lastAccrualTick);
        assertEquals(UnitState.SPAWNED, back.entries().get(0).state());
        assertEquals(e.entityUuid, back.entries().get(0).entityUuid);
        assertEquals(LossReason.ADMIN, back.entries().get(1).lossReason());
        assertFalse(back.entries().get(1).paid);
        assertEquals(2, back.nextSeq);
    }

    @Test
    void pruneDropsOnlyOldTerminalEntries() {
        GarrisonRoster r = new GarrisonRoster(0);
        RosterEntry a = r.recruit(VILLAGE, "militia", "hundred_years_war:militia", 0, 0, true);
        RosterEntry b = r.recruit(VILLAGE, "militia", "hundred_years_war:militia", 0, 0, true);
        r.recruit(VILLAGE, "militia", "hundred_years_war:militia", 0, 0, true);
        a.transition(UnitState.LOST, 100, LossReason.ADMIN);
        b.transition(UnitState.LOST, 20000, LossReason.ADMIN);
        assertEquals(1, r.pruneTerminal(24100, 24000));
        assertEquals(2, r.entries().size());
        assertEquals(2, r.live() + 1);
    }
}

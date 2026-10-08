package dev.hywmill.garrison.service;

import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Post-M5 repair: a home defender stowed while deployed against a threat (wounded in a siege) kept DEPLOYED with no entity,
 * counted as a defender and never came back. It now stands down, stowed at home, and is brought back like any garrisoned slot.
 */
class StandDownTest {
    static final UUID VILLAGE = UUID.fromString("f7edd961-3e4b-4b15-8583-ecbdac270e4a");

    static RosterEntry unit(GarrisonRoster r, long tick) {
        RosterEntry e = r.recruit(VILLAGE, "spear_man", "hundred_years_war:spear_man", 2, tick, false);
        e.transition(UnitState.SPAWNED, tick);
        e.transition(UnitState.GARRISONED, tick);
        e.assignedDuty = Duty.SENTRY;
        e.duty = Duty.SENTRY;
        return e;
    }

    @Test
    void stowedHomeDefendersStandDown() {
        VillageRecord v = new VillageRecord(VILLAGE, UUID.randomUUID());
        v.center = new BlockPos(0, 64, 0);
        GarrisonRoster r = GarrisonService.roster(v, 100);
        RosterEntry ghost = unit(r, 100);
        ghost.transition(UnitState.DEPLOYED, 200);
        ghost.duty = Duty.DEFENSE; // deployed against the host, then wounded and carried off the field (stowed)
        ghost.entityUuid = null;
        RosterEntry returning = unit(r, 100);
        returning.transition(UnitState.DEPLOYED, 200);
        returning.transition(UnitState.RETURNING, 300);
        returning.duty = Duty.RETURNING;
        returning.entityUuid = null;
        RosterEntry host = unit(r, 100);
        host.transition(UnitState.DEPLOYED, 200);
        host.duty = Duty.SIEGE; // on a siege elsewhere: away, left alone
        host.entityUuid = null;
        RosterEntry fighting = unit(r, 100);
        fighting.transition(UnitState.DEPLOYED, 200);
        fighting.duty = Duty.DEFENSE;
        fighting.entityUuid = UUID.randomUUID(); // in the world: the threat response handles it

        assertEquals(2, GarrisonService.standDown(r, 400));
        assertEquals(UnitState.GARRISONED, ghost.state());
        assertEquals(Duty.SENTRY, ghost.duty);
        assertEquals(UnitState.GARRISONED, returning.state());
        assertEquals(Duty.SENTRY, returning.duty);
        assertEquals(UnitState.DEPLOYED, host.state());
        assertEquals(Duty.SIEGE, host.duty);
        assertEquals(UnitState.DEPLOYED, fighting.state());
        assertEquals(0, GarrisonService.standDown(r, 500), "idempotent");
        assertTrue(SiegeService.homeDefenders(v).contains(ghost), "still a defender at home");
        assertFalse(SiegeService.homeDefenders(v).contains(host));
    }
}

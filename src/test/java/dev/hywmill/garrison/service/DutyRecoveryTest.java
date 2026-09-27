package dev.hywmill.garrison.service;

import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.duty.DutyMotion;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Approved M4 fix (G4-2): a trapped home-duty unit whose target is beyond the 40-block recovery ceiling is recovered to
 * the nearest reachable waypoint towards its duty target, bounded; the case within 40 blocks is unchanged; lent units are
 * never recovered by the duty service. The case is the one observed on the server: unit at (643, 79, 613), post (615, 80, 583).
 */
class DutyRecoveryTest {
    static final double UX = 643.5, UY = 79, UZ = 613.5;
    static final BlockPos POST = new BlockPos(615, 80, 583);
    static final BlockPos HOME = new BlockPos(608, 79, 592); // the observed hop, 40.8 blocks away
    /** Every point is standable and loaded (at its own height). */
    static final Function<BlockPos, BlockPos> OPEN = p -> p;

    @Test
    void trappedSentryBeyondTheCeilingIsRecovered() {
        assertTrue(DutyMotion.horizontal(UX, UZ, HOME) > DutyService.UNSTICK_MAX, "precondition: the old recovery refused this spot");
        BlockPos r = DutyService.recoverySpot(UX, UY, UZ, HOME, POST, OPEN);
        assertNotNull(r, "previously the unit stayed trapped forever");
        assertTrue(DutyMotion.horizontal(UX, UZ, r) <= DutyService.UNSTICK_MAX, "unstick's own ceiling still accepts it");
    }

    @Test
    void recoveryMovesTowardsTheDutyTarget() {
        BlockPos r = DutyService.recoverySpot(UX, UY, UZ, HOME, POST, OPEN);
        double before = DutyMotion.horizontal(UX, UZ, POST);
        double after = DutyMotion.horizontal(r.getX() + 0.5, r.getZ() + 0.5, POST);
        assertTrue(after < before, "closer to the post: " + before + " -> " + after);
        // on the line to the post: the nearest-to-target waypoint (32 blocks) is chosen first
        assertEquals(DutyMotion.hop(UX, UY, UZ, POST, 32), r);
    }

    @Test
    void relocationIsBoundedWhateverTheTarget() {
        for (BlockPos far : List.of(new BlockPos(643 + 1000, 79, 613), new BlockPos(643, 79, 613 - 5000), new BlockPos(643 - 41, 79, 613 + 41))) {
            BlockPos r = DutyService.recoverySpot(UX, UY, UZ, null, far, OPEN);
            assertNotNull(r);
            assertTrue(DutyMotion.horizontal(UX, UZ, r) <= DutyService.RECOVERY_STEPS[0] + 3, "bounded: " + r);
        }
        // a stand() that pushes the candidate far away (e.g. a wrong surface) is not accepted
        Function<BlockPos, BlockPos> wild = p -> p.offset(30, 0, 30);
        BlockPos r = DutyService.recoverySpot(UX, UY, UZ, HOME, POST, wild);
        assertTrue(r == null || DutyMotion.horizontal(UX, UZ, r) <= DutyService.RECOVERY_STEPS[0] + 3);
    }

    @Test
    void unloadedOrNonStandableCandidatesAreRejected() {
        List<BlockPos> asked = new ArrayList<>();
        // the 32- and 24-block waypoints are unloaded or not standable (null); the 16-block one is fine
        BlockPos w16 = DutyMotion.hop(UX, UY, UZ, POST, 16);
        Function<BlockPos, BlockPos> partial = p -> {
            asked.add(p);
            return p.equals(w16) ? p : null;
        };
        assertEquals(w16, DutyService.recoverySpot(UX, UY, UZ, HOME, POST, partial));
        assertEquals(3, asked.size(), "32, 24 rejected, 16 accepted; deterministic order");
        assertNull(DutyService.recoverySpot(UX, UY, UZ, HOME, POST, p -> null), "nothing qualifies: the unit holds, as before");
    }

    @Test
    void withinTheCeilingTheBehaviourIsUnchanged() {
        BlockPos nearTarget = new BlockPos(630, 79, 600);
        BlockPos spot = new BlockPos(631, 79, 601);
        List<BlockPos> asked = new ArrayList<>();
        assertSame(spot, DutyService.recoverySpot(UX, UY, UZ, spot, nearTarget, p -> {
            asked.add(p);
            return p;
        }), "the old spot, exactly");
        assertTrue(asked.isEmpty(), "no waypoint search within 40 blocks");
        assertNull(DutyService.recoverySpot(UX, UY, UZ, null, nearTarget, OPEN), "no spot within 40: nothing new is tried (as before)");
    }

    @Test
    void lentAndRaidingUnitsAreNeverMovedOrRecoveredByDuties() {
        GarrisonRoster r = new GarrisonRoster(0);
        UUID village = UUID.randomUUID();
        RosterEntry e = r.recruit(village, "spear_man", "hundred_years_war:spear_man", 1, 0, true);
        r.beginSpawn(village, e, 0);
        r.spawned(e, 1, 0);
        e.transition(UnitState.GARRISONED, 0);
        e.duty = Duty.SENTRY;
        assertTrue(DutyService.movedByDuties(e));
        e.duty = Duty.DETACHED;
        assertFalse(DutyService.movedByDuties(e), "a lent soldier is not moved or recovered by the duty service");
        e.duty = Duty.RAID;
        assertFalse(DutyService.movedByDuties(e));
        e.duty = Duty.SENTRY;
        e.transition(UnitState.DEPLOYED, 1);
        assertFalse(DutyService.movedByDuties(e), "M2-deployed units are the deployment's, as before");
    }
}

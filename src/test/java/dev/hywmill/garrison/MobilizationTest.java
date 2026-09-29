package dev.hywmill.garrison;

import dev.hywmill.military.MilitaryTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 wartime mobilization: the pure rules. */
class MobilizationTest {
    @Test
    void onlyNonStrongholdVillagesMobilize() {
        assertTrue(Mobilization.mobilizes(MilitaryTier.WATCH, false));
        assertTrue(Mobilization.mobilizes(MilitaryTier.GUARD_POST, false));
        assertTrue(Mobilization.mobilizes(MilitaryTier.GARRISON, false));
        assertFalse(Mobilization.mobilizes(MilitaryTier.STRONGHOLD, false));
        assertFalse(Mobilization.mobilizes(MilitaryTier.NONE, false));
        assertFalse(Mobilization.mobilizes(MilitaryTier.GARRISON, true), "lone buildings keep what they have");
    }

    @Test
    void fillsTheCurrentTargetNotTheTierCap() {
        assertEquals(4, Mobilization.count(36, 40, 48), "36/40 with a tier cap of 48: 4 raised");
        assertEquals(0, Mobilization.count(40, 40, 48));
        assertEquals(0, Mobilization.count(45, 40, 48), "above target: none");
        assertEquals(2, Mobilization.count(22, 30, 24), "never past the tier cap");
    }

    @Test
    void freshTroopsAreASetBelowTheRegularsButNeverClubs() {
        assertEquals(1, Mobilization.equipmentLevel(2, 1, 1), "garrison regulars 2: levies 1");
        assertEquals(1, Mobilization.equipmentLevel(1, 1, 1));
        assertEquals(1, Mobilization.equipmentLevel(0, 1, 1), "a watch's clubs are not handed out");
    }
}

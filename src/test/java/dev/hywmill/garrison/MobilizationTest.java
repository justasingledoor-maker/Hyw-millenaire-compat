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

    @Test
    void leviesLeanOnShieldmenAndSpearmen() {
        java.util.Map<String, Integer> w = Mobilization.weights(java.util.Map.of("militia", 1, "spear_man", 3, "archer", 2),
                java.util.Map.of("spear_man", 3, "shieldman", 3));
        assertEquals(1, w.get("militia"));
        assertEquals(6, w.get("spear_man"));
        assertEquals(3, w.get("shieldman"), "shieldmen join even where the composition has none");
        assertEquals(2, w.get("archer"));
    }

    @Test
    void wartimeTopUpComesInBatchesUpToTheTarget() {
        assertEquals(2, Mobilization.topUp(10, 40, 48, 1000, -1, 600, 2), "first top-up at once");
        assertEquals(0, Mobilization.topUp(10, 40, 48, 1000, 700, 600, 2), "not before the interval");
        assertEquals(2, Mobilization.topUp(10, 40, 48, 1300, 700, 600, 2));
        assertEquals(1, Mobilization.topUp(39, 40, 48, 1300, 700, 600, 2), "only up to the target");
        assertEquals(0, Mobilization.topUp(40, 40, 48, 1300, 700, 600, 2));
    }

    @Test
    void leviesAreGearedByTheirOwnLevelNotTheVillageTier() {
        assertEquals(MilitaryTier.GUARD_POST, dev.hywmill.garrison.equip.EquipmentProfiles.gearTier(true, 1, MilitaryTier.WATCH),
                "a Watch's levies get guard-post (iron) weapons, not clubs");
        assertEquals(MilitaryTier.WATCH, dev.hywmill.garrison.equip.EquipmentProfiles.gearTier(false, 0, MilitaryTier.WATCH));
        assertEquals("levy", dev.hywmill.garrison.equip.EquipmentProfiles.role(true, dev.hywmill.garrison.duty.Duty.SENTRY));
        assertEquals("sentry", dev.hywmill.garrison.equip.EquipmentProfiles.role(false, dev.hywmill.garrison.duty.Duty.SENTRY));
    }
}

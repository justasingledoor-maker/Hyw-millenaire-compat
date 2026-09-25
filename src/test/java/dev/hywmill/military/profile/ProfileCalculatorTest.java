package dev.hywmill.military.profile;

import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.classify.BuildingRole;
import dev.hywmill.military.classify.RoleClassifier.VillagerFacts;
import dev.hywmill.military.classify.RoleTable;
import dev.hywmill.military.classify.VillagerRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** M2-2 capacity and the profile formulas. */
class ProfileCalculatorTest {
    static final RoleTable T = new RoleTable(
            Map.of("millenaire:norman/guard", VillagerRole.SOLDIER, "millenaire:norman/knight", VillagerRole.LEADER,
                    "millenaire:byzantines/soldier_byzantine", VillagerRole.SOLDIER),
            Map.of());
    static final VillagerFacts GUARD = new VillagerFacts("millenaire:norman/guard", false, false, true);
    static final VillagerFacts KNIGHT = new VillagerFacts("millenaire:norman/knight", false, false, true);
    static final VillagerFacts LADY = new VillagerFacts("millenaire:norman/lady", false, false, false);
    static final VillagerFacts FARMER = new VillagerFacts("millenaire:norman/farmer", false, false, true);
    static final VillagerFacts BANDIT = new VillagerFacts("millenaire:norman/bandit", true, false, true);
    static final VillagerFacts BYZ = new VillagerFacts("millenaire:byzantines/soldier_byzantine", false, false, true);

    @Test
    void capacityCountsOnlySoldierAndMilitiaSlotsOfTheListedBuildings() {
        List<ProfileCalculator.BuildingSlots> b = List.of(
                // Norman large fort: the town hall lists only the knight and lady ...
                new ProfileCalculator.BuildingSlots("millenaire:norman/largefort", "a", 6, List.of(KNIGHT, LADY)),
                // ... its barrack sub-building is a separate instance with its own two guards
                new ProfileCalculator.BuildingSlots("millenaire:norman/largefort_a_barrack", "a", 0, List.of(GUARD, GUARD)),
                new ProfileCalculator.BuildingSlots("millenaire:norman/farm", "a", 2, List.of(FARMER, LADY)),
                new ProfileCalculator.BuildingSlots("millenaire:norman/bandittower", "a", 0, List.of(BANDIT)));
        assertEquals(3, ProfileCalculator.capacity(b, T), "2 guards + 1 farmer; leader, civilian and outlaw slots excluded");
    }

    @Test
    void capacityUsesTheCurrentLevelSlotsAsGiven() {
        // fortress_a_barracks declares 1..4 soldiers at levels 0..3; the adapter passes the current level only.
        assertEquals(1, ProfileCalculator.capacity(List.of(
                new ProfileCalculator.BuildingSlots("millenaire:byzantines/fortress_a_barracks", "a", 0, List.of(BYZ))), T));
        assertEquals(4, ProfileCalculator.capacity(List.of(
                new ProfileCalculator.BuildingSlots("millenaire:byzantines/fortress_a_barracks", "a", 3, List.of(BYZ, BYZ, BYZ, BYZ))), T));
    }

    @Test
    void readiness() {
        assertEquals(50, ProfileCalculator.readiness(1, 1, 4));
        assertEquals(100, ProfileCalculator.readiness(3, 3, 4), "capped at 100");
        assertEquals(100, ProfileCalculator.readiness(0, 2, 0));
        assertEquals(0, ProfileCalculator.readiness(0, 0, 0));
    }

    @Test
    void equipmentOnlyOverLoadedDefenders() {
        List<ProfileCalculator.LoadedGear> g = List.of(
                new ProfileCalculator.LoadedGear(GUARD, 6, 4),
                new ProfileCalculator.LoadedGear(FARMER, 0, 1),
                new ProfileCalculator.LoadedGear(LADY, 20, 20),
                new ProfileCalculator.LoadedGear(BANDIT, 20, 20));
        assertEquals(5.5, ProfileCalculator.equipmentScore(g, T));
        assertEquals(-1, ProfileCalculator.equipmentScore(List.of(new ProfileCalculator.LoadedGear(LADY, 1, 1)), T));
    }

    @Test
    void profile() {
        MilitaryProfile p = ProfileCalculator.compute(
                Map.of(VillagerRole.SOLDIER, 2, VillagerRole.LEADER, 1, VillagerRole.MILITIA, 5, VillagerRole.CIVILIAN, 9),
                Map.of(BuildingRole.FORT_TOWNHALL, 1, BuildingRole.BORDER_MARKER, 13, BuildingRole.WATCHTOWER, 2),
                List.of(new ProfileCalculator.BuildingSlots("x", "a", 0, List.of(GUARD, GUARD, FARMER, FARMER))), List.of(), T);
        assertEquals(8, p.defenders());
        assertEquals(4, p.capacity());
        assertEquals(100, p.readiness());
        assertEquals(11, p.fortification());
        assertEquals(MilitaryTier.GARRISON, p.tier());
        assertEquals(Map.of(BuildingRole.FORT_TOWNHALL, 1, BuildingRole.WATCHTOWER, 2), p.infrastructure());
        assertEquals(-1, p.equipmentScore());
    }
}

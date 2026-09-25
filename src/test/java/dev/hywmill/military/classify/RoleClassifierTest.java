package dev.hywmill.military.classify;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.hywmill.military.classify.RoleClassifier.VillagerFacts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleClassifierTest {
    private static final RoleTable TABLE = new RoleTable(
            Map.of("millenaire:norman/guard", VillagerRole.SOLDIER, "millenaire:norman/knight", VillagerRole.LEADER,
                    "millenaire:norman/bandit", VillagerRole.SOLDIER),
            Map.of("millenaire:norman/guardhouse", BuildingRole.GUARDHOUSE, "millenaire:norman/fort", BuildingRole.FORT_TOWNHALL,
                    "millenaire:norman/full_wall_gateway", BuildingRole.NONE));

    @Test
    void explicitTableWins() {
        assertEquals(VillagerRole.SOLDIER, RoleClassifier.villager(new VillagerFacts("millenaire:norman/guard", false, false, true), TABLE));
        assertEquals(VillagerRole.LEADER, RoleClassifier.villager(new VillagerFacts("millenaire:norman/knight", false, false, true), TABLE));
    }

    @Test
    void hostileIsOutlawEvenIfListed() {
        assertEquals(VillagerRole.OUTLAW, RoleClassifier.villager(new VillagerFacts("millenaire:norman/bandit", true, false, true), TABLE));
    }

    @Test
    void fallbacks() {
        // helpInAttacks without a table entry: militia (farmers, lumbermen, ... in Millénaire)
        assertEquals(VillagerRole.MILITIA, RoleClassifier.villager(new VillagerFacts("millenaire:norman/farmer", false, false, true), TABLE));
        assertEquals(VillagerRole.CIVILIAN, RoleClassifier.villager(new VillagerFacts("millenaire:norman/wife", false, false, false), TABLE));
        assertEquals(VillagerRole.CIVILIAN, RoleClassifier.villager(new VillagerFacts("millenaire:inuits/inuit_sanduitson", false, true, true), TABLE));
    }

    @Test
    void ambiguousOnlyWhenUnlistedAndSuggestive() {
        VillagerFacts seneschal = new VillagerFacts("millenaire:norman/seneschal", false, false, true);
        assertTrue(RoleClassifier.isAmbiguous(seneschal, true, TABLE));
        assertFalse(RoleClassifier.isAmbiguous(seneschal, false, TABLE));
        assertFalse(RoleClassifier.isAmbiguous(new VillagerFacts("millenaire:norman/knight", false, false, true), true, TABLE));
        assertFalse(RoleClassifier.isAmbiguous(new VillagerFacts("millenaire:norman/bandit", true, false, true), true, TABLE));
    }

    @Test
    void wallTypesDeriveRoles() {
        assertEquals(BuildingRole.WALL, RoleClassifier.wallRole(true, RoleClassifier.WallPiece.WALL));
        assertEquals(BuildingRole.WALL, RoleClassifier.wallRole(true, RoleClassifier.WallPiece.CORNER));
        assertEquals(BuildingRole.WALL, RoleClassifier.wallRole(true, RoleClassifier.WallPiece.SLOPE));
        assertEquals(BuildingRole.TOWER, RoleClassifier.wallRole(true, RoleClassifier.WallPiece.TOWER));
        assertEquals(BuildingRole.GATE, RoleClassifier.wallRole(true, RoleClassifier.WallPiece.GATEWAY));
        // border posts: a wall type that spawns no wall segments is a line of markers
        for (RoleClassifier.WallPiece p : RoleClassifier.WallPiece.values()) {
            assertEquals(BuildingRole.BORDER_MARKER, RoleClassifier.wallRole(false, p));
        }
    }

    @Test
    void buildingOrder() {
        Map<String, BuildingRole> walls = Map.of(
                "millenaire:norman/full_wall_gateway", BuildingRole.GATE,
                "millenaire:norman/full_wall", BuildingRole.WALL,
                "millenaire:norman/borderpost", BuildingRole.BORDER_MARKER);
        assertEquals(BuildingRole.GUARDHOUSE, RoleClassifier.building("millenaire:norman/guardhouse", false, walls, TABLE));
        assertEquals(BuildingRole.WALL, RoleClassifier.building("millenaire:norman/full_wall", false, walls, TABLE));
        assertEquals(BuildingRole.BORDER_MARKER, RoleClassifier.building("millenaire:norman/borderpost", true, walls, TABLE));
        assertEquals(BuildingRole.BORDER_MARKER, RoleClassifier.building("millenaire:norman/somepost", true, walls, TABLE));
        assertNull(RoleClassifier.building("millenaire:norman/full_wall_gateway", false, walls, TABLE), "explicit NONE excludes");
        assertNull(RoleClassifier.building("millenaire:norman/forge", false, walls, TABLE), "no keyword or tag heuristics");
    }
}

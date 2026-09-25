package dev.hywmill.military;

import dev.hywmill.military.classify.BuildingRole;
import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.fortification.FortificationScore;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MilitaryTierTest {
    private static Map<VillagerRole, Integer> v(Object... kv) {
        Map<VillagerRole, Integer> m = new EnumMap<>(VillagerRole.class);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((VillagerRole) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    private static Map<BuildingRole, Integer> b(Object... kv) {
        Map<BuildingRole, Integer> m = new EnumMap<>(BuildingRole.class);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((BuildingRole) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    private static MilitaryTier tier(Map<VillagerRole, Integer> vr, Map<BuildingRole, Integer> br) {
        return MilitaryTier.assess(vr, br, FortificationScore.compute(br));
    }

    @Test
    void fortificationPoints() {
        assertEquals(0, FortificationScore.compute(b(BuildingRole.BORDER_MARKER, 13)));
        assertEquals(1 + 3 + 2 + 3 + 3 + 4 + 5, FortificationScore.compute(b(BuildingRole.WALL, 1, BuildingRole.TOWER, 1,
                BuildingRole.GATE, 1, BuildingRole.GUARDHOUSE, 1, BuildingRole.WATCHTOWER, 1, BuildingRole.BARRACKS, 1,
                BuildingRole.FORT_TOWNHALL, 1)));
        assertEquals(0, FortificationScore.compute(b(BuildingRole.ARMOURY, 2, BuildingRole.TRAINING, 1)));
    }

    @Test
    void douvresBecomesWatchWithZeroFortification() {
        // M1 test world: 13 border posts, militia only (no guard/knight), no military buildings.
        Map<BuildingRole, Integer> br = b(BuildingRole.BORDER_MARKER, 13);
        assertEquals(0, FortificationScore.compute(br));
        assertEquals(MilitaryTier.WATCH, tier(v(VillagerRole.MILITIA, 12, VillagerRole.CIVILIAN, 20), br));
    }

    @Test
    void none() {
        assertEquals(MilitaryTier.NONE, tier(v(VillagerRole.CIVILIAN, 5, VillagerRole.OUTLAW, 4), b(BuildingRole.GUARDHOUSE, 1)));
    }

    @Test
    void guardPost() {
        assertEquals(MilitaryTier.GUARD_POST, tier(v(VillagerRole.SOLDIER, 1), b()));
        assertEquals(MilitaryTier.GUARD_POST, tier(v(VillagerRole.MILITIA, 2), b(BuildingRole.WATCHTOWER, 1)));
        assertEquals(MilitaryTier.WATCH, tier(v(VillagerRole.MILITIA, 1, VillagerRole.LEADER, 0), b(BuildingRole.GUARDHOUSE, 1)));
        assertEquals(MilitaryTier.WATCH, tier(v(VillagerRole.LEADER, 1), b()), "a leader alone is not a soldier");
    }

    @Test
    void garrisonNeedsProfessionalsAndInfrastructure() {
        assertEquals(MilitaryTier.GARRISON, tier(v(VillagerRole.SOLDIER, 2, VillagerRole.LEADER, 1), b(BuildingRole.ARMOURY, 1)));
        assertEquals(MilitaryTier.GUARD_POST, tier(v(VillagerRole.SOLDIER, 2, VillagerRole.MILITIA, 10), b(BuildingRole.ARMOURY, 1)));
        assertEquals(MilitaryTier.GUARD_POST, tier(v(VillagerRole.SOLDIER, 5), b(BuildingRole.GUARDHOUSE, 1)));
    }

    @Test
    void strongholdNeedsAWall() {
        Map<VillagerRole, Integer> army = v(VillagerRole.SOLDIER, 4, VillagerRole.LEADER, 1);
        // 5 + 4*3 + 4 = 21 fortification but no wall
        assertEquals(MilitaryTier.GARRISON, tier(army, b(BuildingRole.FORT_TOWNHALL, 1, BuildingRole.WATCHTOWER, 4, BuildingRole.BARRACKS, 1)));
        assertEquals(MilitaryTier.STRONGHOLD, tier(army, b(BuildingRole.FORT_TOWNHALL, 1, BuildingRole.WATCHTOWER, 4, BuildingRole.BARRACKS, 1,
                BuildingRole.WALL, 1)));
        assertEquals(MilitaryTier.GARRISON, tier(army, b(BuildingRole.FORT_TOWNHALL, 1, BuildingRole.WALL, 3)), "wall but fortification < 20");
    }
}

package dev.hywmill.military.profile;

import dev.hywmill.fortification.FortificationScore;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.classify.BuildingRole;
import dev.hywmill.military.classify.RoleClassifier;
import dev.hywmill.military.classify.RoleTable;
import dev.hywmill.military.classify.VillagerRole;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure profile formulas (unit-tested). */
public final class ProfileCalculator {
    private static final Set<BuildingRole> INFRASTRUCTURE = Set.of(BuildingRole.GUARDHOUSE, BuildingRole.WATCHTOWER,
            BuildingRole.BARRACKS, BuildingRole.ARMOURY, BuildingRole.TRAINING, BuildingRole.FORT_TOWNHALL);

    private ProfileCalculator() {}

    /** Resident slots of one operational building at its current variant and level. */
    public record BuildingSlots(String planSetId, String variant, int level, List<RoleClassifier.VillagerFacts> slots) {}

    /** Gear of one loaded resident. */
    public record LoadedGear(RoleClassifier.VillagerFacts facts, double armor, double weaponDamage) {}

    /**
     * Military capacity: every SOLDIER or MILITIA slot declared by an operational building for
     * its <em>current</em> variant and level. Each building instance counts only its own slots
     * (sub-buildings are separate instances; a parent never lists its sub-buildings' residents),
     * so nothing is counted twice. Future upgrade levels and plan names are never consulted.
     */
    public static int capacity(List<BuildingSlots> buildings, RoleTable table) {
        int n = 0;
        for (BuildingSlots b : buildings) {
            for (RoleClassifier.VillagerFacts f : b.slots()) {
                VillagerRole r = RoleClassifier.villager(f, table);
                if (r == VillagerRole.SOLDIER || r == VillagerRole.MILITIA) {
                    n++;
                }
            }
        }
        return n;
    }

    /** readiness = min(100, 100 * (soldiers + militia) / capacity); without capacity, 100 if any exist. */
    public static int readiness(int soldiers, int militia, int capacity) {
        int present = soldiers + militia;
        if (capacity <= 0) {
            return present > 0 ? 100 : 0;
        }
        return (int) Math.min(100, Math.round(100.0 * present / capacity));
    }

    /** Mean armor + weapon damage over loaded defenders, rounded to 0.1; -1 if none are loaded. */
    public static double equipmentScore(List<LoadedGear> gear, RoleTable table) {
        double sum = 0;
        int n = 0;
        for (LoadedGear g : gear) {
            if (RoleClassifier.villager(g.facts(), table).isDefender()) {
                sum += g.armor() + g.weaponDamage();
                n++;
            }
        }
        return n == 0 ? -1 : Math.round(sum / n * 10.0) / 10.0;
    }

    public static int loadedDefenders(List<LoadedGear> gear, RoleTable table) {
        return (int) gear.stream().filter(g -> RoleClassifier.villager(g.facts(), table).isDefender()).count();
    }

    public static MilitaryProfile compute(Map<VillagerRole, Integer> villagers, Map<BuildingRole, Integer> buildings,
                                          List<BuildingSlots> slots, List<LoadedGear> gear, RoleTable table) {
        int soldiers = villagers.getOrDefault(VillagerRole.SOLDIER, 0);
        int militia = villagers.getOrDefault(VillagerRole.MILITIA, 0);
        int leaders = villagers.getOrDefault(VillagerRole.LEADER, 0);
        int fort = FortificationScore.compute(buildings);
        int cap = capacity(slots, table);
        Map<BuildingRole, Integer> infra = new EnumMap<>(BuildingRole.class);
        buildings.forEach((r, c) -> {
            if (INFRASTRUCTURE.contains(r) && c > 0) {
                infra.put(r, c);
            }
        });
        return new MilitaryProfile(soldiers, militia, leaders, soldiers + militia + leaders,
                villagers.getOrDefault(VillagerRole.OUTLAW, 0), villagers.getOrDefault(VillagerRole.CIVILIAN, 0),
                cap, readiness(soldiers, militia, cap), equipmentScore(gear, table), loadedDefenders(gear, table),
                fort, MilitaryTier.assess(villagers, buildings, fort), buildings, infra);
    }
}

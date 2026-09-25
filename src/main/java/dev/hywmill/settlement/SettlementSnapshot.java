package dev.hywmill.settlement;

import dev.hywmill.military.classify.BuildingRole;
import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.military.profile.ProfileCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Military-relevant facts read directly from the settlement mod and classified by the role
 * tables. No derived scores here; see {@code MilitaryTier} and {@code FortificationScore}.
 *
 * @param population          living residents registered to the village, excluding foreign raid clones
 * @param garrison            living residents whose type defends the village (Millénaire helpInAttacks; M1 meaning, unchanged)
 * @param defendingStrength   Millénaire's own Village.getVillageDefendingStrength()
 * @param villagerRoles       living residents per role
 * @param buildingRoles       operational buildings per role (BORDER_MARKER included, reported only)
 * @param tagCounts           operational buildings per Millénaire tag (reported only; not used for classification)
 * @param wallSegmentsPending wall segments that exist in the plan but are not yet operational (diagnostic only)
 * @param ambiguousTypes      helpInAttacks villager types with a suggestive tag but no table entry (classified MILITIA; report)
 * @param villageRadius       Millénaire's effective village radius (VillageType.radius, villageRadiusOverride applied)
 * @param loneBuilding        Millénaire lone building (bandit camp, inn, lone farm…)
 * @param controllerId        controlling player of a player-controlled village; never the faction identity
 * @param defendingPos        Millénaire's own raid defending position (RaidManager.resolveDefendingPos)
 * @param buildingSlots       resident slots of operational buildings at their current variant/level (capacity)
 * @param loadedGear          armor/weapon of currently loaded residents (equipment score)
 */
public record SettlementSnapshot(
        UUID id,
        String name,
        String culture,
        String type,
        BlockPos center,
        AABB bounds,
        boolean active,
        int population,
        int adults,
        int children,
        int garrison,
        int defendingStrength,
        Map<VillagerRole, Integer> villagerRoles,
        Map<BuildingRole, Integer> buildingRoles,
        Map<String, Integer> tagCounts,
        int wallSegmentsPending,
        List<String> ambiguousTypes,
        String townhallPlan,
        int buildingsTotal,
        int buildingsOperational,
        int villageRadius,
        boolean loneBuilding,
        @Nullable UUID controllerId,
        BlockPos defendingPos,
        List<ProfileCalculator.BuildingSlots> buildingSlots,
        List<ProfileCalculator.LoadedGear> loadedGear
) {
    public static final List<String> TRACKED_TAGS = List.of("patrol", "armoury", "training", "wall_level_0", "wall_level_1", "wall_level_2");
}

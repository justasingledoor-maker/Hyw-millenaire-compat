package dev.hywmill.settlement;

import dev.hywmill.military.classify.BuildingRole;
import dev.hywmill.military.classify.VillagerRole;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

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
        int buildingsOperational
) {
    public static final List<String> TRACKED_TAGS = List.of("patrol", "armoury", "training", "wall_level_0", "wall_level_1", "wall_level_2");
}

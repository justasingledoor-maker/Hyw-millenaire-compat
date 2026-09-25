package dev.hywmill.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Military-relevant facts read directly from the settlement mod. No derived scores here;
 * see {@code MilitaryTier} and {@code FortificationScore} for the formulas.
 *
 * @param population        living residents registered to the village, excluding foreign raid clones
 * @param garrison          living residents whose type defends the village (Millénaire helpInAttacks)
 * @param defendingStrength Millénaire's own Village.getVillageDefendingStrength()
 * @param tagCounts         operational buildings per tag (patrol, armoury, training, wall_level_0/1/2)
 * @param wallSegments      operational buildings flagged as wall segments
 * @param wallSegmentsPending wall segments that exist in the plan but are not yet operational (diagnostic only)
 * @param wallTowers        operational wall segments that are also patrol-tagged (towers)
 * @param defensiveBuildings operational NON-wall buildings tagged patrol (guardhouses, watchtowers, forts)
 * @param militaryPlans     plan-set ids of operational buildings whose name matches a military keyword
 * @param fortTownhall      the town hall's plan set is a fort
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
        Map<String, Integer> tagCounts,
        int wallSegments,
        int wallSegmentsPending,
        int wallTowers,
        int defensiveBuildings,
        List<String> militaryPlans,
        String townhallPlan,
        boolean fortTownhall,
        int buildingsTotal,
        int buildingsOperational
) {
    public static final List<String> TRACKED_TAGS = List.of("patrol", "armoury", "training", "wall_level_0", "wall_level_1", "wall_level_2");

    public int tag(String tag) {
        return tagCounts.getOrDefault(tag, 0);
    }

    public boolean hasPlanKeyword(String keyword) {
        for (String plan : militaryPlans) {
            if (plan.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

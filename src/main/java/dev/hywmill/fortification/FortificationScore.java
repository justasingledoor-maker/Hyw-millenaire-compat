package dev.hywmill.fortification;

import dev.hywmill.classify.BuildingRole;

import java.util.Map;

/**
 * Fortification score: the sum of {@link BuildingRole#fortification} over operational buildings.
 * Under-construction buildings and planned wall segments count for nothing.
 *
 * <pre>
 *   WALL 1, TOWER 3, GATE 2, BORDER_MARKER 0, GUARDHOUSE 3, WATCHTOWER 3, BARRACKS 4,
 *   ARMOURY 0, TRAINING 0, FORT_TOWNHALL 5
 * </pre>
 */
public final class FortificationScore {
    private FortificationScore() {}

    public static int compute(Map<BuildingRole, Integer> operationalBuildingRoles) {
        int score = 0;
        for (Map.Entry<BuildingRole, Integer> e : operationalBuildingRoles.entrySet()) {
            score += e.getKey().fortification * e.getValue();
        }
        return score;
    }
}

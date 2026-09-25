package dev.hywmill.military.defense;

import dev.hywmill.military.doctrine.Doctrine;

import java.util.List;

/**
 * Civilians shelter while the village is ALERT or ENGAGED and a threat is within
 * {@code shelterRadius} of <em>them</em>; {@code shelterRadius = -1} shelters every civilian.
 * Pure (unit-tested).
 */
public final class ShelterPolicy {
    private ShelterPolicy() {}

    public static boolean shouldShelter(AlertState state, int shelterRadius, DefenseCoordinator.Pos civilian,
                                        List<DefenseCoordinator.Pos> threats) {
        if (!state.civiliansShelter()) {
            return false;
        }
        if (shelterRadius == Doctrine.VILLAGE_WIDE) {
            return true;
        }
        double r2 = (double) shelterRadius * shelterRadius;
        for (DefenseCoordinator.Pos t : threats) {
            if (t.distSq(civilian) <= r2) {
                return true;
            }
        }
        return false;
    }
}

package dev.hywmill.military.profile;

import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.classify.BuildingRole;

import java.util.Map;

/**
 * A village's military profile, derived from the M1.1 classification.
 *
 * @param capacity       SOLDIER + MILITIA resident slots of operational buildings at their
 *                       current variant and level (see {@link ProfileCalculator#capacity})
 * @param readiness      0..100: living SOLDIER + MILITIA residents as a share of capacity
 * @param equipmentScore mean (armor value + main-hand attack damage) of loaded defenders, or
 *                       -1 when no defender is loaded (the ledger then keeps the last known value)
 * @param loadedDefenders how many defenders the equipment score was measured on
 * @param infrastructure operational military buildings (GUARDHOUSE … FORT_TOWNHALL; no walls or markers)
 */
public record MilitaryProfile(
        int soldiers,
        int militia,
        int leaders,
        int defenders,
        int outlaws,
        int civilians,
        int capacity,
        int readiness,
        double equipmentScore,
        int loadedDefenders,
        int fortification,
        MilitaryTier tier,
        Map<BuildingRole, Integer> buildingRoles,
        Map<BuildingRole, Integer> infrastructure
) {}

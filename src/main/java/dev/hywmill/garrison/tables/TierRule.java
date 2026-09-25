package dev.hywmill.garrison.tables;

import java.util.Set;

/**
 * Garrison rules for one military tier.
 *
 * @param perCapacity    garrison target per point of M2 military capacity
 * @param minTarget      target floor (villages of this tier)
 * @param maxTarget      target ceiling from the formula
 * @param maxUnits       the tier's per-village ceiling of HywMill-managed troops (0/8/16/32/64)
 * @param baseDaily      levy points per active in-game day
 * @param poolCap        levy point cap
 * @param equipmentLevel equipment level requested for units recruited at this tier
 * @param classes        unit classes this tier may recruit
 */
public record TierRule(double perCapacity, int minTarget, int maxTarget, int maxUnits, double baseDaily, double poolCap,
                       int equipmentLevel, Set<UnitClass> classes) {
    public static final TierRule NONE = new TierRule(0, 0, 0, 0, 0, 0, 0, Set.of());
}

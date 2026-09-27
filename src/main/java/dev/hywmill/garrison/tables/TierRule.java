package dev.hywmill.garrison.tables;

import java.util.Set;

/**
 * Garrison rules for one military tier.
 *
 * @param perCapacity    garrison target per point of M2 military capacity (soldier/militia slots)
 * @param minTarget      target floor (villages of this tier)
 * @param maxTarget      target ceiling from the formula
 * @param maxUnits       the tier's per-village ceiling of HywMill-managed troops
 * @param baseDaily      levy points per active in-game day
 * @param poolCap        levy point cap
 * @param equipmentLevel equipment level requested for units recruited at this tier
 * @param classes        unit classes this tier may recruit
 * @param levyShare      M5-G: target per adult resident (0 = off)
 * @param fortDiv        M5-G: fortification score per target point (0 = off)
 * @param fortCap        M5-G: most target points fortification may add
 * @param perTargetDaily M5-G: extra levy points per active day per point of target (0 = off)
 * @param poolCapShare   M5-G: levy pool cap is at least this share of the target (0 = off)
 * @param supportRatio   M5-G: optional ceiling of target per resident (0 = off; population is no ceiling by default)
 */
public record TierRule(double perCapacity, int minTarget, int maxTarget, int maxUnits, double baseDaily, double poolCap,
                       int equipmentLevel, Set<UnitClass> classes, double levyShare, double fortDiv, double fortCap,
                       double perTargetDaily, double poolCapShare, double supportRatio) {
    public static final TierRule NONE = new TierRule(0, 0, 0, 0, 0, 0, 0, Set.of());

    /** The M3 rule: every M5-G term neutral. */
    public TierRule(double perCapacity, int minTarget, int maxTarget, int maxUnits, double baseDaily, double poolCap,
                    int equipmentLevel, Set<UnitClass> classes) {
        this(perCapacity, minTarget, maxTarget, maxUnits, baseDaily, poolCap, equipmentLevel, classes, 0, 0, 0, 0, 0, 0);
    }
}

package dev.hywmill.garrison.tables;

import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.classify.BuildingRole;

import java.util.Map;

/**
 * The garrison rules that apply to one culture (defaults with the culture's patch applied).
 * {@code infraBonus} (target points per operational building of a role) and {@code typeFactors}
 * (target multiplier per Millénaire village type) are M5-G terms; empty maps are neutral.
 */
public record GarrisonTable(Map<MilitaryTier, TierRule> tiers, double perCapacityDaily, double startingFraction, int commitPerThreat,
                            Map<String, Integer> composition, String equipmentProvider, Map<BuildingRole, Double> infraBonus,
                            Map<String, Double> typeFactors) {
    public GarrisonTable(Map<MilitaryTier, TierRule> tiers, double perCapacityDaily, double startingFraction, int commitPerThreat,
                         Map<String, Integer> composition, String equipmentProvider) {
        this(tiers, perCapacityDaily, startingFraction, commitPerThreat, composition, equipmentProvider, Map.of(), Map.of());
    }

    public TierRule tier(MilitaryTier t) {
        return tiers.getOrDefault(t, TierRule.NONE);
    }
}

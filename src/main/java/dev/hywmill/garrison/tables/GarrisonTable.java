package dev.hywmill.garrison.tables;

import dev.hywmill.military.MilitaryTier;

import java.util.Map;

/** The garrison rules that apply to one culture (defaults with the culture's patch applied). */
public record GarrisonTable(Map<MilitaryTier, TierRule> tiers, double perCapacityDaily, double startingFraction, int commitPerThreat,
                            Map<String, Integer> composition, String equipmentProvider) {
    public TierRule tier(MilitaryTier t) {
        return tiers.getOrDefault(t, TierRule.NONE);
    }
}

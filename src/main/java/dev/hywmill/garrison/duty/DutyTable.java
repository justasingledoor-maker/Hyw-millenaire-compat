package dev.hywmill.garrison.duty;

import dev.hywmill.military.MilitaryTier;

import java.util.Map;

/** The duty rules that apply to one culture (defaults with the culture's patch applied). */
public record DutyTable(Map<MilitaryTier, DutyRule> tiers, MoveRule move, ScoutRule scout, RaidRule raid) {
    public DutyRule tier(MilitaryTier t) {
        return tiers.getOrDefault(t, DutyRule.NONE);
    }
}

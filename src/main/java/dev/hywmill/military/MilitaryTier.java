package dev.hywmill.military;

import dev.hywmill.settlement.SettlementSnapshot;

/**
 * Deterministic military tier. Thresholds are evaluated top-down; the first match wins.
 *
 * <pre>
 *   STRONGHOLD : GARRISON conditions and fortification >= 20
 *   GARRISON   : garrison >= 4 and (armoury or training building, or a barrack/armoury plan)
 *   GUARD_POST : garrison >= 2 and at least one defensive building (patrol-tagged or military plan)
 *   WATCH      : garrison >= 1
 *   NONE       : no defenders
 * </pre>
 */
public enum MilitaryTier {
    NONE, WATCH, GUARD_POST, GARRISON, STRONGHOLD;

    public static final int STRONGHOLD_FORTIFICATION = 20;

    public static MilitaryTier assess(SettlementSnapshot s, int fortification) {
        boolean trainingInfra = s.tag("armoury") > 0 || s.tag("training") > 0
                || s.hasPlanKeyword("barrack") || s.hasPlanKeyword("armoury");
        boolean defensiveInfra = s.tag("patrol") > 0 || !s.militaryPlans().isEmpty();

        boolean garrison = s.garrison() >= 4 && trainingInfra;
        if (garrison && fortification >= STRONGHOLD_FORTIFICATION) {
            return STRONGHOLD;
        }
        if (garrison) {
            return GARRISON;
        }
        if (s.garrison() >= 2 && defensiveInfra) {
            return GUARD_POST;
        }
        if (s.garrison() >= 1) {
            return WATCH;
        }
        return NONE;
    }

    public static MilitaryTier parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}

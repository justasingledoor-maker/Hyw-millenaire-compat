package dev.hywmill.military;

import dev.hywmill.classify.BuildingRole;
import dev.hywmill.classify.VillagerRole;

import java.util.Map;

/**
 * Deterministic military tier (ledger format 2). Evaluated top-down; the first match wins.
 * "Defenders" are SOLDIER + LEADER + MILITIA residents (outlaws excluded).
 *
 * <pre>
 *   STRONGHOLD : GARRISON conditions, fortification >= 20 and at least one WALL
 *   GARRISON   : >= 3 SOLDIER/LEADER and at least one of BARRACKS, ARMOURY, TRAINING, FORT_TOWNHALL
 *   GUARD_POST : >= 1 SOLDIER, or (>= 2 defenders and at least one GUARDHOUSE or WATCHTOWER)
 *   WATCH      : >= 1 defender
 *   NONE       : otherwise
 * </pre>
 */
public enum MilitaryTier {
    NONE, WATCH, GUARD_POST, GARRISON, STRONGHOLD;

    public static final int STRONGHOLD_FORTIFICATION = 20;
    public static final int GARRISON_PROFESSIONALS = 3;

    public static MilitaryTier assess(Map<VillagerRole, Integer> villagers, Map<BuildingRole, Integer> buildings, int fortification) {
        int soldiers = count(villagers, VillagerRole.SOLDIER);
        int professionals = soldiers + count(villagers, VillagerRole.LEADER);
        int defenders = professionals + count(villagers, VillagerRole.MILITIA);

        boolean garrisonInfra = count(buildings, BuildingRole.BARRACKS) > 0 || count(buildings, BuildingRole.ARMOURY) > 0
                || count(buildings, BuildingRole.TRAINING) > 0 || count(buildings, BuildingRole.FORT_TOWNHALL) > 0;
        boolean guardInfra = count(buildings, BuildingRole.GUARDHOUSE) > 0 || count(buildings, BuildingRole.WATCHTOWER) > 0;

        boolean garrison = professionals >= GARRISON_PROFESSIONALS && garrisonInfra;
        if (garrison && fortification >= STRONGHOLD_FORTIFICATION && count(buildings, BuildingRole.WALL) > 0) {
            return STRONGHOLD;
        }
        if (garrison) {
            return GARRISON;
        }
        if (soldiers >= 1 || (defenders >= 2 && guardInfra)) {
            return GUARD_POST;
        }
        if (defenders >= 1) {
            return WATCH;
        }
        return NONE;
    }

    private static <K> int count(Map<K, Integer> m, K key) {
        return m.getOrDefault(key, 0);
    }

    public static MilitaryTier parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}

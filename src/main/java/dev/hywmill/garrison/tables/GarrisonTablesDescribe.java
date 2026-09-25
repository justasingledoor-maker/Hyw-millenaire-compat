package dev.hywmill.garrison.tables;

import dev.hywmill.military.MilitaryTier;

/** Display helpers for garrison tables. */
public final class GarrisonTablesDescribe {
    private GarrisonTablesDescribe() {}

    /** "NONE 0, WATCH 8, ..." */
    public static String caps(GarrisonTable t) {
        StringBuilder sb = new StringBuilder();
        for (MilitaryTier tier : MilitaryTier.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(tier.name()).append(' ').append(t.tier(tier).maxUnits());
        }
        return sb.toString();
    }
}

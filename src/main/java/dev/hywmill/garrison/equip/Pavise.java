package dev.hywmill.garrison.equip;

import dev.hywmill.military.MilitaryTier;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Crossbowmen's pavises (post-M5, user request). A crossbowman reloads behind a pavise, a tall standing shield: HYW gives its
 * crossbowmen nothing in the off hand, so most of them ({@link #SHARE}) carry an Epic Knights pavise of their tier (wood,
 * iron, steel), painted like any shield. A mob holding a crossbow never raises a shield to block, so the pavise also gives a
 * little armour while carried ({@link #armour}). Pure.
 */
public final class Pavise {
    private Pavise() {}

    public static final double SHARE = 0.6;
    public static final String FAMILY = "pavese";

    /** The pavise a crossbowman carries at his gear tier, or null for the ones who carry none (stable per soldier). */
    @Nullable
    public static String choose(MilitaryTier tier, UUID soldier) {
        long h = soldier.getMostSignificantBits() * 31 + soldier.getLeastSignificantBits() + 0x9A7153L;
        if (Math.floorMod(h, 1000L) >= SHARE * 1000) {
            return null;
        }
        String material = switch (tier) {
            case NONE, WATCH -> "wood";
            case GUARD_POST -> Math.floorMod(h >>> 11, 2L) == 0 ? "wood" : "iron";
            case GARRISON -> "iron";
            case STRONGHOLD -> "steel";
        };
        return "magistuarmory:" + material + "_" + FAMILY;
    }

    /** Armour a carried pavise gives (its weight of wood or metal between the man and the arrows). */
    public static int armour(String pavise) {
        String p = pavise.substring(pavise.indexOf(':') + 1);
        if (p.startsWith("wood_") || p.startsWith("stone_")) {
            return 2;
        }
        if (p.startsWith("steel_") || p.startsWith("diamond_") || p.startsWith("netherite_")) {
            return 4;
        }
        return 3;
    }

    public static boolean crossbowman(String entityType) {
        return entityType.endsWith("crossbowman");
    }
}

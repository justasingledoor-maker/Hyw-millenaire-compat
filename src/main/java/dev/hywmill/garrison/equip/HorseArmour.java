package dev.hywmill.garrison.equip;

/**
 * Period horse armour (post-M5): what an HYW rider's horse wears by its equipment level. Leather (dyed in the rider's colours)
 * for the low levels and for light horse below the top; mail for heavy horse at the middle level and light horse at the top;
 * plate barding for heavy horse at the top. Never gold or diamond; without Epic Knights, iron stands in for mail and plate.
 * Pure.
 */
public final class HorseArmour {
    private HorseArmour() {}

    public static final String LEATHER = "minecraft:leather_horse_armor";

    /** Light horse: horse archers, light lancers, mounted gunners (by the rider's entity type path). */
    public static boolean light(String riderType) {
        return riderType.contains("archer") || riderType.contains("light") || riderType.contains("matchlock");
    }

    /** The horse armour item id for an equipment level ({@code seed}: the horse, for the occasional dark barding). */
    public static String choose(int level, boolean light, long seed, boolean ek, boolean addon) {
        String mail = ek ? "magistuarmory:chainmail_horse_armor" : "minecraft:iron_horse_armor";
        String plate = ek ? (addon && Math.floorMod(seed, 3L) == 0 ? "magistuarmoryaddon:dark_barding" : "magistuarmory:barding")
                : "minecraft:iron_horse_armor";
        if (level <= 1) {
            return LEATHER;
        }
        if (level == 2) {
            return light ? LEATHER : mail;
        }
        return light ? mail : plate;
    }
}

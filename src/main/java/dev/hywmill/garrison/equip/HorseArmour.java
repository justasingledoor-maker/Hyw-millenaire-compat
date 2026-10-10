package dev.hywmill.garrison.equip;

/**
 * Period horse armour (post-M5): what an HYW rider's horse wears by its equipment level. Leather (dyed in the rider's colours)
 * for the low levels and for light horse below the top; mail for heavy horse at the middle level and light horse at the top;
 * plate barding for heavy horse at the top. Never gold or diamond; without Epic Knights, iron stands in for mail and plate.
 * A rider in Cultures Armory's Byzantine armour rides a Byzantine horse (felt, scale peytral, kataphraktos barding). Pure.
 */
public final class HorseArmour {
    private HorseArmour() {}

    public static final String LEATHER = "minecraft:leather_horse_armor";

    /** Light horse: horse archers, light lancers, mounted gunners (by the rider's entity type path). */
    public static boolean light(String riderType) {
        return riderType.contains("archer") || riderType.contains("light") || riderType.contains("matchlock");
    }

    /** Cultures Armory's Byzantine horse armour (post-M5, armory 1.3). */
    public static final String CATAPHRACT = "culturesarmory:cataphract_barding";
    public static final String HALF = "culturesarmory:cataphract_half_barding";
    public static final String FELT = "culturesarmory:felt_barding";
    /** The rider coats that bring it: the armory's Byzantine coats (the kataphraktos' and the strategos' the heaviest). */
    public static final java.util.Set<String> BYZANTINE_COATS = java.util.Set.of("culturesarmory:klibanion",
            "culturesarmory:kataphract_armour", "culturesarmory:epilorikion", "culturesarmory:lorikion", "culturesarmory:scale_armour",
            "culturesarmory:kabadion", "culturesarmory:varangian_hauberk", "culturesarmory:strategos_klibanion");
    static final java.util.Set<String> KATAPHRACT_COATS = java.util.Set.of("culturesarmory:kataphract_armour",
            "culturesarmory:strategos_klibanion");

    /**
     * By the rider's coat first: a rider in Byzantine armour (Cultures Armory) rides a Byzantine horse. Heavy horse at the top
     * level under a kataphraktos (or strategos) wears the full kataphraktos barding, other heavy horse the scale peytral and
     * chamfron, light and low-level horse quilted felt dyed in the rider's colours. Any other rider: {@link #choose(int,
     * boolean, long, boolean, boolean)}.
     */
    public static String choose(String riderCoat, int level, boolean light, long seed, boolean ek, boolean addon) {
        if (level > 0 && BYZANTINE_COATS.contains(riderCoat)) {
            if (light) {
                return level >= 3 ? HALF : FELT;
            }
            if (level >= 3) {
                return KATAPHRACT_COATS.contains(riderCoat) ? CATAPHRACT : HALF;
            }
            return level == 2 ? HALF : FELT;
        }
        return choose(level, light, seed, ek, addon);
    }

    /** Horse armour dyed in the rider's colours. */
    public static boolean dyed(String id) {
        return id.equals(LEATHER) || id.equals(FELT);
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

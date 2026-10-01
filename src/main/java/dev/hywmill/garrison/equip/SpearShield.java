package dev.hywmill.garrison.equip;

import dev.hywmill.military.MilitaryTier;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Spearmen's shields (post-M5, user request). Spear and shield was the common foot soldier's kit almost everywhere; HYW gives
 * its spearmen nothing in the off hand, so most of them ({@link #SHARE}) carry a shield of their culture's shape and their
 * tier's material: Normans a kite shield (a heater shield in the larger villages), Byzantines the oval skoutarion, Seljuks,
 * Indians and others a round shield, the Maya a wooden round shield. Japanese spearmen (yari, two hands) and the Inuit carry
 * none. Like a crossbowman's pavise it is painted, and gives a little armour while carried ({@link #armour}). Pure.
 */
public final class SpearShield {
    private SpearShield() {}

    public static final double SHARE = 0.75;
    /** Shield families allowed in a spearman's off hand. */
    public static final Set<String> FAMILIES = Set.of("kiteshield", "heatershield", "roundshield", "ellipticalshield", "rondache", "target", "buckler");

    public static boolean spearman(String entityType) {
        return entityType.endsWith("spear_man");
    }

    /** The shield a spearman carries, or null for the ones who carry none (stable per soldier). */
    @Nullable
    public static String choose(String culture, MilitaryTier tier, UUID soldier) {
        String c = culture.replace("millenaire:", "");
        if (c.equals("japanese") || c.equals("inuits")) {
            return null;
        }
        long h = soldier.getMostSignificantBits() * 31 + soldier.getLeastSignificantBits() + 0x5BEA75L;
        if (Math.floorMod(h, 1000L) >= SHARE * 1000) {
            return null;
        }
        boolean big = tier == MilitaryTier.GARRISON || tier == MilitaryTier.STRONGHOLD;
        String shape = switch (c) {
            case "norman" -> big && Math.floorMod(h >>> 9, 2L) == 0 ? "heatershield" : "kiteshield";
            case "byzantines" -> Math.floorMod(h >>> 9, 3L) == 0 ? "roundshield" : "ellipticalshield";
            default -> "roundshield";
        };
        String material = c.equals("mayan") ? "wood" : switch (tier) {
            case NONE, WATCH -> "wood";
            case GUARD_POST -> Math.floorMod(h >>> 13, 2L) == 0 ? "wood" : "iron";
            case GARRISON -> "iron";
            case STRONGHOLD -> "steel";
        };
        return "magistuarmory:" + material + "_" + shape;
    }

    /** Armour a carried shield gives (it is never raised to block): 1 wood, 2 iron, 3 steel. */
    public static int armour(String shield) {
        return Math.max(1, Pavise.armour(shield) - 1);
    }

    public static List<String> cultureShapes(String culture) {
        return switch (culture.replace("millenaire:", "")) {
            case "norman" -> List.of("kiteshield", "heatershield");
            case "byzantines" -> List.of("ellipticalshield", "roundshield");
            case "japanese", "inuits" -> List.of();
            default -> List.of("roundshield");
        };
    }
}

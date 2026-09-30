package dev.hywmill.garrison.equip;

import net.minecraft.world.item.DyeColor;

import java.util.List;
import java.util.UUID;

/**
 * A village's livery (post-M5): two dye colours its soldiers wear and its shields bear, so each army can be told apart on
 * the battlefield. Chosen once and kept: the pair least like the liveries of the villages around it (so neighbours, who are
 * the ones that fight each other, rarely share a colour), then by its culture's taste; ties by the village id. Pure.
 *
 * <p>The second colour contrasts with the first by the rule of tincture: a metal (white, yellow) with a colour, or the
 * other way round.
 */
public final class VillageLivery {
    private VillageLivery() {}

    public static final List<DyeColor> METALS = List.of(DyeColor.WHITE, DyeColor.YELLOW);

    public static boolean metal(DyeColor c) {
        return METALS.contains(c);
    }

    /**
     * @param taste      the culture's preferred colours, best first (may be empty: every dye colour then serves)
     * @param neighbours liveries of the villages around, as {primary, secondary} dye ids
     * @return {primary, secondary} dye ids
     */
    public static int[] choose(List<DyeColor> taste, List<int[]> neighbours, UUID village) {
        DyeColor[] all = DyeColor.values();
        DyeColor best = null;
        double bestCost = Double.MAX_VALUE;
        for (DyeColor p : all) {
            double cost = 10.0 * count(neighbours, 0, p.getId()) + 3.0 * count(neighbours, 1, p.getId()) + tasteCost(taste, p) + tie(village, p, 0);
            if (cost < bestCost) {
                bestCost = cost;
                best = p;
            }
        }
        DyeColor second = null;
        bestCost = Double.MAX_VALUE;
        for (DyeColor s : all) {
            if (s == best) {
                continue;
            }
            double cost = (metal(best) == metal(s) ? 20.0 : 0.0) + 8.0 * pairs(neighbours, best.getId(), s.getId()) + 2.0 * count(neighbours, 1, s.getId())
                    + tasteCost(taste, s) + tie(village, s, 1);
            if (cost < bestCost) {
                bestCost = cost;
                second = s;
            }
        }
        return new int[]{best.getId(), second.getId()};
    }

    private static double tasteCost(List<DyeColor> taste, DyeColor c) {
        int i = taste.indexOf(c);
        return taste.isEmpty() ? 0 : i < 0 ? 6.0 : i * 0.4;
    }

    private static double tie(UUID village, DyeColor c, int salt) {
        long h = village.getMostSignificantBits() * 31 + village.getLeastSignificantBits() + c.getId() * 0x9E3779B97F4A7C15L + salt;
        return Math.floorMod(h, 1000L) / 100000.0; // < 0.01: only ever breaks ties
    }

    private static int count(List<int[]> ns, int idx, int id) {
        int n = 0;
        for (int[] x : ns) {
            if (x[idx] == id) {
                n++;
            }
        }
        return n;
    }

    private static int pairs(List<int[]> ns, int p, int s) {
        int n = 0;
        for (int[] x : ns) {
            if (x[0] == p && x[1] == s) {
                n++;
            }
        }
        return n;
    }

    /** The RGB a dye colour gives cloth. */
    public static int rgb(int dyeId) {
        return DyeColor.byId(dyeId).getTextureDiffuseColor() & 0xFFFFFF;
    }
}

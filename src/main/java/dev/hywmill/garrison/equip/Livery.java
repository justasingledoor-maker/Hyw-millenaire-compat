package dev.hywmill.garrison.equip;

import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Colours and arms of one unit (post-M5, cosmetic): the dye colours of its dyeable armour and the heraldry on its shield.
 * Pure and deterministic per unit (seeded by its roster id), so a unit looks the same after every re-equip and restart.
 *
 * <p>Heraldry follows the rule of tincture: a metal (white, yellow) is never laid on a metal, nor a colour on a colour.
 */
public final class Livery {
    private Livery() {}

    public static final List<DyeColor> METALS = List.of(DyeColor.WHITE, DyeColor.YELLOW);
    public static final List<DyeColor> COLOURS = List.of(DyeColor.RED, DyeColor.BLUE, DyeColor.BLACK, DyeColor.GREEN, DyeColor.PURPLE);
    /** Most layers on one shield (base colour not counted). */
    public static final int MAX_LAYERS = 3;

    private static Random random(UUID id, long salt) {
        return new Random(id.getMostSignificantBits() * 31 + id.getLeastSignificantBits() + salt);
    }

    /** Two different palette colours: {@code [0]} for head, chest and feet, {@code [1]} for the legs (parti-coloured hose). */
    public static int[] colours(UUID id, List<Integer> palette) {
        Random r = random(id, 0x11FE);
        int a = palette.get(r.nextInt(palette.size()));
        int b = a;
        for (int i = 0; i < 4 && b == a && palette.size() > 1; i++) {
            b = palette.get(r.nextInt(palette.size()));
        }
        return new int[]{a, b};
    }

    public record Layer(String pattern, DyeColor color) {}

    public record Arms(DyeColor base, List<Layer> layers) {}

    /**
     * The unit's arms: a field (metal or colour), usually an ordinary from {@code ordinaries} (geometric patterns), often a
     * charge from {@code charges} (emblems), sometimes a bordure; each in the opposite tincture group to the field, at most
     * {@link #MAX_LAYERS} layers and at least one. Pattern ids are namespaced strings; lists must be in a stable order.
     */
    public static Arms arms(UUID id, List<String> ordinaries, List<String> charges) {
        Random r = random(id, 0xA2A5);
        boolean metalField = r.nextBoolean();
        DyeColor field = pick(r, metalField ? METALS : COLOURS);
        List<DyeColor> contrast = metalField ? COLOURS : METALS;
        List<Layer> layers = new ArrayList<>();
        if (!ordinaries.isEmpty() && r.nextDouble() < 0.75) {
            layers.add(new Layer(pick(r, ordinaries), pick(r, contrast)));
        }
        if (!charges.isEmpty() && r.nextDouble() < 0.55) {
            layers.add(new Layer(pick(r, charges), pick(r, contrast)));
        }
        if (layers.size() < MAX_LAYERS && r.nextDouble() < 0.15) {
            layers.add(new Layer("minecraft:border", pick(r, contrast)));
        }
        if (layers.isEmpty()) {
            List<String> any = !ordinaries.isEmpty() ? ordinaries : charges;
            if (!any.isEmpty()) {
                layers.add(new Layer(pick(r, any), pick(r, contrast)));
            }
        }
        return new Arms(field, List.copyOf(layers.subList(0, Math.min(MAX_LAYERS, layers.size()))));
    }

    private static <T> T pick(Random r, List<T> list) {
        return list.get(r.nextInt(list.size()));
    }
}

package dev.hywmill.garrison.service;

import dev.hywmill.garrison.equip.EquipmentProfiles;
import dev.hywmill.military.MilitaryTier;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * What a supply convoy carries (post-M5): by theme, the culture's own food and drink, its soldiers' gear (from the equipment
 * profiles' levy and watch kits), and the odds and ends of an army on the move. Items of mods that are not installed are skipped.
 */
public final class ConvoyLoot {
    private ConvoyLoot() {}

    public static final List<String> THEMES = List.of("military", "provisions", "mixed", "trade", "siege", "armoury");

    static final Map<String, List<String>> FOOD = Map.of(
            "millenaire:norman", List.of("millenaire:cider", "millenaire:calva", "millenaire:boudin", "millenaire:tripes", "minecraft:bread", "minecraft:cooked_porkchop"),
            "millenaire:byzantines", List.of("millenaire:olives", "millenaire:oliveoil", "millenaire:feta", "millenaire:souvlaki", "millenaire:winebasic",
                    "millenaire:winefancy", "millenaire:grapes", "minecraft:bread"),
            "millenaire:indian", List.of("millenaire:vegcurry", "millenaire:chickencurry", "millenaire:rasgulla", "minecraft:bread"),
            "millenaire:mayan", List.of("millenaire:masa", "millenaire:wah", "millenaire:cacauhaa", "millenaire:maize"),
            "millenaire:japanese", List.of("millenaire:sake", "millenaire:udon", "millenaire:ikayaki", "millenaire:rice", "minecraft:cooked_cod"),
            "millenaire:seljuk", List.of("millenaire:yogurt", "millenaire:ayran", "millenaire:pide", "millenaire:lokum", "millenaire:helva", "millenaire:pistachios"),
            "millenaire:inuits", List.of("millenaire:bearmeat_cooked", "millenaire:wolfmeat_cooked", "millenaire:seafood_cooked", "millenaire:inuitpotatostew",
                    "millenaire:meatystew", "millenaire:bearstew"));
    static final List<String> PLAIN_FOOD = List.of("minecraft:bread", "minecraft:cooked_beef", "minecraft:baked_potato", "minecraft:apple", "minecraft:cooked_mutton");
    static final List<String> CAMP = List.of("minecraft:torch", "minecraft:lead", "minecraft:hay_block", "minecraft:lantern", "minecraft:string",
            "minecraft:leather", "minecraft:bowl", "minecraft:white_wool", "minecraft:campfire", "minecraft:saddle", "minecraft:bucket");
    static final List<String> MILITARY = List.of("minecraft:arrow", "minecraft:arrow", "minecraft:shield", "minecraft:iron_ingot", "minecraft:leather",
            "minecraft:bow", "minecraft:crossbow", "minecraft:iron_sword", "minecraft:flint");
    static final List<String> TRADE = List.of("millenaire:tannedhide", "millenaire:silk", "millenaire:cotton", "millenaire:denier", "millenaire:denier_argent",
            "minecraft:paper", "minecraft:book", "minecraft:white_wool", "minecraft:leather", "minecraft:gold_nugget", "minecraft:honey_bottle");
    static final List<String> SIEGE = List.of("minecraft:oak_log", "minecraft:stick", "minecraft:string", "minecraft:iron_ingot", "minecraft:ladder",
            "minecraft:scaffolding", "minecraft:arrow", "minecraft:flint_and_steel", "minecraft:gunpowder", "minecraft:oak_planks");

    /** Fills the cart: a few stacks by theme. */
    static void fill(Container box, String culture, String theme, SplittableRandom r) {
        List<ItemStack> out = new ArrayList<>();
        List<String> food = FOOD.getOrDefault(culture, PLAIN_FOOD);
        int foods = switch (theme) {
            case "provisions" -> 7;
            case "mixed" -> 4;
            case "military", "armoury", "siege" -> 2;
            default -> 3;
        };
        for (int i = 0; i < foods; i++) {
            add(out, pick(food, PLAIN_FOOD, r, 0.75), 4 + r.nextInt(12));
        }
        switch (theme) {
            case "military" -> {
                for (int i = 0; i < 4; i++) {
                    add(out, MILITARY.get(r.nextInt(MILITARY.size())), 4 + r.nextInt(16));
                }
                gear(out, culture, MilitaryTier.WATCH, 2, r);
            }
            case "armoury" -> gear(out, culture, r.nextDouble() < 0.3 ? MilitaryTier.GARRISON : MilitaryTier.GUARD_POST, 5, r);
            case "mixed" -> {
                add(out, MILITARY.get(r.nextInt(MILITARY.size())), 8 + r.nextInt(12));
                gear(out, culture, MilitaryTier.WATCH, 2, r);
                add(out, TRADE.get(r.nextInt(TRADE.size())), 2 + r.nextInt(6));
            }
            case "trade" -> {
                for (int i = 0; i < 5; i++) {
                    add(out, TRADE.get(r.nextInt(TRADE.size())), 2 + r.nextInt(10));
                }
            }
            case "siege" -> {
                for (int i = 0; i < 5; i++) {
                    add(out, SIEGE.get(r.nextInt(SIEGE.size())), 4 + r.nextInt(20));
                }
            }
            default -> { }
        }
        for (int i = 0, n = 2 + r.nextInt(3); i < n; i++) {
            add(out, CAMP.get(r.nextInt(CAMP.size())), 1 + r.nextInt(8));
        }
        int size = box.getContainerSize();
        for (int i = 0; i < out.size() && size > 0; i++) {
            int slot = r.nextInt(size);
            for (int k = 0; k < size && !box.getItem(slot).isEmpty(); k++) {
                slot = (slot + 1) % size;
            }
            if (box.getItem(slot).isEmpty()) {
                box.setItem(slot, out.get(i));
            }
        }
    }

    /** {@code n} pieces of the culture's soldiers' kit (levy and line kits at that tier). */
    private static void gear(List<ItemStack> out, String culture, MilitaryTier tier, int n, SplittableRandom r) {
        List<String> pieces = new ArrayList<>();
        EquipmentProfiles p = EquipmentProfiles.current();
        for (String role : List.of(EquipmentProfiles.LEVY, "")) {
            for (List<EquipmentProfiles.Kit> kits : p.kitCandidates(culture, tier, role, "line")) {
                for (EquipmentProfiles.Kit k : kits) {
                    k.pieces().values().forEach(v -> {
                        if (!v.equals(EquipmentProfiles.NONE)) {
                            pieces.add(v);
                        }
                    });
                }
            }
        }
        if (pieces.isEmpty()) {
            pieces.addAll(List.of("minecraft:leather_helmet", "minecraft:leather_chestplate", "minecraft:iron_sword", "minecraft:chainmail_helmet"));
        }
        for (int i = 0; i < n; i++) {
            add(out, pieces.get(r.nextInt(pieces.size())), 1);
        }
    }

    private static String pick(List<String> own, List<String> plain, SplittableRandom r, double ownShare) {
        return r.nextDouble() < ownShare ? own.get(r.nextInt(own.size())) : plain.get(r.nextInt(plain.size()));
    }

    private static void add(List<ItemStack> out, String id, int n) {
        String clean = id;
        for (char ch : new char[]{'{', '[', '|', ' ', '#'}) {
            int i = clean.indexOf(ch);
            if (i > 0) {
                clean = clean.substring(0, i);
            }
        }
        ItemStack s = ColumnService.stack(clean, n);
        if (!s.isEmpty()) {
            out.add(s);
        }
    }
}

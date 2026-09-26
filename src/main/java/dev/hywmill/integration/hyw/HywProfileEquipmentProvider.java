package dev.hywmill.integration.hyw;

import dev.hywmill.garrison.equip.EquipmentProfiles;
import dev.hywmill.garrison.spi.EquipmentProvider;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * M4 optional equipment profiles (id {@value #ID}): HYW's own equipment for the level first
 * (exactly as the {@code hyw} provider, so M3 level semantics hold), then, only if Epic Knights
 * ({@code magistuarmory}) is loaded, a per-slot overlay chosen by culture, tier and role from
 * {@code hywmill_equipment}. An overlay item is used only if it is registered, fits the slot
 * (armour) and, for weapons and offhand, belongs to a family HYW itself gives that unit; a slot
 * with no such item keeps HYW's equipment. HywMill has no compile-time Epic Knights dependency.
 */
public final class HywProfileEquipmentProvider implements EquipmentProvider {
    public static final String ID = "hyw_profiles";
    public static final String EK = "magistuarmory";

    public enum Verdict { OK, UNREGISTERED, WRONG_SLOT, NOT_HYW_FAMILY }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public int resolveLevel(UnitSpec unit, MilitaryTier tier, int requestedLevel) {
        return Math.max(0, requestedLevel);
    }

    @Override
    public int apply(Object entity, UnitSpec unit, int level) {
        if (!(entity instanceof BaseCombatEntity u)) {
            return -1;
        }
        u.setEquipment(level);
        return u.getEquipmentLevel();
    }

    @Override
    public int apply(Object entity, UnitSpec unit, int level, Context ctx) {
        int applied = apply(entity, unit, level);
        if (applied >= 0 && entity instanceof BaseCombatEntity u && ModList.get().isLoaded(EK)) {
            overlay(u, unit, ctx);
        }
        return applied;
    }

    @Override
    public boolean reequips() {
        return true;
    }

    private static void overlay(BaseCombatEntity u, UnitSpec unit, Context ctx) {
        EquipmentProfiles p = EquipmentProfiles.current();
        for (String slot : EquipmentProfiles.SLOTS) {
            String item = choose(p, unit.entityType(), slot, ctx);
            if (item != null) {
                u.setItemSlot(slotOf(slot), new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item))));
            }
        }
    }

    @Override
    public List<String> validate(java.util.Collection<UnitSpec> units) {
        EquipmentProfiles p = EquipmentProfiles.current();
        List<String> out = new ArrayList<>();
        java.util.Map<String, Verdict> itemVerdict = new java.util.TreeMap<>();
        java.util.Set<String> incompatible = new java.util.TreeSet<>();
        java.util.Set<String> fallback = new java.util.TreeSet<>();
        int entries = 0;
        for (var c : p.raw().entrySet()) {
            String culture = c.getKey().isEmpty() ? "defaults" : c.getKey();
            for (var t : c.getValue().entrySet()) {
                for (var r : t.getValue().entrySet()) {
                    for (var sl : r.getValue().entrySet()) {
                        for (UnitSpec u : units) {
                            if (!u.enabled() || !appliesTo(r.getKey(), u)) {
                                continue;
                            }
                            boolean any = false;
                            for (String item : sl.getValue()) {
                                Verdict v = check(u.entityType(), sl.getKey(), item);
                                if (v == Verdict.UNREGISTERED || v == Verdict.WRONG_SLOT) {
                                    itemVerdict.put(item + " as " + sl.getKey(), v);
                                } else if (v == Verdict.NOT_HYW_FAMILY) {
                                    incompatible.add(item + " for " + u.key() + " (" + sl.getKey() + ")");
                                } else {
                                    any = true;
                                }
                            }
                            if (!any) {
                                fallback.add(culture + " " + t.getKey() + " " + r.getKey() + " " + sl.getKey() + " for " + u.key());
                            }
                        }
                        entries += sl.getValue().size();
                    }
                }
            }
        }
        itemVerdict.forEach((k, v) -> out.add("INVALID " + k + ": " + v));
        incompatible.forEach(x -> out.add("INCOMPATIBLE " + x + ": not a family HYW gives this unit (skipped for it)"));
        fallback.forEach(x -> out.add("FALLBACK " + x + ": no usable item in this list (next list, else HYW's own item)"));
        out.add("equipcheck: " + entries + " profile entries; " + itemVerdict.size() + " invalid item/slot, " + incompatible.size()
                + " incompatible item/unit pairs, " + fallback.size() + " list/unit fallbacks; Epic Knights "
                + (ModList.get().isLoaded(EK) ? "loaded" : "NOT loaded (profiles inactive: every unit keeps HYW's equipment)"));
        return out;
    }

    /** Class roles apply to units of that class; duty roles and "all" to every unit. */
    static boolean appliesTo(String role, UnitSpec u) {
        return switch (role) {
            case "militia", "line", "ranged" -> role.equals(EquipmentProfiles.classRole(u.unitClass()));
            default -> true;
        };
    }

    /** The profile item for one slot, or null to keep HYW's own (deterministic by roster id). */
    static String choose(EquipmentProfiles p, String entityType, String slot, Context ctx) {
        for (List<String> list : p.candidates(ctx.culture(), ctx.tier(), ctx.dutyRole(), ctx.classRole(), slot)) {
            List<String> valid = new ArrayList<>();
            for (String id : list) {
                if (check(entityType, slot, id) == Verdict.OK) {
                    valid.add(id);
                }
            }
            if (!valid.isEmpty()) {
                long h = ctx.rosterId().getLeastSignificantBits() * 31 + ctx.rosterId().getMostSignificantBits() + slot.hashCode() * 17L;
                return valid.get((int) Math.floorMod(h, (long) valid.size()));
            }
        }
        return null;
    }

    /** Whether {@code itemId} may go into {@code slot} of HYW unit {@code entityType} (the M4 compatibility rule). */
    public static Verdict check(String entityType, String slot, String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return Verdict.UNREGISTERED;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        EquipmentSlot target = slotOf(slot);
        if (target.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
            Equipable eq = Equipable.get(new ItemStack(item));
            return eq != null && eq.getEquipmentSlot() == target ? Verdict.OK : Verdict.WRONG_SLOT;
        }
        return HywEkFamilies.families(entityType, slot).contains(EquipmentProfiles.family(itemId)) ? Verdict.OK : Verdict.NOT_HYW_FAMILY;
    }

    static EquipmentSlot slotOf(String slot) {
        return switch (slot) {
            case "mainhand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            default -> EquipmentSlot.FEET;
        };
    }
}

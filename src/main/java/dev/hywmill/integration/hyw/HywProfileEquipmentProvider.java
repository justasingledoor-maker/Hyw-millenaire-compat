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
        EquipmentProfiles.Look look = p.lookFor(ctx.dutyRole()); // post-M5 squads: their own kits, shields, colours and arms
        EquipmentProfiles.Kit kit = look != null ? pick(look.kits(), unit.entityType(), ctx) : null;
        if (kit == null) {
            kit = chooseKit(p, unit.entityType(), ctx);
        }
        for (String slot : EquipmentProfiles.SLOTS) {
            if (kit != null && EquipmentProfiles.ARMOUR.contains(slot)) {
                // one whole armour set: never a piece from another list (no plate helmet over cloth)
                String id = kit.piece(slot);
                u.setItemSlot(slotOf(slot), id.equals(EquipmentProfiles.NONE) ? ItemStack.EMPTY
                        : new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))));
                continue;
            }
            String item = look != null ? pickItem(look.items().get(slot), unit.entityType(), slot, ctx) : null;
            if (item == null) {
                item = choose(p, unit.entityType(), slot, ctx);
            }
            if (item != null) {
                u.setItemSlot(slotOf(slot), new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item))));
            }
        }
        decorate(u, p, ctx, look);
    }

    /** A usable kit of {@code kits} (deterministic by roster id), or null. */
    @javax.annotation.Nullable
    static EquipmentProfiles.Kit pick(List<EquipmentProfiles.Kit> kits, String entityType, Context ctx) {
        List<EquipmentProfiles.Kit> valid = new ArrayList<>();
        for (EquipmentProfiles.Kit k : kits) {
            if (kitUsable(entityType, k)) {
                valid.add(k);
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        long h = ctx.rosterId().getLeastSignificantBits() * 31 + ctx.rosterId().getMostSignificantBits() + 0x4B17L;
        return valid.get((int) Math.floorMod(h, (long) valid.size()));
    }

    /** A usable item of {@code items} for the slot (deterministic by roster id), or null. */
    @javax.annotation.Nullable
    static String pickItem(@javax.annotation.Nullable List<String> items, String entityType, String slot, Context ctx) {
        if (items == null) {
            return null;
        }
        List<String> valid = new ArrayList<>();
        for (String id : items) {
            if (check(entityType, slot, id) == Verdict.OK) {
                valid.add(id);
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        long h = ctx.rosterId().getLeastSignificantBits() * 31 + ctx.rosterId().getMostSignificantBits() + slot.hashCode() * 17L;
        return valid.get((int) Math.floorMod(h, (long) valid.size()));
    }

    /** The unit's armour kit (deterministic by roster id): from the first kit list with a kit whose every piece is usable. */
    @javax.annotation.Nullable
    static EquipmentProfiles.Kit chooseKit(EquipmentProfiles p, String entityType, Context ctx) {
        for (List<EquipmentProfiles.Kit> list : p.kitCandidates(ctx.culture(), ctx.tier(), ctx.dutyRole(), ctx.classRole())) {
            List<EquipmentProfiles.Kit> valid = new ArrayList<>();
            for (EquipmentProfiles.Kit k : list) {
                if (kitUsable(entityType, k)) {
                    valid.add(k);
                }
            }
            if (!valid.isEmpty()) {
                long h = ctx.rosterId().getLeastSignificantBits() * 31 + ctx.rosterId().getMostSignificantBits() + 0x4B17L;
                return valid.get((int) Math.floorMod(h, (long) valid.size()));
            }
        }
        return null;
    }

    static boolean kitUsable(String entityType, EquipmentProfiles.Kit k) {
        for (String slot : EquipmentProfiles.ARMOUR) {
            String id = k.piece(slot);
            if (!id.equals(EquipmentProfiles.NONE) && check(entityType, slot, id) != Verdict.OK) {
                return false;
            }
        }
        return true;
    }

    /** Livery: dyeable armour in the unit's two colours; a shield painted with its arms (vanilla banner components). */
    private static void decorate(BaseCombatEntity u, EquipmentProfiles p, Context ctx, @javax.annotation.Nullable EquipmentProfiles.Look look) {
        List<Integer> palette = look != null && !look.palette().isEmpty() ? look.palette() : p.palette(ctx.culture());
        int[] c = dev.hywmill.garrison.equip.Livery.colours(ctx.rosterId(), palette);
        for (String slot : EquipmentProfiles.ARMOUR) {
            ItemStack st = u.getItemBySlot(slotOf(slot));
            if (!st.isEmpty() && st.is(net.minecraft.tags.ItemTags.DYEABLE)) {
                st.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
                        new net.minecraft.world.item.component.DyedItemColor(slot.equals("legs") ? c[1] : c[0], false));
            }
        }
        ItemStack shield = u.getItemBySlot(EquipmentSlot.OFFHAND);
        if (p.heraldry() && shield.getItem() instanceof net.minecraft.world.item.ShieldItem) {
            if (look != null && !look.arms().isEmpty()) {
                // a squad bears its own arms (one of the look's, by roster id)
                long h = ctx.rosterId().getMostSignificantBits() ^ 0xA2A5L;
                paint(u, shield, look.arms().get((int) Math.floorMod(h, (long) look.arms().size())));
            } else {
                paint(u, shield, ctx.rosterId());
            }
        }
    }

    private static void paint(BaseCombatEntity u, ItemStack shield, dev.hywmill.garrison.equip.Livery.Arms arms) {
        var reg = u.level().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN);
        net.minecraft.world.level.block.entity.BannerPatternLayers.Builder b = new net.minecraft.world.level.block.entity.BannerPatternLayers.Builder();
        for (dev.hywmill.garrison.equip.Livery.Layer l : arms.layers()) {
            ResourceLocation id = ResourceLocation.tryParse(l.pattern());
            if (id != null) {
                reg.getHolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BANNER_PATTERN, id))
                        .ifPresent(h -> b.add(h, l.color()));
            }
        }
        shield.set(net.minecraft.core.component.DataComponents.BASE_COLOR, arms.base());
        shield.set(net.minecraft.core.component.DataComponents.BANNER_PATTERNS, b.build());
    }

    private static void paint(BaseCombatEntity u, ItemStack shield, java.util.UUID id) {
        var reg = u.level().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN);
        List<String> ordinaries = new ArrayList<>();
        reg.getTag(net.minecraft.tags.BannerPatternTags.NO_ITEM_REQUIRED).ifPresent(set -> set.forEach(h -> h.unwrapKey()
                .map(k -> k.location().toString()).filter(s -> !s.equals("minecraft:base")).ifPresent(ordinaries::add)));
        List<String> charges = new ArrayList<>();
        for (ResourceLocation k : reg.keySet()) {
            if (k.getNamespace().equals(EK)) {
                charges.add(k.toString());
            }
        }
        java.util.Collections.sort(ordinaries);
        java.util.Collections.sort(charges);
        dev.hywmill.garrison.equip.Livery.Arms arms = dev.hywmill.garrison.equip.Livery.arms(id, ordinaries, charges);
        net.minecraft.world.level.block.entity.BannerPatternLayers.Builder b = new net.minecraft.world.level.block.entity.BannerPatternLayers.Builder();
        for (dev.hywmill.garrison.equip.Livery.Layer l : arms.layers()) {
            reg.getHolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BANNER_PATTERN,
                    ResourceLocation.parse(l.pattern()))).ifPresent(h -> b.add(h, l.color()));
        }
        shield.set(net.minecraft.core.component.DataComponents.BASE_COLOR, arms.base());
        shield.set(net.minecraft.core.component.DataComponents.BANNER_PATTERNS, b.build());
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
        int kitCount = 0;
        java.util.Set<String> badKits = new java.util.TreeSet<>();
        for (var c : p.rawKits().entrySet()) {
            for (var t : c.getValue().entrySet()) {
                for (var r : t.getValue().entrySet()) {
                    for (EquipmentProfiles.Kit k : r.getValue()) {
                        kitCount++;
                        for (String slot : EquipmentProfiles.ARMOUR) {
                            String id = k.piece(slot);
                            if (id.equals(EquipmentProfiles.NONE)) {
                                continue;
                            }
                            Verdict v = check("", slot, id);
                            if (v == Verdict.UNREGISTERED || v == Verdict.WRONG_SLOT) {
                                badKits.add((c.getKey().isEmpty() ? "defaults" : c.getKey()) + " " + t.getKey() + " " + r.getKey() + " kit " + id
                                        + " as " + slot + ": " + v);
                            }
                        }
                    }
                }
            }
        }
        badKits.forEach(x -> out.add("INVALID KIT " + x + " (the kit is skipped)"));
        out.add("kits: " + kitCount + " armour kit(s), " + badKits.size() + " invalid piece(s); heraldry " + (p.heraldry() ? "on" : "off")
                + "; profile revision " + p.revision());
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

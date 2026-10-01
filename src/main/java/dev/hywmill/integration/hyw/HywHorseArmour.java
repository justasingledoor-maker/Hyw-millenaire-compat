package dev.hywmill.integration.hyw;

import dev.hywmill.core.HmLog;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity;
import ydmsama.hundred_years_war.main.entity.entities.HywHorseEntity;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * Period horse armour (post-M5, user request): HYW gives its riders' horses vanilla leather, iron and diamond horse armour by
 * equipment level. When an HYW horse joins the level (as its rider first spawns it, or when its chunk loads) its armour is
 * replaced by armour that fits the setting:
 * <ul>
 *   <li>low levels, and light horse (archers, light lancers) below the top: leather horse armour dyed in the rider's colours;</li>
 *   <li>heavy horse at the middle level, light horse at the top: Epic Knights' chainmail horse armour;</li>
 *   <li>heavy horse at the top: Epic Knights' plate barding (now and then the Addon's dark barding).</li>
 * </ul>
 * Never gold or diamond; without Epic Knights, iron stands in for mail and plate. Chosen by the horse (stable across reloads),
 * set through HYW's own horse armour so it keeps tracking and maintaining it. A horse without armour (level 0) keeps none.
 */
public final class HywHorseArmour {
    private HywHorseArmour() {}

    static final String EK = "magistuarmory", ADDON = "magistuarmoryaddon";
    static final int BROWN = 0x6B4A2A;

    /**
     * HYW's own setter for a horse's armour and the call that puts it on the horse (both protected), looked up once. HYW keeps
     * the horse's own armour apart from what it wears and re-applies it, so both are needed.
     */
    private static final class Setter {
        static final Method M = find("setOwnedHorseArmor", ItemStack.class);
        static final Method SHOW = find("syncOwnedHorseArmorVisibility");

        @Nullable
        static Method find(String name, Class<?>... args) {
            try {
                Method m = HywHorseEntity.class.getDeclaredMethod(name, args);
                m.setAccessible(true);
                return m;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                HmLog.warn("HYW horse armour setter not found ({}); horse armour is set in the body slot instead", ex.toString());
                return null;
            }
        }
    }

    public static void onJoin(EntityJoinLevelEvent e) {
        if (!e.getLevel().isClientSide() && e.getEntity() instanceof HywHorseEntity h) {
            dress(h);
        }
    }

    /**
     * HYW re-applies its own horse armour after the horse joins (and when its rider's equipment changes): about once a second
     * each HYW horse is checked again, a single comparison when it already wears the right armour.
     */
    public static void onTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post e) {
        if (e.getEntity() instanceof HywHorseEntity h && h.tickCount % 20 == 7 && !h.level().isClientSide()) {
            dress(h);
        }
    }

    /** Puts period armour on an HYW horse if it wears anything else. Returns true if it changed. */
    static boolean dress(HywHorseEntity h) {
        int level = h.getEquipmentLevel();
        ItemStack now = h.getBodyArmorItem();
        if (level <= 0 && now.isEmpty()) {
            return false;
        }
        BaseCombatEntity rider = h.getRiderEntity();
        boolean light = rider != null && dev.hywmill.garrison.equip.HorseArmour.light(BuiltInRegistries.ENTITY_TYPE.getKey(rider.getType()).getPath());
        String want = dev.hywmill.garrison.equip.HorseArmour.choose(level, light, h.getUUID().getLeastSignificantBits(), ModList.get().isLoaded(EK), ModList.get().isLoaded(ADDON));
        String have = now.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(now.getItem()).toString();
        if (want.equals(have) && (!want.equals(dev.hywmill.garrison.equip.HorseArmour.LEATHER) || now.get(DataComponents.DYED_COLOR) != null)) {
            return false; // the right armour already (leather dyed in the rider's colours)
        }
        ResourceLocation id = ResourceLocation.parse(want);
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return false;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
        if (want.equals(dev.hywmill.garrison.equip.HorseArmour.LEATHER)) {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour(rider), false));
        }
        boolean viaHyw = false;
        if (Setter.M != null) {
            try {
                Setter.M.invoke(h, stack);
                if (Setter.SHOW != null) {
                    Setter.SHOW.invoke(h);
                }
                viaHyw = Setter.SHOW != null;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                HmLog.warnThrottled("hyw.horsearmour", 600_000L, "HYW horse armour setter failed: {}", ex.toString());
            }
        }
        if (!viaHyw) {
            h.setItemSlot(EquipmentSlot.BODY, stack);
        }
        h.setDropChance(EquipmentSlot.BODY, 0.0f);
        HmLog.infoThrottled("hyw.horsearmour.swap", 30_000L, "HYW horse {} (level {}{}): {} -> {}", h.getUUID().toString().substring(0, 8), level,
                light ? ", light" : "", have.isEmpty() ? "none" : have, want);
        return true;
    }

    /** The rider's colours: its chest piece's dye, else its helmet's, else plain brown leather. */
    static int colour(@Nullable BaseCombatEntity rider) {
        if (rider != null) {
            for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.LEGS}) {
                DyedItemColor c = rider.getItemBySlot(s).get(DataComponents.DYED_COLOR);
                if (c != null) {
                    return c.rgb();
                }
            }
        }
        return BROWN;
    }
}

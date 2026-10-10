package dev.culturesarmory;

import dev.architectury.platform.Platform;
import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.culturesarmory.client.ArmoryModels;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * Cultures Armory: an Epic Knights add-on with the armour of other cultures, made the Epic Knights way (its armour types,
 * dyeable items and model layers), so the pieces behave like Epic Knights' own: dyeing, rendering, repair, stats.
 */
@Mod(CulturesArmory.ID)
public final class CulturesArmory {
    public static final String ID = "culturesarmory";
    /** Epic Knights' armour tab: the pieces are listed there, after its own. */
    static final ResourceKey<CreativeModeTab> EK_ARMOR_TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB,
            ResourceLocation.fromNamespaceAndPath("magistuarmory", "armor"));
    /** Epic Knights' weapons tab: the weapons and the bow. */
    static final ResourceKey<CreativeModeTab> EK_WEAPONS_TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB,
            ResourceLocation.fromNamespaceAndPath("magistuarmory", "weapons"));

    public CulturesArmory() {
        ArmoryItems.INSTANCE.init();
        ArmoryArmorTypes.init();
        for (RegistrySupplier<? extends Item> item : ArmoryItems.INSTANCE.armorItems) {
            if (item != null) {
                CreativeTabRegistry.append(EK_ARMOR_TAB, item);
            }
        }
        for (RegistrySupplier<? extends Item> item : ArmoryItems.INSTANCE.weaponItems) {
            if (item != null) {
                CreativeTabRegistry.append(EK_WEAPONS_TAB, item);
            }
        }
        for (RegistrySupplier<? extends Item> item : ArmoryItems.INSTANCE.rangedWeaponItems) {
            if (item != null) {
                CreativeTabRegistry.append(EK_WEAPONS_TAB, item);
            }
        }
        if (Platform.getEnv() == Dist.CLIENT) {
            ArmoryModels.INSTANCE.init(ArmoryItems.INSTANCE);
        }
    }
}

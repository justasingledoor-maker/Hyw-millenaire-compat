package dev.culturesarmory.client;

import dev.architectury.registry.client.rendering.ColorHandlerRegistry;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.culturesarmory.ArmoryItems;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.DyedItemColor;

/** The dyed horse armour's icon: its first layer tinted by the dye (the horse itself is tinted by the game's armour layer). */
public final class BardingColours {
    private BardingColours() {}

    @SuppressWarnings("unchecked")
    public static void register() {
        for (RegistrySupplier<Item> item : ArmoryItems.DYED_BARDINGS) {
            ColorHandlerRegistry.registerItemColors((stack, layer) -> layer > 0 ? -1
                    : FastColor.ARGB32.opaque(DyedItemColor.getOrDefault(stack, 0x8E2A2A)), item);
        }
    }
}

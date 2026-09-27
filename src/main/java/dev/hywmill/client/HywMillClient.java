package dev.hywmill.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hywmill.HywMill;
import dev.hywmill.net.PoliticsPayloads;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-only registration (M5-UI): the Politics key, in a HywMill category in Controls, unbound by default.
 * Pressing it asks the server for the Politics screen.
 */
public final class HywMillClient {
    public static final KeyMapping POLITICS = new KeyMapping("key.hywmill.politics", InputConstants.UNKNOWN.getValue(), "key.categories.hywmill");

    private HywMillClient() {}

    /** Mod-bus event (routed to the mod bus automatically). */
    @EventBusSubscriber(modid = HywMill.MODID, value = Dist.CLIENT)
    public static final class ModEvents {
        private ModEvents() {}

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(POLITICS);
        }
    }

    @EventBusSubscriber(modid = HywMill.MODID, value = Dist.CLIENT)
    public static final class GameEvents {
        private GameEvents() {}

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            while (POLITICS.consumeClick()) {
                if (mc.player != null && mc.getConnection() != null && mc.getConnection().hasChannel(PoliticsPayloads.OpenPolitics.TYPE)) {
                    PacketDistributor.sendToServer(new PoliticsPayloads.OpenPolitics());
                }
            }
        }
    }
}

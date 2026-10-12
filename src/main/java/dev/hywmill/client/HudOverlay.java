package dev.hywmill.client;

import dev.hywmill.HywMill;
import dev.hywmill.net.HudPayloads;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The HUD (1.7), client side: a small bar at the top of the screen with the village the player stands in (its name, what it
 * is, whom it answers to), below any boss bars (sieges, armies); and, at the left, the player's orders and service. It draws
 * what the server last sent and nothing else. The state is the client's own (one connection), cleared on logout.
 */
public final class HudOverlay {
    private HudOverlay() {}

    private static HudPayloads.Hud hud = HudPayloads.Hud.EMPTY;
    private static long villageSince;
    /** Where the boss bars drawn this frame end (screen y). */
    private static int bossBottom;

    public static void accept(HudPayloads.Hud h) {
        if (!h.village().equals(hud.village())) {
            villageSince = System.currentTimeMillis();
        }
        hud = h;
    }

    static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        int top = bossBottom;
        bossBottom = 0;
        if (mc.options.hideGui || mc.player == null || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        Font font = mc.font;
        int w = g.guiWidth();
        if (!hud.village().isEmpty()) {
            // fully visible for a few seconds after entering, then quieter
            float age = (System.currentTimeMillis() - villageSince) / 1000f;
            int alpha = age < 6 ? 255 : 170;
            String line = hud.village() + "  ·  " + hud.status() + (hud.owner().isEmpty() ? "" : "  ·  " + hud.owner());
            int tw = font.width(line);
            if (tw > w - 24) {
                line = font.plainSubstrByWidth(line, w - 30) + "…";
                tw = font.width(line);
            }
            int x = (w - tw) / 2, y = top + 3;
            g.fill(x - 6, y - 2, x + tw + 6, y + 10, (alpha * 100 / 255) << 24);
            int swatch = hud.colour() >= 0 ? hud.colour() : 0xAAAAAA;
            g.fill(x - 6, y - 2, x - 4, y + 10, (alpha << 24) | (swatch & 0xFFFFFF));
            int nameW = font.width(hud.village());
            g.drawString(font, hud.village(), x, y, (alpha << 24) | 0xFFE9B0, false);
            if (line.length() > hud.village().length()) {
                g.drawString(font, line.substring(hud.village().length()), x + nameW, y, (alpha << 24) | 0xD0D0D0, false);
            }
        }
        if (!hud.orders().isEmpty()) {
            int x = 4, y = g.guiHeight() / 3;
            int bw = 0;
            for (String s : hud.orders()) {
                bw = Math.max(bw, Math.min(font.width(s), 220));
            }
            int lines = 0;
            for (String s : hud.orders()) {
                lines += Math.max(1, font.split(net.minecraft.network.chat.Component.literal(s), 220).size());
            }
            g.fill(x - 2, y - 3, x + bw + 4, y + lines * 10 + 1, 0x80000000);
            int row = 0;
            for (int i = 0; i < hud.orders().size(); i++) {
                int colour = i == 0 ? 0xFFFFD27A : 0xFFE0E0E0;
                for (var seq : font.split(net.minecraft.network.chat.Component.literal(hud.orders().get(i)), 220)) {
                    g.drawString(font, seq, x, y + row * 10, colour, false);
                    row++;
                }
            }
        }
    }

    @EventBusSubscriber(modid = HywMill.MODID, value = Dist.CLIENT)
    public static final class ModEvents {
        private ModEvents() {}

        @SubscribeEvent
        public static void layers(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, ResourceLocation.fromNamespaceAndPath(HywMill.MODID, "village_hud"), HudOverlay::render);
        }
    }

    @EventBusSubscriber(modid = HywMill.MODID, value = Dist.CLIENT)
    public static final class GameEvents {
        private GameEvents() {}

        @SubscribeEvent
        public static void boss(CustomizeGuiOverlayEvent.BossEventProgress event) {
            bossBottom = Math.max(bossBottom, event.getY() + event.getIncrement());
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            hud = HudPayloads.Hud.EMPTY;
        }
    }
}

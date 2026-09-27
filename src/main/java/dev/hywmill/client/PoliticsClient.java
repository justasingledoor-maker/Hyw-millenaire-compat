package dev.hywmill.client;

import dev.hywmill.net.PoliticsPayloads;
import dev.hywmill.net.PoliticsSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client side of the Politics screen (M5-UI). Only ever reached from a client-bound payload handler, so it
 * is never loaded on a dedicated server. It shows what the server sent and submits intents; nothing else.
 */
public final class PoliticsClient {
    private PoliticsClient() {}

    public static void onSnapshot(PoliticsSnapshot s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PoliticsScreen screen) {
            screen.update(s);
        } else {
            mc.setScreen(new PoliticsScreen(s));
        }
    }

    public static void onResult(PoliticsPayloads.ActionResult r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PoliticsScreen screen) {
            screen.result(r);
        } else if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Politics] " + r.message()), false);
        }
    }
}

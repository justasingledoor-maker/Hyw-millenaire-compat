package dev.hywmill.client;

import dev.hywmill.net.RecruitPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client side of the Muster Roll screen; reached only from client-bound payload handlers. It shows and sends intents. */
public final class RecruitClient {
    private RecruitClient() {}

    public static void onView(RecruitPayloads.View v) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MusterRollScreen s && s.pos().equals(v.pos())) {
            s.update(v);
        } else {
            mc.setScreen(new MusterRollScreen(v));
        }
    }

    /** The squads arrive right after the view; kept for a screen that opens a moment later. */
    private static RecruitPayloads.Squads pending;

    public static void onSquads(RecruitPayloads.Squads s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MusterRollScreen scr && scr.pos().equals(s.pos())) {
            scr.squads(s);
        } else {
            pending = s;
        }
    }

    @javax.annotation.Nullable
    static RecruitPayloads.Squads pendingFor(net.minecraft.core.BlockPos pos) {
        return pending != null && pending.pos().equals(pos) ? pending : null;
    }

    public static void onResult(RecruitPayloads.Result r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MusterRollScreen s) {
            s.result(r);
        } else if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Muster Roll] " + r.message()), false);
        }
    }
}

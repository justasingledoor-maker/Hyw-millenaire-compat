package dev.hywmill.net;

import dev.hywmill.HywMill;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/** The small M5-UI payloads. Client → server: intents only; the server re-validates everything. */
public final class PoliticsPayloads {
    private PoliticsPayloads() {}

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HywMill.MODID, path);
    }

    /** Client → server: open the Politics screen (the server picks the nearest village as home). */
    public record OpenPolitics() implements CustomPacketPayload {
        public static final Type<OpenPolitics> TYPE = new Type<>(id("open_politics"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenPolitics> CODEC = StreamCodec.unit(new OpenPolitics());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: select a village in the list (the server answers with a fresh snapshot). */
    public record SelectVillage(UUID home, UUID village) implements CustomPacketPayload {
        public static final Type<SelectVillage> TYPE = new Type<>(id("select_village"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SelectVillage> CODEC = StreamCodec.composite(
                net.minecraft.core.UUIDUtil.STREAM_CODEC, SelectVillage::home, net.minecraft.core.UUIDUtil.STREAM_CODEC, SelectVillage::village,
                SelectVillage::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: an intent ({@code action} as offered in the snapshot) from {@code home} towards {@code target}. */
    public record SubmitAction(String action, UUID home, UUID target, int amount) implements CustomPacketPayload {
        public static final Type<SubmitAction> TYPE = new Type<>(id("submit_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SubmitAction> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), SubmitAction::action, net.minecraft.core.UUIDUtil.STREAM_CODEC, SubmitAction::home,
                net.minecraft.core.UUIDUtil.STREAM_CODEC, SubmitAction::target, ByteBufCodecs.VAR_INT, SubmitAction::amount, SubmitAction::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → client: the outcome of an intent (the same result the commands print). */
    public record ActionResult(boolean ok, String code, String message) implements CustomPacketPayload {
        public static final Type<ActionResult> TYPE = new Type<>(id("action_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionResult> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, ActionResult::ok, ByteBufCodecs.stringUtf8(64), ActionResult::code, ByteBufCodecs.stringUtf8(512),
                ActionResult::message, ActionResult::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @Nullable
    public static ActionResult of(dev.hywmill.politics.api.PoliticsActions.ActionResult r) {
        String m = r.message().length() > 500 ? r.message().substring(0, 500) : r.message();
        return new ActionResult(r.ok(), r.code(), m);
    }
}

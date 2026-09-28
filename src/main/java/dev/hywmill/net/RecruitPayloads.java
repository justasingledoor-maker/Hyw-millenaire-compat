package dev.hywmill.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** Muster Roll payloads. Client → server: intents only; the server re-validates standing, prices and money. */
public final class RecruitPayloads {
    private RecruitPayloads() {}

    /** One offer as shown: {@code key} is sent back to hire it; {@code price} in deniers per unit. */
    public record OfferView(String key, String label, String gear, int price) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OfferView> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), OfferView::key, ByteBufCodecs.stringUtf8(96), OfferView::label, ByteBufCodecs.stringUtf8(32),
                OfferView::gear, ByteBufCodecs.VAR_INT, OfferView::price, OfferView::new);
    }

    /** Server → client: the Muster Roll screen (header: village, culture, tier, standing, gear; money in deniers). */
    public record View(BlockPos pos, String header, int money, int radius, boolean owner, List<OfferView> offers) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(PoliticsPayloads.id("muster_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, View::pos, ByteBufCodecs.stringUtf8(512), View::header, ByteBufCodecs.VAR_INT, View::money,
                ByteBufCodecs.VAR_INT, View::radius, ByteBufCodecs.BOOL, View::owner, OfferView.CODEC.apply(ByteBufCodecs.list(64)), View::offers,
                View::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → client: the outcome of a hire or radius change. */
    public record Result(boolean ok, String message) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(PoliticsPayloads.id("muster_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Result::ok, ByteBufCodecs.stringUtf8(512), Result::message, Result::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: hire {@code count} of offer {@code key} at the Muster Roll at {@code pos}. */
    public record Hire(BlockPos pos, String key, int count) implements CustomPacketPayload {
        public static final Type<Hire> TYPE = new Type<>(PoliticsPayloads.id("muster_hire"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hire> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Hire::pos, ByteBufCodecs.stringUtf8(64), Hire::key, ByteBufCodecs.VAR_INT, Hire::count, Hire::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: set the spawn radius (the placer only). */
    public record SetRadius(BlockPos pos, int radius) implements CustomPacketPayload {
        public static final Type<SetRadius> TYPE = new Type<>(PoliticsPayloads.id("muster_radius"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetRadius> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetRadius::pos, ByteBufCodecs.VAR_INT, SetRadius::radius, SetRadius::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}

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

    /**
     * Server → client: the Muster Roll screen (header: village, culture, tier, standing, gear; money in deniers). {@code colour1} and
     * {@code colour2}: the player's chosen colours for hired soldiers, as dye ids (-1: none chosen, the village's own).
     */
    public record View(BlockPos pos, String header, int money, int radius, boolean owner, List<OfferView> offers, int colour1, int colour2)
            implements CustomPacketPayload {
        public View(BlockPos pos, String header, int money, int radius, boolean owner, List<OfferView> offers) {
            this(pos, header, money, radius, owner, offers, -1, -1);
        }

        public static final Type<View> TYPE = new Type<>(PoliticsPayloads.id("muster_view"));
        private static final StreamCodec<RegistryFriendlyByteBuf, List<OfferView>> OFFERS = OfferView.CODEC.apply(ByteBufCodecs.list(64));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.of((buf, v) -> {
            BlockPos.STREAM_CODEC.encode(buf, v.pos());
            buf.writeUtf(v.header(), 512);
            buf.writeVarInt(v.money());
            buf.writeVarInt(v.radius());
            buf.writeBoolean(v.owner());
            OFFERS.encode(buf, v.offers());
            buf.writeVarInt(v.colour1() + 1);
            buf.writeVarInt(v.colour2() + 1);
        }, buf -> new View(BlockPos.STREAM_CODEC.decode(buf), buf.readUtf(512), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                OFFERS.decode(buf), buf.readVarInt() - 1, buf.readVarInt() - 1));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One culture squad as shown (post-M5): hire it by sending {@code Hire(pos, "squad:" + id, 1)}. {@code refusal} is empty when the
     * player may hire it; {@code price} in deniers for the whole squad.
     */
    public record SquadView(String id, String name, String category, String quality, String roster, String gear, int size, int price,
                            String refusal, String description) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SquadView> CODEC = StreamCodec.of((buf, v) -> {
            buf.writeUtf(v.id(), 64);
            buf.writeUtf(v.name(), 64);
            buf.writeUtf(v.category(), 16);
            buf.writeUtf(v.quality(), 16);
            buf.writeUtf(v.roster(), 256);
            buf.writeUtf(v.gear(), 32);
            buf.writeVarInt(v.size());
            buf.writeVarInt(v.price());
            buf.writeUtf(v.refusal(), 128);
            buf.writeUtf(v.description(), 256);
        }, buf -> new SquadView(buf.readUtf(64), buf.readUtf(64), buf.readUtf(16), buf.readUtf(16), buf.readUtf(256), buf.readUtf(32),
                buf.readVarInt(), buf.readVarInt(), buf.readUtf(128), buf.readUtf(256)));
    }

    /** Server → client (post-M5): the village's culture squads, sent with every {@link View}. */
    public record Squads(BlockPos pos, List<SquadView> squads) implements CustomPacketPayload {
        public static final Type<Squads> TYPE = new Type<>(PoliticsPayloads.id("muster_squads"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Squads> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Squads::pos, SquadView.CODEC.apply(ByteBufCodecs.list(32)), Squads::squads, Squads::new);

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

    /** Client → server (post-M5): the player's colours for the soldiers they hire, as dye ids (-1: none, the village's own). */
    public record SetColours(BlockPos pos, int colour1, int colour2) implements CustomPacketPayload {
        public static final Type<SetColours> TYPE = new Type<>(PoliticsPayloads.id("muster_colours"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetColours> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetColours::pos, ByteBufCodecs.VAR_INT, c -> c.colour1() + 1, ByteBufCodecs.VAR_INT, c -> c.colour2() + 1,
                (p, a, b) -> new SetColours(p, a - 1, b - 1));

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

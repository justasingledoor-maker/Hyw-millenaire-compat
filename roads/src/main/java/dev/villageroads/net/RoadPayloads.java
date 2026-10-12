package dev.villageroads.net;

import dev.villageroads.VillageRoads;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The staff's messages: the server opens the survey screen; the player asks for a road (or to take up his last). */
public final class RoadPayloads {
    private RoadPayloads() {}

    /**
     * The player's choices, packed: mode (0 road, 1 bridge), road style, bridge style, width. Kept on the staff so the screen
     * opens as he left it.
     */
    public record Choices(int mode, int road, int bridge, int width) {
        public static final Choices DEFAULT = new Choices(0, 2, 0, 3);

        public int pack() {
            return mode | road << 4 | bridge << 8 | width << 12;
        }

        public static Choices unpack(int v) {
            return v == 0 ? DEFAULT : new Choices(v & 15, v >> 4 & 15, v >> 8 & 15, v >> 12 & 15);
        }
    }

    /** Server to player: open the survey screen for these two points. */
    public record OpenSurvey(BlockPos a, BlockPos b, int choices) implements CustomPacketPayload {
        public static final Type<OpenSurvey> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VillageRoads.ID, "open_survey"));
        public static final StreamCodec<ByteBuf, OpenSurvey> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenSurvey::a, BlockPos.STREAM_CODEC, OpenSurvey::b, ByteBufCodecs.VAR_INT, OpenSurvey::choices, OpenSurvey::new);

        @Override
        public Type<OpenSurvey> type() {
            return TYPE;
        }
    }

    /** Player to server: build it. */
    public record Build(BlockPos a, BlockPos b, int choices) implements CustomPacketPayload {
        public static final Type<Build> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VillageRoads.ID, "build"));
        public static final StreamCodec<ByteBuf, Build> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Build::a, BlockPos.STREAM_CODEC, Build::b, ByteBufCodecs.VAR_INT, Build::choices, Build::new);

        @Override
        public Type<Build> type() {
            return TYPE;
        }
    }

    /** Player to server: take up my last road. */
    public record Undo() implements CustomPacketPayload {
        public static final Type<Undo> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VillageRoads.ID, "undo"));
        public static final StreamCodec<ByteBuf, Undo> CODEC = StreamCodec.unit(new Undo());

        @Override
        public Type<Undo> type() {
            return TYPE;
        }
    }
}

package dev.hywmill.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** The HUD (1.7): server → client only, sent when what the player should see changes. */
public final class HudPayloads {
    private HudPayloads() {}

    /**
     * The village bar and the orders panel. {@code village} empty: the player is in no village (no bar). {@code status}: what
     * the village is ("Capital of a realm of 3 villages", "Province", "Vassal", "Independent village"); {@code owner}: whom it
     * answers to, or empty; {@code colour}: its livery colour (RGB), or -1. {@code orders}: the player's standing orders and
     * service (empty: no panel), first line the heading.
     */
    public record Hud(String village, String status, String owner, int colour, List<String> orders) implements CustomPacketPayload {
        public static final Type<Hud> TYPE = new Type<>(PoliticsPayloads.id("hud"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hud> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(96), Hud::village, ByteBufCodecs.stringUtf8(128), Hud::status, ByteBufCodecs.stringUtf8(160), Hud::owner,
                ByteBufCodecs.INT, Hud::colour, ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(10)), Hud::orders, Hud::new);

        public static final Hud EMPTY = new Hud("", "", "", -1, List.of());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}

package dev.hywmill.net;

import dev.hywmill.politics.realm.Territory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** Map markers (1.8): server → client only; shown on JourneyMap when it is installed. */
public final class MapPayloads {
    private MapPayloads() {}

    public enum Kind { VILLAGE, CAPITAL, PROVINCE, VASSAL, HOST, SIEGE, BATTLE, ORDER, RELIEF, COLUMN }

    /**
     * One marker: a stable {@code id}, its kind, where, a short {@code label} (drawn on the map), the {@code detail} (shown on
     * hover), a colour (RGB) and, for an event with a clock, the game time it is due ({@code dueAt}, -1: none); the map counts
     * down to it live.
     */
    public record Marker(String id, Kind kind, int x, int z, String label, String detail, int colour, long dueAt) {}

    public record Markers(List<Marker> markers) implements CustomPacketPayload {
        public static final Type<Markers> TYPE = new Type<>(PoliticsPayloads.id("map_markers"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Markers> CODEC = StreamCodec.of(Markers::write, Markers::read);
        public static final int MAX = 400;

        static void write(RegistryFriendlyByteBuf buf, Markers m) {
            int n = Math.min(MAX, m.markers().size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                Marker k = m.markers().get(i);
                buf.writeUtf(k.id(), 96);
                buf.writeByte(k.kind().ordinal());
                buf.writeVarInt(k.x());
                buf.writeVarInt(k.z());
                buf.writeUtf(k.label(), 128);
                buf.writeUtf(k.detail(), 512);
                buf.writeInt(k.colour());
                buf.writeVarLong(k.dueAt() + 1);
            }
        }

        static Markers read(FriendlyByteBuf buf) {
            int n = Math.min(MAX, buf.readVarInt());
            List<Marker> out = new ArrayList<>(n);
            Kind[] kinds = Kind.values();
            for (int i = 0; i < n; i++) {
                String id = buf.readUtf(96);
                int kind = buf.readByte();
                int x = buf.readVarInt(), z = buf.readVarInt();
                String label = buf.readUtf(128), detail = buf.readUtf(512);
                int colour = buf.readInt();
                long due = buf.readVarLong() - 1;
                out.add(new Marker(id, kinds[Math.max(0, Math.min(kinds.length - 1, kind))], x, z, label, detail, colour, due));
            }
            return new Markers(out);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 1.10: a state on the map's borders: its {@code name} and what it is ({@code detail}, on hover), its colour (RGB), and
     * whether it is a realm's vassals' land (drawn fainter in their sovereign's colour).
     */
    public record Owner(String name, String detail, int colour, boolean vassals) {}

    /** 1.10: the map's borders: the states and their villages (each site's owner an index into {@code owners}); the client draws the land. */
    public record Borders(List<Owner> owners, List<Territory.Site> sites) implements CustomPacketPayload {
        public static final Type<Borders> TYPE = new Type<>(PoliticsPayloads.id("map_borders"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Borders> CODEC = StreamCodec.of(Borders::write, Borders::read);
        public static final int MAX = 1024;
        public static final Borders EMPTY = new Borders(List.of(), List.of());

        static void write(RegistryFriendlyByteBuf buf, Borders b) {
            int n = Math.min(MAX, b.owners().size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                Owner o = b.owners().get(i);
                buf.writeUtf(o.name(), 128);
                buf.writeUtf(o.detail(), 512);
                buf.writeInt(o.colour());
                buf.writeBoolean(o.vassals());
            }
            List<Territory.Site> sites = b.sites().stream().filter(s -> s.owner() >= 0 && s.owner() < n).limit(MAX).toList();
            buf.writeVarInt(sites.size());
            for (Territory.Site s : sites) {
                buf.writeVarInt(s.x());
                buf.writeVarInt(s.z());
                buf.writeVarInt(s.radius());
                buf.writeVarInt(s.owner());
            }
        }

        static Borders read(FriendlyByteBuf buf) {
            int n = Math.min(MAX, buf.readVarInt());
            List<Owner> owners = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                owners.add(new Owner(buf.readUtf(128), buf.readUtf(512), buf.readInt(), buf.readBoolean()));
            }
            int m = Math.min(MAX, buf.readVarInt());
            List<Territory.Site> sites = new ArrayList<>(m);
            for (int i = 0; i < m; i++) {
                int x = buf.readVarInt(), z = buf.readVarInt(), r = buf.readVarInt(), o = buf.readVarInt();
                if (o >= 0 && o < n) {
                    sites.add(new Territory.Site(x, z, r, o));
                }
            }
            return new Borders(owners, sites);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}

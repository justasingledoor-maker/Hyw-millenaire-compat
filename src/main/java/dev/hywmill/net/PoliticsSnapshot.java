package dev.hywmill.net;

import dev.hywmill.HywMill;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → client (M5-UI): everything the Politics screen shows, already decided by the server. The
 * client renders it and never computes anything political from it. Lists are bounded.
 *
 * @param home     the village the player is dealing with (nearest, or chosen); null: none nearby
 * @param selected the village selected in the list (the target of diplomacy actions); may equal home
 */
public record PoliticsSnapshot(@Nullable UUID home, String homeName, String culture, String standing, String effective, int reputation,
                               double grievance, int favor, int diplomacyPoints, List<String> notes, List<String> chronicle,
                               List<VillageRow> villages, @Nullable UUID selected, List<ActionRow> actions, List<String> envoys,
                               List<String> lent, List<String> honours) implements CustomPacketPayload {

    /**
     * A discovered village: its name, the player's standing there, and home's relation towards it. Post-M5: its livery
     * colours (RGB, -1: none) and its distance in metres (blocks) from the home village (-1: unknown, or it is home).
     */
    public record VillageRow(UUID id, String name, String standing, int relation, boolean truce, int colour1, int colour2, int distance) {
        public static final int NO_RELATION = Integer.MIN_VALUE;

        public VillageRow(UUID id, String name, String standing, int relation, boolean truce) {
            this(id, name, standing, relation, truce, -1, -1, -1);
        }
    }

    /**
     * An action the player may choose: {@code action} is the intent name the server accepts; {@code available}
     * and {@code requirement} are the server's verdict; {@code outcome} a coarse expected-outcome band.
     */
    public record ActionRow(String action, String label, boolean available, String requirement, String outcome) {}

    public static final int MAX_ROWS = 64;
    public static final int MAX_LINES = 12;

    public static final Type<PoliticsSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(HywMill.MODID, "politics_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PoliticsSnapshot> CODEC = StreamCodec.of((buf, s) -> {
        writeUuid(buf, s.home);
        buf.writeUtf(s.homeName);
        buf.writeUtf(s.culture);
        buf.writeUtf(s.standing);
        buf.writeUtf(s.effective);
        buf.writeVarInt(s.reputation);
        buf.writeDouble(s.grievance);
        buf.writeVarInt(s.favor);
        buf.writeVarInt(s.diplomacyPoints);
        writeLines(buf, s.notes);
        writeLines(buf, s.chronicle);
        buf.writeVarInt(Math.min(MAX_ROWS, s.villages.size()));
        for (VillageRow v : s.villages.subList(0, Math.min(MAX_ROWS, s.villages.size()))) {
            buf.writeUUID(v.id());
            buf.writeUtf(v.name());
            buf.writeUtf(v.standing());
            buf.writeInt(v.relation());
            buf.writeBoolean(v.truce());
            buf.writeInt(v.colour1());
            buf.writeInt(v.colour2());
            buf.writeVarInt(v.distance() + 1);
        }
        writeUuid(buf, s.selected);
        buf.writeVarInt(Math.min(MAX_ROWS, s.actions.size()));
        for (ActionRow a : s.actions.subList(0, Math.min(MAX_ROWS, s.actions.size()))) {
            buf.writeUtf(a.action());
            buf.writeUtf(a.label());
            buf.writeBoolean(a.available());
            buf.writeUtf(a.requirement());
            buf.writeUtf(a.outcome());
        }
        writeLines(buf, s.envoys);
        writeLines(buf, s.lent);
        writeLines(buf, s.honours);
    }, buf -> {
        UUID home = readUuid(buf);
        String homeName = buf.readUtf(), culture = buf.readUtf(), standing = buf.readUtf(), effective = buf.readUtf();
        int rep = buf.readVarInt();
        double g = buf.readDouble();
        int favor = buf.readVarInt(), dp = buf.readVarInt();
        List<String> notes = readLines(buf), chronicle = readLines(buf);
        int nv = Math.min(MAX_ROWS, buf.readVarInt());
        List<VillageRow> villages = new ArrayList<>();
        for (int i = 0; i < nv; i++) {
            villages.add(new VillageRow(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readBoolean(), buf.readInt(), buf.readInt(),
                    buf.readVarInt() - 1));
        }
        UUID selected = readUuid(buf);
        int na = Math.min(MAX_ROWS, buf.readVarInt());
        List<ActionRow> actions = new ArrayList<>();
        for (int i = 0; i < na; i++) {
            actions.add(new ActionRow(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readUtf(), buf.readUtf()));
        }
        return new PoliticsSnapshot(home, homeName, culture, standing, effective, rep, g, favor, dp, notes, chronicle, villages, selected,
                actions, readLines(buf), readLines(buf), readLines(buf));
    });

    static void writeUuid(RegistryFriendlyByteBuf buf, @Nullable UUID u) {
        buf.writeBoolean(u != null);
        if (u != null) {
            buf.writeUUID(u);
        }
    }

    @Nullable
    static UUID readUuid(RegistryFriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }

    static void writeLines(RegistryFriendlyByteBuf buf, List<String> lines) {
        int n = Math.min(MAX_LINES, lines.size());
        buf.writeVarInt(n);
        for (String l : lines.subList(lines.size() - n, lines.size())) {
            buf.writeUtf(l.length() > 300 ? l.substring(0, 300) : l);
        }
    }

    static List<String> readLines(RegistryFriendlyByteBuf buf) {
        int n = Math.min(MAX_LINES, buf.readVarInt());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(buf.readUtf());
        }
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

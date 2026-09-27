package dev.hywmill.settlement;

import dev.hywmill.politics.EnvoyKind;
import dev.hywmill.politics.EnvoyMission;
import dev.hywmill.politics.Favor;
import dev.hywmill.politics.GrievanceKind;
import dev.hywmill.politics.Grievances;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.Standing;
import dev.hywmill.politics.VillagePolitics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ledger format 5 codec for the politics model (optional keys; only non-default entries are written). */
public final class PoliticsNbt {
    private PoliticsNbt() {}

    public static CompoundTag save(VillagePolitics p) {
        CompoundTag t = new CompoundTag();
        ListTag players = new ListTag();
        for (Map.Entry<UUID, PoliticsRecord> e : p.players().entrySet()) {
            PoliticsRecord r = e.getValue();
            if (r.isDefault()) {
                continue;
            }
            CompoundTag c = new CompoundTag();
            c.putUUID("player", e.getKey());
            c.putString("status", r.status.name());
            c.putLong("statusSince", r.statusSince);
            Grievances g = r.grievances;
            if (!g.isEmpty()) {
                CompoundTag gt = new CompoundTag();
                gt.putDouble("value", g.value());
                gt.putLong("last", g.lastTick());
                if (g.lastKind() != null) {
                    gt.putString("kind", g.lastKind().name());
                }
                gt.putBoolean("inside", g.lastInside());
                gt.putBoolean("peacetime", g.lastPeacetime());
                gt.putBoolean("selfDefense", g.lastSelfDefense());
                gt.putIntArray("pos", new int[]{g.lastX(), g.lastY(), g.lastZ()});
                gt.putLong("peacetimeKill", g.peacetimeKillTick());
                c.put("grievance", gt);
            }
            if (!r.favor.isEmpty()) {
                c.putInt("favor", r.favor.points());
                c.putLong("favorEarned", r.favor.earnedTotal());
            }
            if (r.lastRequestTick >= 0) {
                c.putLong("lastRequest", r.lastRequestTick);
            }
            if (r.casualtiesOnErrands > 0) {
                c.putInt("casualties", r.casualtiesOnErrands);
            }
            if (!r.lastProposal.isEmpty()) {
                ListTag lp = new ListTag();
                r.lastProposal.forEach((target, tick) -> {
                    CompoundTag x = new CompoundTag();
                    x.putUUID("target", target);
                    x.putLong("tick", tick);
                    lp.add(x);
                });
                c.put("lastProposal", lp);
            }
            if (r.lastSowDiscord >= 0) {
                c.putLong("lastSow", r.lastSowDiscord);
                c.putInt("sowAttempts", r.sowAttempts);
            }
            if (r.lastErrandLoss >= 0) {
                c.putLong("lastErrandLoss", r.lastErrandLoss);
            }
            if (r.lastTrickle >= 0) {
                c.putLong("lastTrickle", r.lastTrickle);
            }
            players.add(c);
        }
        t.put("players", players);
        ListTag truces = new ListTag();
        p.truces().forEach((other, until) -> {
            CompoundTag x = new CompoundTag();
            x.putUUID("village", other);
            x.putLong("until", until);
            truces.add(x);
        });
        t.put("truces", truces);
        if (!p.discordCooldown().isEmpty()) {
            ListTag dc = new ListTag();
            p.discordCooldown().forEach((other, until) -> {
                CompoundTag x = new CompoundTag();
                x.putUUID("village", other);
                x.putLong("until", until);
                dc.add(x);
            });
            t.put("discordCooldown", dc);
        }
        ListTag chron = new ListTag();
        for (VillagePolitics.ChronicleEntry e : p.chronicle()) {
            CompoundTag x = new CompoundTag();
            x.putLong("t", e.tick());
            x.putString("text", e.text());
            chron.add(x);
        }
        if (!chron.isEmpty()) {
            t.put("chronicle", chron);
        }
        return t;
    }

    public static VillagePolitics load(CompoundTag t) {
        VillagePolitics p = new VillagePolitics();
        ListTag players = t.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag c = players.getCompound(i);
            if (!c.hasUUID("player")) {
                continue;
            }
            PoliticsRecord r = p.get(c.getUUID("player"));
            r.status = Standing.parse(c.getString("status"));
            r.statusSince = c.getLong("statusSince");
            if (c.contains("grievance", Tag.TAG_COMPOUND)) {
                CompoundTag gt = c.getCompound("grievance");
                int[] pos = gt.getIntArray("pos");
                GrievanceKind kind = null;
                try {
                    kind = gt.contains("kind") ? GrievanceKind.valueOf(gt.getString("kind")) : null;
                } catch (IllegalArgumentException ignored) {
                    // unknown kind from a newer version: keep the value, drop the label
                }
                r.grievances = Grievances.restore(gt.getDouble("value"), gt.getLong("last"), kind, gt.getBoolean("inside"),
                        gt.getBoolean("peacetime"), gt.getBoolean("selfDefense"), pos.length > 0 ? pos[0] : 0,
                        pos.length > 1 ? pos[1] : 0, pos.length > 2 ? pos[2] : 0, gt.contains("peacetimeKill") ? gt.getLong("peacetimeKill") : -1);
            }
            r.favor = Favor.restore(c.getInt("favor"), c.getLong("favorEarned"));
            r.lastRequestTick = c.contains("lastRequest") ? c.getLong("lastRequest") : -1;
            r.casualtiesOnErrands = c.getInt("casualties");
            ListTag lp = c.getList("lastProposal", Tag.TAG_COMPOUND);
            for (int j = 0; j < lp.size(); j++) {
                CompoundTag x = lp.getCompound(j);
                if (x.hasUUID("target")) {
                    r.lastProposal.put(x.getUUID("target"), x.getLong("tick"));
                }
            }
            r.lastSowDiscord = c.contains("lastSow") ? c.getLong("lastSow") : -1;
            r.sowAttempts = c.getInt("sowAttempts");
            r.lastErrandLoss = c.contains("lastErrandLoss") ? c.getLong("lastErrandLoss") : -1;
            r.lastTrickle = c.contains("lastTrickle") ? c.getLong("lastTrickle") : -1;
        }
        ListTag truces = t.getList("truces", Tag.TAG_COMPOUND);
        for (int i = 0; i < truces.size(); i++) {
            CompoundTag x = truces.getCompound(i);
            if (x.hasUUID("village")) {
                p.setTruce(x.getUUID("village"), x.getLong("until"));
            }
        }
        ListTag dc = t.getList("discordCooldown", Tag.TAG_COMPOUND);
        for (int i = 0; i < dc.size(); i++) {
            CompoundTag x = dc.getCompound(i);
            if (x.hasUUID("village")) {
                p.discordCooldown().put(x.getUUID("village"), x.getLong("until"));
            }
        }
        ListTag chron = t.getList("chronicle", Tag.TAG_COMPOUND);
        for (int i = 0; i < chron.size(); i++) {
            p.chronicle(chron.getCompound(i).getLong("t"), chron.getCompound(i).getString("text"));
        }
        return p;
    }

    /** M5-4: an envoy result waiting for its player to log in. */
    public record EnvoyReport(UUID player, long tick, String text) {}

    public static ListTag saveReports(List<EnvoyReport> reports) {
        ListTag l = new ListTag();
        for (EnvoyReport r : reports) {
            CompoundTag x = new CompoundTag();
            x.putUUID("player", r.player());
            x.putLong("t", r.tick());
            x.putString("text", r.text());
            l.add(x);
        }
        return l;
    }

    public static List<EnvoyReport> loadReports(ListTag l) {
        List<EnvoyReport> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            CompoundTag x = l.getCompound(i);
            if (x.hasUUID("player")) {
                out.add(new EnvoyReport(x.getUUID("player"), x.getLong("t"), x.getString("text")));
            }
        }
        return out;
    }

    public static ListTag saveEnvoys(List<EnvoyMission> missions) {
        ListTag l = new ListTag();
        for (EnvoyMission m : missions) {
            CompoundTag x = new CompoundTag();
            x.putUUID("id", m.id());
            x.putUUID("player", m.player());
            x.putUUID("from", m.from());
            x.putUUID("to", m.to());
            x.putString("kind", m.kind().name());
            x.putLong("depart", m.departTick());
            x.putLong("arrive", m.arriveTick());
            x.putLong("seed", m.seed());
            if (m.attempts() > 0) {
                x.putInt("attempts", m.attempts());
            }
            l.add(x);
        }
        return l;
    }

    public static List<EnvoyMission> loadEnvoys(ListTag l) {
        List<EnvoyMission> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            CompoundTag x = l.getCompound(i);
            try {
                out.add(new EnvoyMission(x.getUUID("id"), x.getUUID("player"), x.getUUID("from"), x.getUUID("to"),
                        EnvoyKind.valueOf(x.getString("kind")), x.getLong("depart"), x.getLong("arrive"), x.getLong("seed"), x.getInt("attempts")));
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // malformed or unknown kind: dropped (missions are transient; the player can propose again)
            }
        }
        return out;
    }
}

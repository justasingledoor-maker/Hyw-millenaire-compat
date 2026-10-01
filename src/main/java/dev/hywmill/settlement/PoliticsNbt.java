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
            if (r.lastRaidCounsel >= 0) {
                c.putLong("lastRaidCounsel", r.lastRaidCounsel);
            }
            if (r.lastSiegeCounsel >= 0) {
                c.putLong("lastSiegeCounsel", r.lastSiegeCounsel);
            }
            if (r.lastWarCounsel >= 0) {
                c.putLong("lastWarCounsel", r.lastWarCounsel);
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
            r.lastRaidCounsel = c.contains("lastRaidCounsel") ? c.getLong("lastRaidCounsel") : -1;
            r.lastSiegeCounsel = c.contains("lastSiegeCounsel") ? c.getLong("lastSiegeCounsel") : -1;
            r.lastWarCounsel = c.contains("lastWarCounsel") ? c.getLong("lastWarCounsel") : -1;
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

    /** M5-5b ledger keys "wars", "campaigns", "projections" (written only when non-empty). */
    public static void saveWar(CompoundTag root, Map<String, dev.hywmill.politics.war.WarRecord> wars,
                               List<dev.hywmill.politics.war.Campaign> campaigns, Map<String, String> projections) {
        ListTag w = new ListTag();
        for (dev.hywmill.politics.war.WarRecord r : wars.values()) {
            if (r.idle()) {
                continue;
            }
            CompoundTag x = new CompoundTag();
            x.putUUID("a", r.a);
            x.putUUID("b", r.b);
            x.putLong("conflictSince", r.conflictSince);
            x.putLong("calmSince", r.calmSince);
            x.putLong("warSince", r.warSince);
            w.add(x);
        }
        if (!w.isEmpty()) {
            root.put("wars", w);
        }
        ListTag c = new ListTag();
        for (dev.hywmill.politics.war.Campaign k : campaigns) {
            CompoundTag x = new CompoundTag();
            x.putUUID("player", k.player());
            x.putUUID("ally", k.ally());
            x.putUUID("enemy", k.enemy());
            x.putLong("since", k.since());
            x.putLong("until", k.until());
            c.add(x);
        }
        if (!c.isEmpty()) {
            root.put("campaigns", c);
        }
        if (!projections.isEmpty()) {
            CompoundTag p = new CompoundTag();
            projections.forEach(p::putString);
            root.put("projections", p);
        }
    }

    public static void loadWar(CompoundTag root, Map<String, dev.hywmill.politics.war.WarRecord> wars,
                               List<dev.hywmill.politics.war.Campaign> campaigns, Map<String, String> projections) {
        ListTag w = root.getList("wars", Tag.TAG_COMPOUND);
        for (int i = 0; i < w.size(); i++) {
            CompoundTag x = w.getCompound(i);
            if (x.hasUUID("a") && x.hasUUID("b")) {
                dev.hywmill.politics.war.WarRecord r = new dev.hywmill.politics.war.WarRecord(x.getUUID("a"), x.getUUID("b"));
                r.conflictSince = x.getLong("conflictSince");
                r.calmSince = x.getLong("calmSince");
                r.warSince = x.getLong("warSince");
                wars.put(r.key(), r);
            }
        }
        ListTag c = root.getList("campaigns", Tag.TAG_COMPOUND);
        for (int i = 0; i < c.size(); i++) {
            CompoundTag x = c.getCompound(i);
            if (x.hasUUID("player") && x.hasUUID("ally") && x.hasUUID("enemy")) {
                campaigns.add(new dev.hywmill.politics.war.Campaign(x.getUUID("player"), x.getUUID("ally"), x.getUUID("enemy"),
                        x.getLong("since"), x.getLong("until")));
            }
        }
        CompoundTag p = root.getCompound("projections");
        for (String k : p.getAllKeys()) {
            projections.put(k, p.getString(k));
        }
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

    // ------------------------------------------------------------------ post-M5 sieges

    /** Siege tribute owed to a player who was offline when the siege ended (paid at login). */
    public record PendingPay(UUID player, int deniers, String text) {}

    public static ListTag saveSieges(List<dev.hywmill.politics.war.Siege> sieges) {
        ListTag l = new ListTag();
        for (dev.hywmill.politics.war.Siege g : sieges) {
            CompoundTag x = new CompoundTag();
            x.putUUID("id", g.id);
            x.putUUID("attacker", g.attacker);
            x.putUUID("target", g.target);
            if (g.counsel != null) {
                x.putUUID("counsel", g.counsel);
            }
            x.putLong("launched", g.launched);
            x.putString("phase", g.phase.name());
            x.putLong("phaseSince", g.phaseSince);
            x.putLong("phaseEnd", g.phaseEnd);
            x.putString("outcome", g.outcome.name());
            x.put("host", uuids(g.host));
            x.putInt("hostStart", g.hostStart);
            x.putInt("defendersStart", g.defendersStart);
            x.put("attackerHelpers", uuids(g.attackerHelpers));
            x.put("defenderHelpers", uuids(g.defenderHelpers));
            x.putString("summary", g.summary);
            if (g.pausedSince >= 0) {
                x.putLong("pausedSince", g.pausedSince);
            }
            if (g.forceUnwatched) {
                x.putBoolean("unwatched", true);
            }
            if (g.aidRolled) {
                x.putBoolean("aidRolled", true);
                x.put("extras", uuids(g.extras));
            }
            if (g.mercRolled) {
                x.putBoolean("mercRolled", true);
                x.putString("mercCompany", g.mercCompany);
                x.putInt("mercCount", g.mercCount);
            }
            if (!g.reliefs.isEmpty()) {
                ListTag rl = new ListTag();
                for (dev.hywmill.politics.war.Relief r : g.reliefs) {
                    CompoundTag y = new CompoundTag();
                    y.putUUID("helper", r.helper);
                    y.putString("phase", r.phase.name());
                    y.putLong("phaseEnd", r.phaseEnd);
                    y.putString("fate", r.fate.name());
                    y.put("units", uuids(r.units));
                    y.put("strays", uuids(r.strays));
                    y.putInt("sent", r.sent);
                    y.putInt("killed", r.killed);
                    rl.add(y);
                }
                x.put("reliefs", rl);
            }
            l.add(x);
        }
        return l;
    }

    public static List<dev.hywmill.politics.war.Siege> loadSieges(ListTag l) {
        List<dev.hywmill.politics.war.Siege> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            CompoundTag x = l.getCompound(i);
            try {
                dev.hywmill.politics.war.Siege g = new dev.hywmill.politics.war.Siege(x.getUUID("id"), x.getUUID("attacker"), x.getUUID("target"),
                        x.hasUUID("counsel") ? x.getUUID("counsel") : null, x.getLong("launched"));
                g.phase = dev.hywmill.politics.war.Siege.Phase.valueOf(x.getString("phase"));
                g.phaseSince = x.getLong("phaseSince");
                g.phaseEnd = x.getLong("phaseEnd");
                g.outcome = dev.hywmill.politics.war.Siege.Outcome.valueOf(x.getString("outcome"));
                g.host.addAll(readUuids(x.getList("host", Tag.TAG_INT_ARRAY)));
                g.hostStart = x.getInt("hostStart");
                g.defendersStart = x.getInt("defendersStart");
                g.attackerHelpers.addAll(readUuids(x.getList("attackerHelpers", Tag.TAG_INT_ARRAY)));
                g.defenderHelpers.addAll(readUuids(x.getList("defenderHelpers", Tag.TAG_INT_ARRAY)));
                g.summary = x.getString("summary");
                g.forceUnwatched = x.getBoolean("unwatched");
                g.mercRolled = x.getBoolean("mercRolled");
                g.aidRolled = x.getBoolean("aidRolled");
                g.extras.addAll(readUuids(x.getList("extras", Tag.TAG_INT_ARRAY)));
                g.mercCompany = x.getString("mercCompany");
                g.mercCount = x.getInt("mercCount");
                g.pausedSince = x.contains("pausedSince") ? x.getLong("pausedSince") : -1;
                ListTag rl = x.getList("reliefs", Tag.TAG_COMPOUND);
                for (int j = 0; j < rl.size(); j++) {
                    CompoundTag y = rl.getCompound(j);
                    dev.hywmill.politics.war.Relief r = new dev.hywmill.politics.war.Relief(y.getUUID("helper"));
                    r.phase = dev.hywmill.politics.war.Relief.Phase.valueOf(y.getString("phase"));
                    r.phaseEnd = y.getLong("phaseEnd");
                    r.fate = dev.hywmill.politics.war.Relief.Fate.valueOf(y.getString("fate"));
                    r.units.addAll(readUuids(y.getList("units", Tag.TAG_INT_ARRAY)));
                    r.strays.addAll(readUuids(y.getList("strays", Tag.TAG_INT_ARRAY)));
                    r.sent = y.getInt("sent");
                    r.killed = y.getInt("killed");
                    g.reliefs.add(r);
                }
                out.add(g);
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // malformed: dropped; its host slots are brought home by the siege service's orphan sweep
            }
        }
        return out;
    }

    public static ListTag savePending(List<PendingPay> pay) {
        ListTag l = new ListTag();
        for (PendingPay p : pay) {
            CompoundTag x = new CompoundTag();
            x.putUUID("player", p.player());
            x.putInt("deniers", p.deniers());
            x.putString("text", p.text());
            l.add(x);
        }
        return l;
    }

    public static List<PendingPay> loadPending(ListTag l) {
        List<PendingPay> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            CompoundTag x = l.getCompound(i);
            if (x.hasUUID("player")) {
                out.add(new PendingPay(x.getUUID("player"), x.getInt("deniers"), x.getString("text")));
            }
        }
        return out;
    }

    public static ListTag saveTributes(List<dev.hywmill.politics.war.Tribute> tributes) {
        ListTag l = new ListTag();
        for (dev.hywmill.politics.war.Tribute t : tributes) {
            CompoundTag x = new CompoundTag();
            x.putUUID("payer", t.payer);
            x.putUUID("payee", t.payee);
            x.put("helpers", uuids(t.helpers));
            x.putInt("total", t.total);
            x.putDouble("levy", t.levy);
            x.putInt("helperPay", t.helperPay);
            x.putInt("days", t.days);
            x.putInt("paid", t.paid);
            x.putLong("next", t.nextTick);
            l.add(x);
        }
        return l;
    }

    public static List<dev.hywmill.politics.war.Tribute> loadTributes(ListTag l) {
        List<dev.hywmill.politics.war.Tribute> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            CompoundTag x = l.getCompound(i);
            if (x.hasUUID("payer") && x.hasUUID("payee")) {
                dev.hywmill.politics.war.Tribute t = new dev.hywmill.politics.war.Tribute(x.getUUID("payer"), x.getUUID("payee"), x.getInt("total"),
                        x.getDouble("levy"), x.getInt("helperPay"), x.getInt("days"), x.getLong("next"));
                t.helpers.addAll(readUuids(x.getList("helpers", Tag.TAG_INT_ARRAY)));
                t.paid = x.getInt("paid");
                out.add(t);
            }
        }
        return out;
    }

    private static ListTag uuids(java.util.Collection<UUID> ids) {
        ListTag l = new ListTag();
        for (UUID id : ids) {
            l.add(net.minecraft.nbt.NbtUtils.createUUID(id));
        }
        return l;
    }

    private static List<UUID> readUuids(ListTag l) {
        List<UUID> out = new ArrayList<>();
        for (Tag t : l) {
            out.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
        }
        return out;
    }
}

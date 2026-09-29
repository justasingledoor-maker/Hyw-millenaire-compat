package dev.hywmill.politics.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.Services;
import dev.hywmill.faction.CombatFactionService;
import dev.hywmill.politics.GrievanceEvent;
import dev.hywmill.politics.GrievanceKind;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.Standing;
import dev.hywmill.politics.war.Campaign;
import dev.hywmill.politics.war.RelationPlan;
import dev.hywmill.politics.war.WarRecord;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * M5-5b: projects HywMill's political state (wars between villages, players' campaigns) onto HYW
 * relations. The ledger is the truth; HYW's relations are reconciled against it at startup and every
 * {@link #INTERVAL} ticks. It remembers the relation it replaced on every edge it changed and restores
 * it when the cause ends; it never touches an edge it did not change. Resident identities are never
 * part of the plan (Option 1). One instance per server; the plan is transient and recomputed.
 */
public final class RelationProjector {
    public static final int INTERVAL = 200;
    public static final int OFFSET = 163;
    /** A campaign lasts this long (renewable by joining again). */
    public static final long CAMPAIGN_TICKS = 7 * PoliticsTables.DAY;
    /** A war ends once the relation has stayed above open conflict this long. */
    public static final long MIN_PEACE_TICKS = PoliticsTables.DAY;
    public static final int OPEN_CONFLICT = -90;
    public static final String C_WRITTEN = "relations.projected";
    public static final String C_RESTORED = "relations.restored";

    private volatile Map<RelationPlan.Edge, String> plan = Map.of();
    private boolean started;

    /** Whether the current plan wants these identities HOSTILE (consulted by {@link PoliticalPolicy}). */
    public boolean wantsHostile(UUID x, UUID y) {
        return RelationPlan.wantsHostile(plan, x, y);
    }

    public Map<RelationPlan.Edge, String> plan() {
        return plan;
    }

    public void tick(ServerLevel overworld, HywMillRuntime rt, long tick) {
        if (started && Math.floorMod(tick - OFFSET, INTERVAL) != 0) {
            return;
        }
        started = true;
        long t0 = rt.perf().start();
        reconcile(overworld, rt, tick);
        rt.perf().stop("relations.reconcile", t0);
    }

    public void reconcile(ServerLevel overworld, HywMillRuntime rt, long now) {
        SettlementSource source = Services.settlements();
        CombatFactionService factions = Services.factions();
        if (source == null || factions == null) {
            return;
        }
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        boolean dirty = updateWars(overworld, source, ledger, now);
        dirty |= expireCampaigns(overworld, source, ledger, now);
        Map<UUID, UUID> factionOf = new HashMap<>();
        for (VillageRecord r : ledger.all()) {
            factionOf.put(r.villageId, r.factionId);
        }
        Map<RelationPlan.Edge, String> desired = RelationPlan.desired(ledger.wars().values(), ledger.campaigns(), now, factionOf::get,
                v -> ledger.get(v) != null ? dev.hywmill.faction.FactionIds.residentsOf(v) : null);
        plan = Map.copyOf(desired);
        dirty |= apply(rt, factions, ledger, desired);
        if (dirty) {
            ledger.setDirty();
        }
    }

    // ------------------------------------------------------------------ wars

    private boolean updateWars(ServerLevel overworld, SettlementSource source, GarrisonLedger ledger, long now) {
        boolean auto = HywMillConfig.AUTO_WAR.get();
        long minConflict = HywMillConfig.WAR_MIN_CONFLICT_TICKS.get();
        List<VillageRecord> recs = new ArrayList<>(ledger.all());
        Map<UUID, UUID> raidTarget = new HashMap<>();
        for (VillageRecord r : recs) {
            source.raidInfo(overworld, r.villageId).ifPresent(i -> {
                if (i.target() != null && i.raidStart() > 0) {
                    raidTarget.put(r.villageId, i.target());
                }
            });
        }
        boolean dirty = false;
        for (int i = 0; i < recs.size(); i++) {
            for (int j = i + 1; j < recs.size(); j++) {
                VillageRecord x = recs.get(i), y = recs.get(j);
                String key = WarRecord.key(x.villageId, y.villageId);
                WarRecord w = ledger.wars().get(key);
                OptionalInt rxy = source.villageRelation(overworld, x.villageId, y.villageId);
                OptionalInt ryx = source.villageRelation(overworld, y.villageId, x.villageId);
                boolean conflict = (rxy.isPresent() && rxy.getAsInt() <= OPEN_CONFLICT) || (ryx.isPresent() && ryx.getAsInt() <= OPEN_CONFLICT);
                boolean raid = y.villageId.equals(raidTarget.get(x.villageId)) || x.villageId.equals(raidTarget.get(y.villageId));
                if (w == null) {
                    if (!auto || !(conflict || raid)) {
                        continue;
                    }
                    w = new WarRecord(x.villageId, y.villageId);
                    ledger.wars().put(key, w);
                    dirty = true;
                }
                boolean truce = x.politics.truceWith(y.villageId, now) || y.politics.truceWith(x.villageId, now);
                long before = w.conflictSince;
                WarRecord.Change c = w.update(now, conflict, raid, truce, auto, minConflict, MIN_PEACE_TICKS);
                dirty |= c != WarRecord.Change.NONE || before != w.conflictSince;
                if (c == WarRecord.Change.STARTED) {
                    String text = x.name + " and " + y.name + " are at war" + (raid ? " (a raid is under way)" : "");
                    PoliticsService.chronicle(overworld, source, x, now, text);
                    PoliticsService.chronicle(overworld, source, y, now, text);
                    HmLog.info("War: {} <-> {} started", x.name, y.name);
                } else if (c == WarRecord.Change.ENDED) {
                    String text = "The war between " + x.name + " and " + y.name + " is over" + (truce ? " (truce)" : !auto ? " (autoWar off)" : "");
                    PoliticsService.chronicle(overworld, source, x, now, text);
                    PoliticsService.chronicle(overworld, source, y, now, text);
                    HmLog.info("War: {} <-> {} ended", x.name, y.name);
                }
                if (w.idle()) {
                    ledger.wars().remove(key);
                }
            }
        }
        // records of villages that are gone
        dirty |= ledger.wars().values().removeIf(w -> ledger.get(w.a) == null || ledger.get(w.b) == null);
        return dirty;
    }

    public static boolean atWar(GarrisonLedger ledger, UUID x, UUID y) {
        WarRecord w = ledger.wars().get(WarRecord.key(x, y));
        return w != null && w.atWar();
    }

    // ------------------------------------------------------------------ campaigns

    private boolean expireCampaigns(ServerLevel overworld, SettlementSource source, GarrisonLedger ledger, long now) {
        boolean dirty = false;
        for (Iterator<Campaign> it = ledger.campaigns().iterator(); it.hasNext(); ) {
            Campaign c = it.next();
            VillageRecord ally = ledger.get(c.ally());
            String why = null;
            if (!c.active(now)) {
                why = "the campaign's time is up";
            } else if (ally == null || ledger.get(c.enemy()) == null) {
                why = "a village is gone";
            } else if (!atWar(ledger, c.ally(), c.enemy())) {
                why = "the war is over";
            } else if (EnvoyService.effective(overworld, ledger, source, ally, c.player()).ordinal() < Standing.TRUSTED.ordinal()) {
                why = "you lost the trust of " + ally.name;
            }
            if (why != null) {
                it.remove();
                dirty = true;
                endNotice(overworld, source, ledger, c, now, why);
            }
        }
        return dirty;
    }

    private static void endNotice(ServerLevel overworld, SettlementSource source, GarrisonLedger ledger, Campaign c, long now, String why) {
        VillageRecord ally = ledger.get(c.ally());
        VillageRecord enemy = ledger.get(c.enemy());
        String name = PoliticsService.playerName(overworld, c.player());
        String text = name + "'s campaign" + (ally != null ? " for " + ally.name : "") + (enemy != null ? " against " + enemy.name : "") + " ended (" + why + ")";
        if (ally != null) {
            PoliticsService.chronicle(overworld, source, ally, now, text);
        }
        if (enemy != null) {
            PoliticsService.chronicle(overworld, source, enemy, now, text);
        }
        ServerPlayer p = PoliticsService.onlinePlayer(overworld, c.player());
        if (p != null) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal("[War] Your campaign ended: " + why));
        }
        HmLog.info("War: {}", text);
    }

    @Nullable
    public static Campaign campaignOf(GarrisonLedger ledger, UUID player) {
        for (Campaign c : ledger.campaigns()) {
            if (c.player().equals(player)) {
                return c;
            }
        }
        return null;
    }

    /** The player is fighting against this village now (an enemy combatant there). */
    public static boolean enemyCombatant(GarrisonLedger ledger, UUID village, UUID player, long now) {
        Campaign c = campaignOf(ledger, player);
        return c != null && c.enemy().equals(village) && c.active(now);
    }

    public enum JoinResult { OK, NO_WAR, STANDING_TOO_LOW, ALREADY_IN_CAMPAIGN, SAME_VILLAGE, UNKNOWN_VILLAGE }

    /** {@code /hywmill war join <ally> against <enemy>}: validated here; the projection follows at once. */
    public JoinResult join(ServerLevel overworld, HywMillRuntime rt, UUID player, UUID allyId, UUID enemyId) {
        SettlementSource source = Services.settlements();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord ally = ledger.get(allyId);
        VillageRecord enemy = ledger.get(enemyId);
        if (source == null || ally == null || enemy == null) {
            return JoinResult.UNKNOWN_VILLAGE;
        }
        if (allyId.equals(enemyId)) {
            return JoinResult.SAME_VILLAGE;
        }
        if (!atWar(ledger, allyId, enemyId)) {
            return JoinResult.NO_WAR;
        }
        if (EnvoyService.effective(overworld, ledger, source, ally, player).ordinal() < Standing.TRUSTED.ordinal()) {
            return JoinResult.STANDING_TOO_LOW;
        }
        Campaign old = campaignOf(ledger, player);
        long now = overworld.getGameTime();
        if (old != null && !(old.ally().equals(allyId) && old.enemy().equals(enemyId))) {
            return JoinResult.ALREADY_IN_CAMPAIGN;
        }
        if (old != null) {
            ledger.campaigns().remove(old); // renewal
        }
        ledger.campaigns().add(new Campaign(player, allyId, enemyId, old != null ? old.since() : now, now + CAMPAIGN_TICKS));
        if (old == null) {
            // the political cost with the enemy: a grievance (decays normally; no pardon needed after the war)
            rt.politics().applyGrievance(overworld, enemy, player, new GrievanceEvent(GrievanceKind.JOINED_ENEMY, now, false, true, false,
                    enemy.center.getX(), enemy.center.getY(), enemy.center.getZ()));
            String text = PoliticsService.playerName(overworld, player) + " joined " + ally.name + " in its war against " + enemy.name;
            PoliticsService.chronicle(overworld, source, ally, now, text);
            PoliticsService.chronicle(overworld, source, enemy, now, text);
        }
        ledger.setDirty();
        reconcile(overworld, rt, now);
        return JoinResult.OK;
    }

    /** {@code /hywmill war leave}. Returns true if the player was on campaign. */
    public boolean leave(ServerLevel overworld, HywMillRuntime rt, UUID player) {
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        Campaign c = campaignOf(ledger, player);
        if (c == null) {
            return false;
        }
        ledger.campaigns().remove(c);
        endNotice(overworld, Services.settlements(), ledger, c, overworld.getGameTime(), "you left");
        ledger.setDirty();
        reconcile(overworld, rt, overworld.getGameTime());
        return true;
    }

    // ------------------------------------------------------------------ projection

    static String edgeKey(RelationPlan.Edge e) {
        return e.from() + ">" + e.to();
    }

    /** Writes the plan's differences (remembering what it replaced) and restores edges whose cause ended. */
    private boolean apply(HywMillRuntime rt, CombatFactionService f, GarrisonLedger ledger, Map<RelationPlan.Edge, String> desired) {
        boolean dirty = false;
        // 1. remember the current relation of every edge about to change, before any write (HOSTILE writes both directions)
        List<Map.Entry<RelationPlan.Edge, String>> writes = new ArrayList<>();
        for (Map.Entry<RelationPlan.Edge, String> e : desired.entrySet()) {
            String cur = f.relation(e.getKey().from(), e.getKey().to());
            if (!e.getValue().equals(cur)) {
                ledger.projections().putIfAbsent(edgeKey(e.getKey()), cur);
                writes.add(e);
                dirty = true;
            }
        }
        for (Map.Entry<RelationPlan.Edge, String> e : writes) {
            if (!e.getValue().equals(f.relation(e.getKey().from(), e.getKey().to()))) {
                f.setRelation(e.getKey().from(), e.getKey().to(), e.getValue());
                rt.increment(C_WRITTEN);
            }
        }
        if (!writes.isEmpty()) {
            HmLog.info("Relations: projected {} edge(s): {}", writes.size(), writes.stream()
                    .map(e -> e.getKey().from().toString().substring(0, 8) + "->" + e.getKey().to().toString().substring(0, 8) + "=" + e.getValue()).toList());
        }
        // 2. restore edges whose cause ended (only edges this projector changed)
        for (Iterator<Map.Entry<String, String>> it = ledger.projections().entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> rec = it.next();
            String[] ab = rec.getKey().split(">");
            RelationPlan.Edge edge = new RelationPlan.Edge(UUID.fromString(ab[0]), UUID.fromString(ab[1]));
            if (desired.containsKey(edge)) {
                continue;
            }
            it.remove();
            dirty = true;
            restore(rt, f, edge, rec.getValue());
        }
        return dirty;
    }

    private static void restore(HywMillRuntime rt, CombatFactionService f, RelationPlan.Edge edge, String previous) {
        String cur = f.relation(edge.from(), edge.to());
        if (cur.equals(previous)) {
            return;
        }
        if ("HOSTILE".equals(cur) && !"HOSTILE".equals(previous) && rt.diplomacy().permitsPermanentHostility(edge.from(), edge.to())) {
            return; // another political cause (outlawry) still wants it; its own projection owns it now
        }
        f.setRelation(edge.from(), edge.to(), previous);
        rt.increment(C_RESTORED);
        HmLog.info("Relations: restored {}->{} to {} (was {})", edge.from().toString().substring(0, 8), edge.to().toString().substring(0, 8), previous, cur);
    }

    /** Admin (uninstall hygiene): restores every edge this projector changed. With autoWar on, active wars project again next interval. */
    public int clearAll(ServerLevel overworld, HywMillRuntime rt) {
        CombatFactionService f = Services.factions();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        if (f == null) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, String> rec : new ArrayList<>(ledger.projections().entrySet())) {
            String[] ab = rec.getKey().split(">");
            restore(rt, f, new RelationPlan.Edge(UUID.fromString(ab[0]), UUID.fromString(ab[1])), rec.getValue());
            n++;
        }
        ledger.projections().clear();
        plan = Map.of();
        ledger.setDirty();
        return n;
    }

    /** Standing record helper for status output. */
    static PoliticsRecord peek(VillageRecord rec, UUID player) {
        return rec.politics.peek(player);
    }
}

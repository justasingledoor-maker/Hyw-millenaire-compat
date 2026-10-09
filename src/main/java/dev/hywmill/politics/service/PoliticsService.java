package dev.hywmill.politics.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.Services;
import dev.hywmill.faction.CombatFactionService;
import dev.hywmill.garrison.tag.GarrisonAttachments;
import dev.hywmill.garrison.tag.GarrisonTag;
import dev.hywmill.politics.GrievanceEvent;
import dev.hywmill.politics.GrievanceKind;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.Standing;
import dev.hywmill.politics.VillagePolitics;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.ResidentInfo;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M5-2: political status per player per village. One instance per server (owned by
 * {@link HywMillRuntime}); all persistent state is in the ledger ({@code VillageRecord.politics}).
 *
 * <ul>
 *   <li><b>Event-driven grievances</b> from the existing damage and death hooks: a player, or a
 *       player's HYW unit, that damages or kills a village resident (raid clones excluded) or one of
 *       its garrison units. Context: inside the village's bounds, peacetime, self-defence (the victim
 *       struck first, from the incident ledger). The controller of a player-controlled village is
 *       exempt (controller = permissions).</li>
 *   <li><b>Staggered refresh</b>: each village on its own slot, online players only; status from
 *       Millénaire's combined reputation. Default records are pruned.</li>
 *   <li><b>Chronicle</b>: status changes are written to HywMill's persisted chronicle and mirrored to
 *       Millénaire's (session-only) village history.</li>
 * </ul>
 * No per-tick world scan: the refresh iterates ledger records and online players.
 */
public final class PoliticsService {
    public static final int REFRESH_INTERVAL = 200;
    /** Repeated hits within this many ticks count as one assault. */
    public static final long ASSAULT_COOLDOWN = 40;

    /** (player, village) -> tick of the last assault grievance (transient throttle). */
    private final Map<String, Long> lastAssault = new ConcurrentHashMap<>();
    /** M5-5: (player, village) -> defense kills rewarded in the current alert (transient; reset when the village is calm). */
    private final Map<String, Integer> defenseKills = new ConcurrentHashMap<>();
    /** M5-5: villages seen ENGAGED since their last calm (transient); present players earn Favor when the attack is repelled. */
    private final java.util.Set<UUID> engaged = ConcurrentHashMap.newKeySet();
    /** Defense kills that earn Favor per player, village and alert. */
    public static final int DEFENSE_KILLS_PER_ALERT = 3;
    /** Long good standing: one Favor trickle per this many ticks. */
    public static final long TRICKLE_PERIOD = 30 * PoliticsTables.DAY;

    public static PoliticsTables tables(@Nullable VillageRecord rec) {
        return PoliticsTableLoader.current().forCulture(rec == null ? "" : rec.culture);
    }

    // ------------------------------------------------------------------ refresh

    public void tick(ServerLevel overworld, HywMillRuntime rt, long tick) {
        SettlementSource source = Services.settlements();
        if (source == null || overworld.players().isEmpty()) {
            return;
        }
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        for (VillageRecord rec : ledger.all()) {
            watchDefense(overworld, rt, rec, tick);
            if (!rt.scheduler().isDue(rec.villageId, tick + 97, REFRESH_INTERVAL)) {
                continue;
            }
            long t0 = rt.perf().start();
            boolean dirty = false;
            PoliticsTables tables = tables(rec);
            for (ServerPlayer p : onlinePlayers(overworld)) {
                if (!countsAsPlayer(p)) {
                    continue;
                }
                PoliticsRecord r = rec.politics.get(p.getUUID());
                int rep = source.playerReputation(overworld, rec.villageId, p.getUUID());
                Standing before = r.status;
                if (r.refresh(tick, rep, tables)) {
                    announce(overworld, source, rec, p.getUUID(), before, r.status, tick, reason(r, rep));
                    dirty = true;
                }
                dirty |= trickle(r, tick, tables);
            }
            for (Map.Entry<UUID, PoliticsRecord> e : rec.politics.players().entrySet()) {
                if (e.getValue().status == Standing.OUTLAW) {
                    project(rt, rec, e.getKey(), true); // re-assert the projection (HYW or a command may have changed it)
                }
            }
            if (rec.politics.prune(tick) > 0 || dirty) {
                ledger.setDirty();
            }
            rt.perf().stop("politics.refresh", t0);
        }
    }

    // ------------------------------------------------------------------ Favor from service (M5-5)

    /** Long good standing: +LONG_STANDING once a month at Trusted or better with no grievance. Online players only. */
    static boolean trickle(PoliticsRecord r, long now, PoliticsTables t) {
        boolean eligible = r.status.ordinal() >= Standing.TRUSTED.ordinal() && r.grievances.decayed(now, t.grievance()) < 1;
        if (!eligible) {
            boolean had = r.lastTrickle >= 0;
            r.lastTrickle = -1;
            return had;
        }
        if (r.lastTrickle < 0) {
            r.lastTrickle = now;
            return true;
        }
        if (now - r.lastTrickle >= TRICKLE_PERIOD) {
            r.favor.earn(dev.hywmill.politics.FavorSource.LONG_STANDING, t.favor());
            r.lastTrickle = now;
            return true;
        }
        return false;
    }

    /** Per tick, map lookups only: a village leaving ENGAGED rewards the players who stood by it. */
    private void watchDefense(ServerLevel overworld, HywMillRuntime rt, VillageRecord rec, long tick) {
        dev.hywmill.military.defense.AlertState st = rt.defense().state(rec.villageId);
        if (st == dev.hywmill.military.defense.AlertState.ENGAGED) {
            engaged.add(rec.villageId);
            return;
        }
        if (st == dev.hywmill.military.defense.AlertState.CALM) {
            String suffix = ">" + rec.villageId;
            defenseKills.keySet().removeIf(k -> k.endsWith(suffix));
        }
        if (!engaged.remove(rec.villageId)) {
            return;
        }
        int radius = rt.threats().defenseRadius(rec.villageId);
        PoliticsTables t = tables(rec);
        for (ServerPlayer p : onlinePlayers(overworld)) {
            if (!countsAsPlayer(p) || p.level() != overworld || player(p).equals(rec.controllerPlayerId)
                    || !dev.hywmill.military.defense.DefenseArea.inside(rec.center.getX() + 0.5, rec.center.getZ() + 0.5, radius, p.getX(), p.getZ())) {
                continue;
            }
            PoliticsRecord r = rec.politics.get(p.getUUID());
            if (r.status == Standing.OUTLAW) {
                continue;
            }
            int n = r.favor.earn(dev.hywmill.politics.FavorSource.PRESENT_AT_DEFENSE, t.favor());
            HmLog.info("Politics: {} stood by village '{}' while it was attacked: Favor +{}", p.getGameProfile().getName(), rec.name, n);
            GarrisonLedger.get(overworld).setDirty();
        }
    }

    private static UUID player(ServerPlayer p) {
        return p.getUUID();
    }

    /** Damaging or killing a current threat of a village during its alert: Favor for the kill, capped per alert. */
    private void defenseDeed(ServerLevel level, HywMillRuntime rt, LivingEntity victim, Entity attacker, boolean killed) {
        if (!killed) {
            return;
        }
        UUID player = responsiblePlayer(rt, attacker);
        if (player == null) {
            return;
        }
        ServerLevel overworld = level.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        for (UUID village : rt.threats().villagesThreatenedBy(victim.getUUID())) {
            VillageRecord rec = ledger.get(village);
            if (rec == null || victim.getUUID().equals(player)) {
                continue;
            }
            String key = player + ">" + village;
            int done = defenseKills.getOrDefault(key, 0);
            if (done >= DEFENSE_KILLS_PER_ALERT) {
                continue;
            }
            PoliticsRecord r = rec.politics.get(player);
            if (r.status == Standing.OUTLAW) {
                continue;
            }
            defenseKills.put(key, done + 1);
            int n = r.favor.earn(dev.hywmill.politics.FavorSource.DEFENSE, tables(rec).favor());
            HmLog.info("Politics: {} killed a threat of village '{}': Favor +{} ({} of {} this alert)", player, rec.name, n, done + 1,
                    DEFENSE_KILLS_PER_ALERT);
            ledger.setDirty();
        }
    }

    // ------------------------------------------------------------------ events

    /** A damage event already recorded by the incident ledger. */
    public void onDamage(ServerLevel level, LivingEntity victim, @Nullable Entity attacker) {
        record(level, victim, attacker, false);
    }

    public void onDeath(ServerLevel level, LivingEntity victim, @Nullable Entity killer) {
        record(level, victim, killer, true);
        HywMillRuntime rt = HywMillRuntime.get();
        if (rt != null && killer != null) {
            defenseDeed(level, rt, victim, killer, true);
        }
    }

    private void record(ServerLevel level, LivingEntity victim, @Nullable Entity attacker, boolean killed) {
        HywMillRuntime rt = HywMillRuntime.get();
        SettlementSource source = Services.settlements();
        if (rt == null || source == null || attacker == null) {
            return;
        }
        UUID player = responsiblePlayer(rt, attacker);
        if (player == null) {
            return;
        }
        UUID village;
        GrievanceKind kind;
        Optional<ResidentInfo> res = source.residentInfo(victim);
        GarrisonTag tag = GarrisonAttachments.get(victim);
        if (res.isPresent() && !res.get().raider()) {
            village = res.get().settlementId();
            kind = killed ? GrievanceKind.KILL_RESIDENT : GrievanceKind.ASSAULT_RESIDENT;
        } else if (tag != null) {
            village = tag.villageId();
            kind = killed ? GrievanceKind.KILL_GARRISON : GrievanceKind.ASSAULT_GARRISON;
        } else {
            return;
        }
        ServerLevel overworld = level.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord rec = ledger.get(village);
        if (rec == null || player.equals(rec.controllerPlayerId)) {
            return;
        }
        long now = overworld.getGameTime();
        if (!killed) {
            String key = player + ">" + village;
            Long last = lastAssault.get(key);
            if (last != null && now - last < ASSAULT_COOLDOWN) {
                return;
            }
            lastAssault.put(key, now);
        }
        PoliticsRecord r = rec.politics.get(player);
        boolean inside = village.equals(rt.threats().villageContaining(victim.blockPosition()));
        // peacetime: no war or campaign puts the player against this village, and the player was not already its outlaw
        boolean peacetime = r.status != Standing.OUTLAW && !RelationProjector.enemyCombatant(ledger, village, player, now);
        UUID first = rt.incidents().firstStriker(attacker.getUUID(), victim.getUUID(), now);
        boolean selfDefense = victim.getUUID().equals(first);
        GrievanceEvent e = new GrievanceEvent(kind, now, inside, peacetime, selfDefense,
                victim.getBlockX(), victim.getBlockY(), victim.getBlockZ());
        applyGrievance(overworld, rec, player, e);
        ledger.setDirty();
    }

    /** Adds a grievance and re-evaluates the player's status at once (event-driven). Also used by admin commands. */
    public double applyGrievance(ServerLevel overworld, VillageRecord rec, UUID player, GrievanceEvent e) {
        SettlementSource source = Services.settlements();
        PoliticsTables tables = tables(rec);
        PoliticsRecord r = rec.politics.get(player);
        double added = r.grievances.add(e, tables.grievance());
        int rep = source != null ? source.playerReputation(overworld, rec.villageId, player) : 0;
        Standing before = r.status;
        if (r.refresh(e.tick(), rep, tables)) {
            announce(overworld, source, rec, player, before, r.status, e.tick(), describe(e));
        }
        HmLog.diag("Grievance {} +{} for player {} with village '{}' (inside={}, peacetime={}, selfDefense={}); now {} status {}",
                e.kind(), added, player, rec.name, e.insideVillage(), e.peacetime(), e.selfDefense(),
                r.grievances.decayed(e.tick(), tables.grievance()), r.status);
        return added;
    }

    /** The player answerable for an attacker: the player itself, or the (non-village) owner of an HYW unit. */
    @Nullable
    static UUID responsiblePlayer(HywMillRuntime rt, Entity attacker) {
        if (attacker instanceof Player p) {
            return countsAsPlayer(p) ? p.getUUID() : null;
        }
        CombatFactionService factions = Services.factions();
        if (factions == null || !factions.isHywUnit(attacker)) {
            return null;
        }
        UUID owner = factions.ownerOf(attacker);
        return owner == null || rt.factions().isVillageIdentity(owner) ? null : owner;
    }

    /**
     * Online players: the server's player list, plus (dev/test servers only) fake players standing in the
     * overworld (the M5 harness stand-in, which is not in the player list).
     */
    public static java.util.List<ServerPlayer> onlinePlayers(ServerLevel overworld) {
        java.util.List<ServerPlayer> out = new java.util.ArrayList<>(overworld.getServer().getPlayerList().getPlayers());
        if (HywMillConfig.DEV_COMMANDS.get()) {
            for (ServerPlayer p : overworld.players()) {
                if (p.isFakePlayer() && !out.contains(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** The online player (or dev stand-in) with this UUID, or null. */
    @Nullable
    public static ServerPlayer onlinePlayer(ServerLevel overworld, UUID id) {
        ServerPlayer p = overworld.getServer().getPlayerList().getPlayer(id);
        if (p == null && HywMillConfig.DEV_COMMANDS.get() && overworld.getPlayerByUUID(id) instanceof ServerPlayer sp && sp.isFakePlayer()) {
            return sp;
        }
        return p;
    }

    /** Real players; fake players only on dev/test servers (the M5 harness stand-in). */
    static boolean countsAsPlayer(Player p) {
        return !p.isSpectator() && (!(p instanceof ServerPlayer sp) || !sp.isFakePlayer() || HywMillConfig.DEV_COMMANDS.get());
    }

    // ------------------------------------------------------------------ outlaw projection (M5-3)

    public static final String C_PROJECTED = "politics.outlawHostileWritten";
    public static final String C_CLEARED = "politics.outlawHostileCleared";

    /**
     * Projects outlawry onto HYW: village <b>faction</b> identity ↔ player HOSTILE while outlawed,
     * cleared on pardon. Never the resident identity (Option 1). The political record is authoritative;
     * {@link PoliticalPolicy} lets the escalation guard keep exactly these pairs.
     */
    public static void project(HywMillRuntime rt, VillageRecord rec, UUID player, boolean outlaw) {
        CombatFactionService f = Services.factions();
        if (f == null || rec.factionId == null || player.equals(rec.factionId) || rt.factions().isVillageIdentity(player)) {
            return;
        }
        if (outlaw) {
            if (!"HOSTILE".equals(f.relation(rec.factionId, player)) || !"HOSTILE".equals(f.relation(player, rec.factionId))) {
                f.setRelation(rec.factionId, player, "HOSTILE"); // HYW writes HOSTILE in both directions
                rt.increment(C_PROJECTED);
                HmLog.info("Politics: village '{}' faction {} -> outlaw {}: HYW HOSTILE", rec.name, rec.factionId, player);
            }
        } else if (f.isHostileEitherWay(rec.factionId, player)) {
            f.resetHostileToNeutral(rec.factionId, player);
            rt.increment(C_CLEARED);
            HmLog.info("Politics: village '{}' faction {} -> {}: HYW HOSTILE cleared (no longer an outlaw)", rec.name, rec.factionId, player);
        }
    }

    /** M2 player threat reason (M5-3 outlaw first, then M5-5b enemy combatant), or null. */
    @Nullable
    public static dev.hywmill.military.ThreatTracker.Reason playerThreat(ServerLevel overworld, UUID village, UUID player) {
        if (isOutlaw(overworld, village, player)) {
            return dev.hywmill.military.ThreatTracker.Reason.OUTLAWED_PLAYER;
        }
        return RelationProjector.enemyCombatant(GarrisonLedger.get(overworld), village, player, overworld.getGameTime())
                ? dev.hywmill.military.ThreatTracker.Reason.ENEMY_COMBATANT : null;
    }

    /** Whether the village has outlawed the player (its own record; word travels never outlaws). O(1). */
    public static boolean isOutlaw(ServerLevel overworld, UUID village, UUID player) {
        VillageRecord rec = GarrisonLedger.get(overworld).get(village);
        PoliticsRecord r = rec == null ? null : rec.politics.peek(player);
        return r != null && r.status == Standing.OUTLAW;
    }

    // ------------------------------------------------------------------ pardon (M5-3)

    /** Result of a formal pardon request; {@code status} is the standing afterwards. */
    public record PardonResult(dev.hywmill.politics.Pardon.Quote quote, boolean paid, Standing status, int reputationAfter) {}

    /**
     * The formal pardon: quote, and when {@code pay} and the quote is OK, take the weregild from
     * Millénaire's reputation, lower the grievance and re-evaluate (the announce clears the HYW HOSTILE).
     */
    public PardonResult pardon(ServerLevel overworld, VillageRecord rec, UUID player, boolean pay) {
        SettlementSource source = Services.settlements();
        PoliticsTables t = tables(rec);
        long now = overworld.getGameTime();
        PoliticsRecord r = rec.politics.get(player);
        int rep = source != null ? source.playerReputation(overworld, rec.villageId, player) : 0;
        dev.hywmill.politics.Pardon.Quote q = dev.hywmill.politics.Pardon.quote(r, now, rep, t);
        if (!pay || !q.ok() || source == null) {
            return new PardonResult(q, false, r.status, rep);
        }
        java.util.OptionalInt after = source.takeReputation(overworld, rec.villageId, player, q.price());
        if (after.isEmpty()) {
            return new PardonResult(q, false, r.status, rep);
        }
        Standing before = r.status;
        dev.hywmill.politics.Pardon.apply(r, now, after.getAsInt(), t);
        if (r.status != before) {
            announce(overworld, source, rec, player, before, r.status, now, "weregild of " + q.price() + " reputation paid");
        }
        GarrisonLedger.get(overworld).setDirty();
        return new PardonResult(q, true, r.status, after.getAsInt());
    }

    // ------------------------------------------------------------------ amnesty (post-M5)

    /**
     * A village that lost a siege forgives a player who helped the winners ({@link dev.hywmill.politics.Amnesty}): grievances
     * wiped, reputation raised to the Trusted line, standing re-evaluated (an outlaw is pardoned; the announce clears the HYW
     * HOSTILE). Returns the standing afterwards.
     */
    public static Standing amnesty(ServerLevel overworld, VillageRecord rec, UUID player, long tick, String why) {
        SettlementSource source = Services.settlements();
        PoliticsTables t = tables(rec);
        PoliticsRecord r = rec.politics.get(player);
        int rep = source != null ? source.playerReputation(overworld, rec.villageId, player) : 0;
        int raise = dev.hywmill.politics.Amnesty.raise(rep, t);
        if (raise > 0 && source != null) {
            // Millénaire moves the combined reputation by 1.1 x the village delta (culture +10%): round the village delta up
            java.util.OptionalInt after = source.adjustReputation(overworld, rec.villageId, player, (int) Math.ceil(raise * 10.0 / 11.0));
            rep = after.isPresent() ? after.getAsInt() : source.playerReputation(overworld, rec.villageId, player);
        }
        Standing before = r.status;
        dev.hywmill.politics.Amnesty.apply(r, tick, rep, t);
        if (r.status != before) {
            announce(overworld, source, rec, player, before, r.status, tick, why);
        }
        GarrisonLedger.get(overworld).setDirty();
        HmLog.info("Politics: amnesty of village '{}' for {}: reputation raised by {}, {} -> {}", rec.name, playerName(overworld, player), raise,
                before, r.status);
        return r.status;
    }

    // ------------------------------------------------------------------ apology (post-M5)

    /** Result of an apology; {@code status} is the standing afterwards, {@code moneyLeft} the payer's deniers (-1 unknown). */
    public record ApologyResult(dev.hywmill.politics.Apology.Quote quote, boolean paid, Standing status, int moneyLeft) {}

    /**
     * The apology: quote against the money the payer carries, and when {@code pay} and the quote is OK, take the price
     * (Millénaire money) and clear the grievance. Reputation is not touched.
     */
    public ApologyResult apology(ServerLevel overworld, VillageRecord rec, net.minecraft.world.entity.player.Player payer, boolean pay) {
        SettlementSource source = Services.settlements();
        PoliticsTables t = tables(rec);
        long now = overworld.getGameTime();
        UUID player = payer.getUUID();
        PoliticsRecord r = rec.politics.get(player);
        int money = source != null ? source.playerMoney(payer) : 0;
        dev.hywmill.politics.Apology.Quote q = dev.hywmill.politics.Apology.quote(r, now, money, t);
        if (!pay || !q.ok() || source == null || !source.takeMoney(payer, q.price())) {
            return new ApologyResult(q, false, r.status, source == null ? -1 : money);
        }
        int rep = source.playerReputation(overworld, rec.villageId, player);
        Standing before = r.status;
        dev.hywmill.politics.Apology.apply(r, now, rep, t);
        if (r.status != before) {
            announce(overworld, source, rec, player, before, r.status, now, "apology of " + dev.hywmill.recruit.RecruitOffers.money(q.price()) + " paid");
        }
        GarrisonLedger.get(overworld).setDirty();
        return new ApologyResult(q, true, r.status, source.playerMoney(payer));
    }

    // ------------------------------------------------------------------ chronicle

    private static void announce(ServerLevel overworld, @Nullable SettlementSource source, VillageRecord rec, UUID player,
                          Standing before, Standing after, long tick, String why) {
        HywMillRuntime rt = HywMillRuntime.get();
        if (rt != null && (before == Standing.OUTLAW) != (after == Standing.OUTLAW)) {
            project(rt, rec, player, after == Standing.OUTLAW);
        }
        String name = playerName(overworld, player);
        String place = rec.name.isEmpty() ? "this village" : rec.name;
        String honour = honour(after);
        String text = before == Standing.OUTLAW && after != Standing.OUTLAW
                ? name + " was pardoned by " + place + " and is now " + label(after) + (why.isEmpty() ? "" : " (" + why + ")")
                : honour != null && after.ordinal() > before.ordinal()
                ? name + " is honoured as " + honour + " of " + place // M5-6 honours
                : name + " is now " + label(after) + " in " + place + (why.isEmpty() ? "" : " (" + why + ")");
        chronicle(overworld, source, rec, tick, text);
        ServerPlayer p = onlinePlayer(overworld, player);
        if (p != null) {
            p.sendSystemMessage(Component.literal("[" + (rec.name.isEmpty() ? "Village" : rec.name) + "] You are now "
                    + label(after) + (before == Standing.OUTLAW && after != Standing.OUTLAW ? " (pardoned)" : "")
                    + (why.isEmpty() ? "" : ": " + why)));
        }
        HmLog.info("Politics: {} {} -> {} in village '{}' ({})", name, before, after, rec.name, why);
    }

    public static void chronicle(ServerLevel overworld, @Nullable SettlementSource source, VillageRecord rec, long tick, String text) {
        rec.politics.chronicle(tick, text);
        if (source != null) {
            source.recordHistory(overworld, rec.villageId, "[HywMill] " + text);
        }
    }

    /** M5-6: the honour a standing carries ("trusted friend", "patron", "one of us"), or null. */
    @Nullable
    public static String honour(Standing s) {
        return switch (s) {
            case TRUSTED -> "a trusted friend";
            case PATRON -> "a patron";
            case SWORN -> "one of us";
            default -> null;
        };
    }

    static String label(Standing s) {
        return switch (s) {
            case OUTLAW -> "an outlaw";
            case UNWELCOME -> "unwelcome";
            case STRANGER -> "a stranger";
            case TRUSTED -> "a trusted friend";
            case PATRON -> "a patron";
            case SWORN -> "sworn (one of us)";
        };
    }

    static String describe(GrievanceEvent e) {
        String what = switch (e.kind()) {
            case KILL_RESIDENT -> "killed a villager";
            case KILL_GARRISON -> "killed a soldier of the garrison";
            case ASSAULT_RESIDENT -> "attacked a villager";
            case ASSAULT_GARRISON -> "attacked the garrison";
            case ERRAND_ABUSE -> "abused a detachment";
            case PLOT_EXPOSED -> "was exposed plotting against the village";
            case JOINED_ENEMY -> "joined a war against the village";
        };
        return what + (e.insideVillage() ? " inside the village" : "") + (e.immediateOutlaw() ? " in peacetime" : "")
                + (e.selfDefense() ? ", in self-defence" : "");
    }

    static String reason(PoliticsRecord r, int rep) {
        return "reputation " + rep;
    }

    public static String playerName(ServerLevel level, UUID player) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        if (p != null) {
            return p.getGameProfile().getName();
        }
        return level.getServer().getProfileCache() != null
                ? level.getServer().getProfileCache().get(player).map(g -> g.getName()).orElse(player.toString().substring(0, 8))
                : player.toString().substring(0, 8);
    }

    /** Test/admin helper: the politics of a village (read-only view goes through PoliticsView). */
    public static VillagePolitics politicsOf(VillageRecord rec) {
        return rec.politics;
    }
}

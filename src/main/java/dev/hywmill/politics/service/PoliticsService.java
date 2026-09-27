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
            if (!rt.scheduler().isDue(rec.villageId, tick + 97, REFRESH_INTERVAL)) {
                continue;
            }
            long t0 = rt.perf().start();
            boolean dirty = false;
            PoliticsTables tables = tables(rec);
            for (ServerPlayer p : overworld.getServer().getPlayerList().getPlayers()) {
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
            }
            if (rec.politics.prune(tick) > 0 || dirty) {
                ledger.setDirty();
            }
            rt.perf().stop("politics.refresh", t0);
        }
    }

    // ------------------------------------------------------------------ events

    /** A damage event already recorded by the incident ledger. */
    public void onDamage(ServerLevel level, LivingEntity victim, @Nullable Entity attacker) {
        record(level, victim, attacker, false);
    }

    public void onDeath(ServerLevel level, LivingEntity victim, @Nullable Entity killer) {
        record(level, victim, killer, true);
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
        boolean peacetime = r.status != Standing.OUTLAW; // M5-5b adds wars and campaigns
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

    /** Real players; fake players only on dev/test servers (the M5 harness stand-in). */
    static boolean countsAsPlayer(Player p) {
        return !p.isSpectator() && (!(p instanceof ServerPlayer sp) || !sp.isFakePlayer() || HywMillConfig.DEV_COMMANDS.get());
    }

    // ------------------------------------------------------------------ chronicle

    private void announce(ServerLevel overworld, @Nullable SettlementSource source, VillageRecord rec, UUID player,
                          Standing before, Standing after, long tick, String why) {
        String name = playerName(overworld, player);
        String text = name + " is now " + label(after) + " in " + (rec.name.isEmpty() ? "this village" : rec.name)
                + (why.isEmpty() ? "" : " (" + why + ")");
        chronicle(overworld, source, rec, tick, text);
        ServerPlayer p = overworld.getServer().getPlayerList().getPlayer(player);
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
            case ERRAND_ABUSE -> "abused an escort";
            case PLOT_EXPOSED -> "was exposed plotting against the village";
        };
        return what + (e.insideVillage() ? " inside the village" : "") + (e.immediateOutlaw() ? " in peacetime" : "")
                + (e.selfDefense() ? ", in self-defence" : "");
    }

    static String reason(PoliticsRecord r, int rep) {
        return "reputation " + rep;
    }

    static String playerName(ServerLevel level, UUID player) {
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

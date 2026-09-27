package dev.hywmill.garrison.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.duty.DutyMotion;
import dev.hywmill.garrison.duty.DutyTable;
import dev.hywmill.garrison.duty.RaidPlanner;
import dev.hywmill.garrison.duty.RaidRule;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.military.defense.AlertState;
import dev.hywmill.politics.FavorSource;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.Requests;
import dev.hywmill.politics.Standing;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * M5-5: garrison units lent to a player: ESCORT (follow the player) and DETACHED (hold a named point).
 * They stay the village's units (roster first, same UUIDs, deaths permanent) and are away from home
 * defense like a raid contingent. They move only with M4's hop resolution: a home at most one hop
 * ahead, on standable ground in an entity-ticking chunk. They never teleport, never use HYW's own follow
 * order (which teleports) and never force-load; when the way ahead is not loaded they hold.
 * The errand ends on time or on dismissal (a home alert does not recall it); the units then walk home
 * through the normal RETURNING path. Pure bookkeeping lives in {@link Requests}.
 */
public final class ErrandService {
    public static final String C_GRANTED = "errands.granted";
    /** Escort ring radius around the player. */
    static final double RING = 3.0;
    /** An escort engages whoever hurt its player within this distance and this many ticks. */
    static final double DEFEND_RADIUS = 16.0;
    static final long DEFEND_WINDOW = 100;

    private ErrandService() {}

    /** Units the village can spare now under its own raid rule: never below the home share, never the kept sentry pairs and reserve. */
    static List<UUID> spare(ServerLevel overworld, GarrisonRoster r, RaidRule raid) {
        List<RaidPlanner.Candidate> cands = new ArrayList<>();
        for (RosterEntry e : r.entries()) {
            if ((e.state() == UnitState.GARRISONED || e.state() == UnitState.RECOVERED) && e.duty.standing() && e.entityUuid != null
                    && !DutyMotion.scoutAway(e)) {
                Entity ent = GarrisonService.find(overworld.getServer(), e.entityUuid);
                if (ent != null && ent.isAlive()) {
                    cands.add(new RaidPlanner.Candidate(e.rosterId, e.assignedDuty, e.dutyIndex));
                }
            }
        }
        // the raid rule with everything above the home share offered (the village's own minHome, kept pairs and reserve)
        RaidRule r2 = new RaidRule(true, Math.max(0, 1 - raid.minHome()), 1, 64, raid.minHome(), raid.keepSentryPairs(), raid.keepReserve(), 1);
        return RaidPlanner.select(r2, cands);
    }

    public record Grant(Requests.Offer offer, List<RosterEntry> units) {}

    /**
     * Evaluates and, when accepted, lends the units. {@code point} is the detachment's point (null for escorts).
     */
    public static Grant request(ServerLevel overworld, VillageRecord rec, UUID player, Standing standing, Requests.Kind kind, int asked,
                                int days, @Nullable BlockPos point, AlertState alert, PoliticsTables t) {
        return request(overworld, rec, player, standing, kind, asked, days, point, alert, t, false);
    }

    /** With {@code dryRun} the village evaluates and nothing is lent or paid (the Politics screen's verdict). */
    public static Grant request(ServerLevel overworld, VillageRecord rec, UUID player, Standing standing, Requests.Kind kind, int asked,
                                int days, @Nullable BlockPos point, AlertState alert, PoliticsTables t, boolean dryRun) {
        GarrisonRoster r = rec.hywRoster;
        SettlementSource source = Services.settlements();
        UnitProvider units = Services.units();
        long now = overworld.getGameTime();
        PoliticsRecord pr = dryRun ? java.util.Objects.requireNonNullElseGet(rec.politics.peek(player), PoliticsRecord::new) : rec.politics.get(player);
        boolean raidPreparing = (r != null && r.raid != null)
                || (source != null && source.raidInfo(overworld, rec.villageId).map(i -> i.target() != null).orElse(false));
        List<UUID> spare = r == null || units == null ? List.of() : spare(overworld, r, DutyTableRaid.of(rec));
        double dist = point == null ? 0 : Math.sqrt(point.distSqr(rec.center));
        Requests.Offer offer = Requests.evaluate(kind, standing, asked, days, dist, alert == AlertState.CALM, raidPreparing, spare.size(),
                recentCasualties(pr, now, t), pr.favor.points(), now, pr.lastRequestTick, t.requests());
        if (!offer.ok() || r == null || units == null || dryRun) {
            return new Grant(offer, List.of());
        }
        pr.favor.spend(offer.favorCost());
        pr.lastRequestTick = now;
        long until = now + (kind == Requests.Kind.ESCORT ? t.requests().escortTicks() : (long) days * PoliticsTables.DAY);
        List<RosterEntry> lent = new ArrayList<>();
        for (UUID id : spare.subList(0, offer.units())) {
            RosterEntry e = r.entry(id);
            e.transition(UnitState.DEPLOYED, now);
            e.duty = kind == Requests.Kind.ESCORT ? Duty.ESCORT : Duty.DETACHED;
            e.dutySince = now;
            e.errandPlayer = player;
            e.errandUntil = until;
            e.errandPoint = point == null ? Long.MIN_VALUE : point.asLong();
            lent.add(e);
        }
        HmLog.info("Village '{}' lends {} unit(s) to {} as {} until tick {} (Favor {}): {}", rec.name, lent.size(), player, kind, until,
                offer.favorCost(), lent.stream().map(RosterEntry::shortId).toList());
        GarrisonLedger.get(overworld).setDirty();
        return new Grant(offer, lent);
    }

    /** How long the village remembers soldiers lost on a player's errands (willingness). */
    public static final long CASUALTY_MEMORY = 30 * PoliticsTables.DAY;

    /** Casualties on this player's errands that the village still remembers: all of them until a month passes without a new loss. */
    static int recentCasualties(PoliticsRecord pr, long now, PoliticsTables t) {
        return pr.lastErrandLoss < 0 || now - pr.lastErrandLoss > CASUALTY_MEMORY ? 0 : pr.casualtiesOnErrands;
    }

    /**
     * Moves and ends errands of one village; called on its duty tick. Returns true if any roster state changed.
     */
    public static boolean tick(ServerLevel overworld, VillageRecord rec, GarrisonRoster r, DutyTable table, AlertState alert, long tick) {
        UnitProvider units = Services.units();
        if (units == null) {
            return false;
        }
        boolean changed = false;
        int idx = 0;
        for (RosterEntry e : r.entries()) {
            if (!e.duty.errand() || e.state() != UnitState.DEPLOYED) {
                continue;
            }
            UUID player = e.errandPlayer;
            // A home alert does not recall lent soldiers (an ordinary night would otherwise end every escort);
            // the village only refuses new requests while it is not calm.
            if (player == null || tick >= e.errandUntil) {
                changed |= end(overworld, rec, e, units, tick, player == null ? "no player" : "time is up");
                continue;
            }
            Entity ent = GarrisonService.find(overworld.getServer(), e.entityUuid);
            if (ent == null || !ent.isAlive() || !(ent.level() instanceof ServerLevel level) || level != overworld) {
                continue; // not loaded: it holds wherever it is (the reconciler's missing clock is paused for it)
            }
            BlockPos goal;
            if (e.duty == Duty.ESCORT) {
                ServerPlayer p = dev.hywmill.politics.service.PoliticsService.onlinePlayer(overworld, player);
                if (p == null || p.level() != overworld) {
                    continue; // the player is away: hold
                }
                double a = (idx++ * 2 * Math.PI / 6) + (e.rosterId.getLeastSignificantBits() & 7) * 0.1;
                goal = BlockPos.containing(p.getX() + RING * Math.cos(a), p.getY(), p.getZ() + RING * Math.sin(a));
                goal = confine(rec, player, goal);
                LivingEntity attacker = p.getLastHurtByMob();
                if (attacker != null && attacker.isAlive() && tick - p.getLastHurtByMobTimestamp() < DEFEND_WINDOW
                        && attacker.distanceToSqr(ent) < DEFEND_RADIUS * DEFEND_RADIUS && units.target(ent) != attacker) {
                    units.engage(ent, attacker);
                }
            } else {
                goal = BlockPos.of(e.errandPoint);
            }
            if (DutyMotion.horizontal(ent.getX(), ent.getZ(), goal) <= table.move().arriveRadius()) {
                continue;
            }
            BlockPos home = units.home(ent);
            if (home != null && DutyMotion.horizontal(ent.getX(), ent.getZ(), home) > table.move().maxHop() / 2.0
                    && DutyMotion.horizontal(home.getX() + 0.5, home.getZ() + 0.5, goal) < DutyMotion.horizontal(ent.getX(), ent.getZ(), goal)) {
                continue; // still travelling to a hop that leads towards the goal: do not restart HYW's path (as M4 duties)
            }
            BlockPos hop = DutyService.hopTarget(overworld, ent, goal, table.move().maxHop());
            if (hop != null && (home == null || home.distSqr(hop) > 2)) {
                units.setHome(ent, hop);
            }
        }
        return changed;
    }

    /** A Trusted player's escort stays within the village's lands (its defense radius); Patrons and Sworn may leave them. */
    private static BlockPos confine(VillageRecord rec, UUID player, BlockPos goal) {
        PoliticsRecord pr = rec.politics.peek(player);
        if (pr != null && pr.status.ordinal() >= Standing.PATRON.ordinal()) {
            return goal;
        }
        int radius = Math.max(16, rec.villageRadius);
        double dx = goal.getX() - rec.center.getX(), dz = goal.getZ() - rec.center.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= radius) {
            return goal;
        }
        return new BlockPos((int) Math.round(rec.center.getX() + dx / d * radius), goal.getY(), (int) Math.round(rec.center.getZ() + dz / d * radius));
    }

    /** Ends one unit's errand: it walks home through the normal RETURNING path (no teleport). */
    static boolean end(ServerLevel overworld, VillageRecord rec, RosterEntry e, UnitProvider units, long tick, String why) {
        UUID player = e.errandPlayer;
        long since = e.dutySince;
        e.clearErrand();
        e.transition(UnitState.RETURNING, tick);
        e.duty = Duty.RETURNING;
        Entity ent = e.entityUuid == null ? null : GarrisonService.find(overworld.getServer(), e.entityUuid);
        if (ent != null) {
            units.disengage(ent);
            units.setHome(ent, GarrisonService.anchorOf(rec));
        }
        if (player != null && !stillOut(rec, player)) {
            PoliticsRecord pr = rec.politics.get(player);
            PoliticsTables t = dev.hywmill.politics.service.PoliticsService.tables(rec);
            boolean clean = pr.lastErrandLoss < since;
            if (clean) {
                pr.favor.earn(FavorSource.ERRAND_SUCCESS, t.favor());
            }
            ServerPlayer p = overworld.getServer().getPlayerList().getPlayer(player);
            if (p != null) {
                p.sendSystemMessage(Component.literal("[" + rec.name + "] Your " + (clean ? "errand is over; the soldiers go home (Favor +"
                        + t.favor().amount(FavorSource.ERRAND_SUCCESS) + ")" : "errand is over; the survivors go home") + " (" + why + ")"));
            }
            HmLog.info("Errand of village '{}' for {} ended ({}), {}", rec.name, player, why, clean ? "no losses" : "with losses");
        }
        return true;
    }

    private static boolean stillOut(VillageRecord rec, UUID player) {
        for (RosterEntry x : rec.hywRoster.entries()) {
            if (x.duty.errand() && player.equals(x.errandPlayer) && x.state() == UnitState.DEPLOYED) {
                return true;
            }
        }
        return false;
    }

    /** The player sends every lent unit home. Returns how many. */
    public static int dismiss(ServerLevel overworld, UUID player) {
        UnitProvider units = Services.units();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        int n = 0;
        long tick = overworld.getGameTime();
        for (VillageRecord rec : ledger.all()) {
            if (rec.hywRoster == null || units == null) {
                continue;
            }
            for (RosterEntry e : rec.hywRoster.entries()) {
                if (e.duty.errand() && player.equals(e.errandPlayer) && e.state() == UnitState.DEPLOYED) {
                    end(overworld, rec, e, units, tick, "dismissed");
                    n++;
                }
            }
        }
        if (n > 0) {
            ledger.setDirty();
        }
        return n;
    }

    /** A soldier died on the player's errand: "you got our sons killed". */
    public static void onDeath(VillageRecord rec, RosterEntry e, long tick) {
        if (!e.duty.errand() || e.errandPlayer == null) {
            return;
        }
        PoliticsRecord pr = rec.politics.get(e.errandPlayer);
        PoliticsTables t = dev.hywmill.politics.service.PoliticsService.tables(rec);
        pr.casualtiesOnErrands++;
        pr.lastErrandLoss = tick;
        int lost = pr.favor.lose(t.requests().casualtyFavor());
        HmLog.info("Soldier {} of village '{}' died on {}'s errand: Favor -{} ({} casualties on their errands)", e.shortId(), rec.name,
                e.errandPlayer, lost, pr.casualtiesOnErrands);
        e.clearErrand();
    }

    /** Units currently lent to the player by this village. */
    public static List<RosterEntry> lentTo(VillageRecord rec, UUID player) {
        List<RosterEntry> out = new ArrayList<>();
        if (rec.hywRoster != null) {
            for (RosterEntry e : rec.hywRoster.entries()) {
                if (e.duty.errand() && player.equals(e.errandPlayer) && e.state() == UnitState.DEPLOYED) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** The village's raid rule (duty data), used for the spare computation. */
    static final class DutyTableRaid {
        static RaidRule of(VillageRecord rec) {
            return dev.hywmill.garrison.duty.DutyTables.current().forCulture(rec.culture).raid();
        }
    }
}

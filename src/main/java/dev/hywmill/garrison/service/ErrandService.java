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
 * M5-5: garrison units lent to a player as a DETACHMENT (hold a named point). They stay the village's units
 * (roster first, same UUIDs, deaths permanent) and are away from home defense like a raid contingent. They
 * move only with M4's hop resolution: a home at most one hop ahead, on standable ground in an entity-ticking
 * chunk; when stuck they try side-steps along the obstacle, then hold. They never teleport and never force-load.
 * The errand ends on time or on dismissal (a home alert does not recall it); the units then walk home through
 * the normal RETURNING path. Pure bookkeeping lives in {@link Requests}.
 *
 * <p>Player-following escorts are deferred to a later phase (M5 sign-off scope): following a player out of a
 * village can leave a lent soldier trapped, and lent soldiers never teleport.
 */
public final class ErrandService {
    public static final String C_GRANTED = "errands.granted";

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
        List<UUID> chosen = new ArrayList<>(RaidPlanner.select(r2, cands));
        // prefer soldiers standing in the open: one inside a building may not get out (HYW units do not open doors, and a
        // lent soldier never teleports), so it is lent last
        chosen.sort(java.util.Comparator.comparing(id -> {
            RosterEntry e = r.entry(id);
            Entity ent = e == null ? null : GarrisonService.find(overworld.getServer(), e.entityUuid);
            return ent == null || !overworld.canSeeSky(ent.blockPosition().above()) ? 1 : 0;
        }));
        return chosen;
    }

    public record Grant(Requests.Offer offer, List<RosterEntry> units) {}

    /**
     * Evaluates and, when accepted, lends the units to hold {@code point}.
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
        // post-M5: a village under siege (declared or fought) keeps every man at home
        boolean calm = alert == AlertState.CALM && !SiegeService.besieged(dev.hywmill.settlement.GarrisonLedger.get(overworld), rec.villageId);
        Requests.Offer offer = Requests.evaluate(kind, standing, asked, days, dist, calm, raidPreparing, spare.size(),
                recentCasualties(pr, now, t), pr.favor.points(), now, pr.lastRequestTick, t.requests());
        if (!offer.ok() || r == null || units == null || dryRun) {
            return new Grant(offer, List.of());
        }
        pr.favor.spend(offer.favorCost());
        pr.lastRequestTick = now;
        if (point == null) {
            return new Grant(new Requests.Offer(Requests.Refusal.BAD_REQUEST, 0, 0, "a detachment needs a point to hold"), List.of());
        }
        long until = now + (long) days * PoliticsTables.DAY;
        List<RosterEntry> lent = new ArrayList<>();
        for (UUID id : spare.subList(0, offer.units())) {
            RosterEntry e = r.entry(id);
            e.transition(UnitState.DEPLOYED, now);
            e.duty = Duty.DETACHED;
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
    /** An errand unit that has not moved for this long towards its hop tries a detour (as M4 duties do). */
    static final long STUCK_TICKS = 200;

    public static boolean tick(ServerLevel overworld, VillageRecord rec, GarrisonRoster r, DutyTable table, AlertState alert, long tick,
                               java.util.Map<UUID, long[]> moves) {
        UnitProvider units = Services.units();
        if (units == null) {
            return false;
        }
        boolean changed = false;
        for (RosterEntry e : r.entries()) {
            if (!e.duty.errand() || e.state() != UnitState.DEPLOYED) {
                moves.remove(e.rosterId);
                continue;
            }
            UUID player = e.errandPlayer;
            // A home alert does not recall lent soldiers (an ordinary night would otherwise end every errand);
            // the village only refuses new requests while it is not calm.
            if (player == null || tick >= e.errandUntil) {
                changed |= end(overworld, rec, e, units, tick, player == null ? "no player" : "time is up");
                continue;
            }
            Entity ent = GarrisonService.find(overworld.getServer(), e.entityUuid);
            if (ent == null || !ent.isAlive() || ent.level() != overworld || e.errandPoint == Long.MIN_VALUE) {
                continue; // not loaded: it holds wherever it is (the reconciler's missing clock is paused for it)
            }
            BlockPos goal = BlockPos.of(e.errandPoint);
            if (DutyMotion.horizontal(ent.getX(), ent.getZ(), goal) <= table.move().arriveRadius()) {
                continue;
            }
            BlockPos home = units.home(ent);
            long pos = ent.blockPosition().asLong();
            long[] last = moves.get(e.rosterId);
            if (last != null && home != null && last[0] == home.asLong()) {
                if (BlockPos.of(last[3]).distSqr(ent.blockPosition()) > 4) {
                    last[1] = tick; // progress
                    last[3] = pos;
                } else if (tick - last[1] >= STUCK_TICKS) {
                    // stuck short of its hop (a village wall, a hedge): look for a way round along the obstacle with points
                    // off the direct line (±35°, ±70°, ±105°, up to 24 blocks), then give the leg up and hold. Hops only, never a teleport.
                    int attempt = (int) last[2] + 1;
                    BlockPos alt = attempt <= DETOURS ? sideStep(overworld, ent, goal, attempt) : null;
                    if (alt != null) {
                        units.setHome(ent, alt);
                        moves.put(e.rosterId, new long[]{alt.asLong(), tick, attempt, pos});
                    } else {
                        moves.remove(e.rosterId);
                    }
                    continue;
                }
            }
            if (home != null && DutyMotion.horizontal(ent.getX(), ent.getZ(), home) > table.move().maxHop() / 2.0
                    && DutyMotion.horizontal(home.getX() + 0.5, home.getZ() + 0.5, goal) < DutyMotion.horizontal(ent.getX(), ent.getZ(), goal)) {
                continue; // still travelling to a hop that leads towards the goal: do not restart HYW's path (as M4 duties)
            }
            BlockPos hop = DutyService.hopTarget(overworld, ent, goal, table.move().maxHop());
            if (hop != null && (home == null || home.distSqr(hop) > 2)) {
                units.setHome(ent, hop);
                moves.put(e.rosterId, new long[]{hop.asLong(), tick, last != null && last[0] == (home == null ? 0 : home.asLong()) ? last[2] : 0, pos});
            }
        }
        return changed;
    }

    static final int DETOURS = 6;

    /** A reachable-looking spot off the direct line to {@code goal}: rotated by 35° × ⌈attempt/2⌉, alternating sides. */
    @Nullable
    static BlockPos sideStep(ServerLevel level, Entity ent, BlockPos goal, int attempt) {
        double dx = goal.getX() + 0.5 - ent.getX(), dz = goal.getZ() + 0.5 - ent.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-3) {
            return null;
        }
        double a = Math.toRadians(35 * ((attempt + 1) / 2)) * (attempt % 2 == 1 ? 1 : -1);
        double len = Math.min(24, Math.max(8, d));
        double ux = dx / d, uz = dz / d;
        double rx = ux * Math.cos(a) - uz * Math.sin(a), rz = ux * Math.sin(a) + uz * Math.cos(a);
        return DutyService.surface(level, BlockPos.containing(ent.getX() + rx * len, ent.getY(), ent.getZ() + rz * len));
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

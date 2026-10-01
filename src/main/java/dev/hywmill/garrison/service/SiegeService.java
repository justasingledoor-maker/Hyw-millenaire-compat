package dev.hywmill.garrison.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.PerfCounters;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.LossReason;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.duty.DutyMotion;
import dev.hywmill.garrison.duty.RaidPlanner;
import dev.hywmill.garrison.duty.RaidRule;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.politics.FavorSource;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.Standing;
import dev.hywmill.politics.api.PoliticsView;
import dev.hywmill.politics.service.PoliticsService;
import dev.hywmill.politics.service.RelationProjector;
import dev.hywmill.politics.war.Campaign;
import dev.hywmill.politics.war.Siege;
import dev.hywmill.politics.war.SiegeMath;
import dev.hywmill.politics.war.Tribute;
import dev.hywmill.politics.war.WarRecord;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.PoliticsNbt;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Post-M5 sieges (docs/siege-design.md): HYW-only expeditions of a garrison against a village it is at war with. A host
 * musters, marches stowed (roster slots without entities: nothing to load, nothing to lose, nothing to duplicate), and
 * materializes before the target when that is loaded; otherwise the siege is decided off-screen by strength. The winner
 * takes tribute (levy for recruiting, money for its helpers) and its helpers gain standing; survivors march home the same
 * way. Stateless apart from the ledger's siege records.
 */
public final class SiegeService {
    public static final int INTERVAL = 20;
    public static final int OFFSET = 7;
    /** Village decisions are checked on this cadence (ticks); the per-culture rule's {@code aiInterval} scales the odds. */
    public static final int AI_INTERVAL = 1200;
    public static final int AI_OFFSET = 607;
    /** Players this close to the target during a battle count as helpers of the side their campaign is on. */
    public static final double HELPER_RANGE = 96;
    public static final double ENGAGE_RANGE = 32;
    /** The host lands this many blocks outside the target's village radius. */
    public static final int STAGING_MARGIN = 16;
    /** Village radius assumed when Millénaire did not report one. */
    public static final int DEFAULT_RADIUS = 48;
    /** Players this close to either village hear about the siege. */
    public static final double NEWS_RANGE = 256;
    /** A paused (unwatched) battle ends on its standing shares after this long (10 minutes). */
    public static final long PAUSE_LIMIT = 12000;
    public static final String C_LAUNCHED = "siege.launched", C_WON = "siege.won", C_LOST = "siege.lost", C_OFFSCREEN = "siege.offscreen";

    private final PerfCounters perf;
    /** Post-M5: one boss bar per siege in battle, shown to players near the target (runtime only; rebuilt from the ledger). */
    private final java.util.Map<UUID, net.minecraft.server.level.ServerBossEvent> bars = new java.util.HashMap<>();

    public SiegeService(PerfCounters perf) {
        this.perf = perf;
    }

    public record Launch(SiegeMath.Refusal refusal, @Nullable Siege siege, String detail) {
        public boolean ok() {
            return refusal == SiegeMath.Refusal.OK && siege != null;
        }
    }

    public record Counsel(SiegeMath.Refusal refusal, double chance, boolean agreed, boolean spent, String detail) {
        public boolean ok() {
            return refusal == SiegeMath.Refusal.OK;
        }
    }

    public static PoliticsTables.SiegeRule rule(VillageRecord attacker) {
        return PoliticsService.tables(attacker).siege();
    }

    // ------------------------------------------------------------------ queries

    /**
     * The siege whose host belongs to {@code attacker} and is on campaign (mustering, marching, waiting or fighting). A host on
     * its way home does not count: it holds none of the soldiers a new siege would take, and the village may march again.
     */
    @Nullable
    public static Siege byAttacker(GarrisonLedger ledger, UUID attacker) {
        for (Siege s : ledger.sieges()) {
            if (s.attacker.equals(attacker) && s.phase != Siege.Phase.RETURN) {
                return s;
            }
        }
        return null;
    }

    /** The siege against {@code target} that has not been decided or called off yet (a host on its way home is no threat). */
    @Nullable
    public static Siege against(GarrisonLedger ledger, UUID target) {
        for (Siege s : ledger.sieges()) {
            if (s.target.equals(target) && s.outcome == Siege.Outcome.NONE && s.phase != Siege.Phase.RETURN) {
                return s;
            }
        }
        return null;
    }

    static RaidRule hostRule(PoliticsTables.SiegeRule r) {
        return new RaidRule(r.enabled(), r.commitFraction(), r.minCommit(), r.maxCommit(), r.minHome(), r.keepSentryPairs(), r.keepReserve(),
                r.minGarrison());
    }

    /** The host the village would send now (units at home on a standing duty, loaded or not), by the raid planner's rules. */
    public static List<UUID> planHost(VillageRecord rec) {
        GarrisonRoster r = rec.hywRoster;
        if (r == null) {
            return List.of();
        }
        List<RaidPlanner.Candidate> cands = new ArrayList<>();
        for (RosterEntry e : r.entries()) {
            if ((e.state() == UnitState.GARRISONED || e.state() == UnitState.RECOVERED) && e.duty.standing() && e.entityUuid != null
                    && !DutyMotion.scoutAway(e)) {
                cands.add(new RaidPlanner.Candidate(e.rosterId, e.assignedDuty, e.dutyIndex));
            }
        }
        return RaidPlanner.select(hostRule(rule(rec)), cands);
    }

    static double cost(RosterEntry e) {
        UnitSpec u = GarrisonTables.current().units().get(e.unitKey);
        return u == null ? 1 : u.cost();
    }

    public static double strength(Collection<RosterEntry> entries) {
        double s = 0;
        for (RosterEntry e : entries) {
            s += SiegeMath.unitStrength(cost(e), e.equipmentLevel);
        }
        return s;
    }

    static List<RosterEntry> entries(VillageRecord rec, Collection<UUID> ids) {
        List<RosterEntry> out = new ArrayList<>();
        if (rec.hywRoster == null) {
            return out;
        }
        for (UUID id : ids) {
            RosterEntry e = rec.hywRoster.entry(id);
            if (e != null && !e.state().terminal()) {
                out.add(e);
            }
        }
        return out;
    }

    /** The target's garrison at home: bound, living slots not away (loaded or not). */
    static List<RosterEntry> homeDefenders(VillageRecord target) {
        List<RosterEntry> out = new ArrayList<>();
        if (target.hywRoster == null) {
            return out;
        }
        for (RosterEntry e : target.hywRoster.entries()) {
            if (e.state().bound() && !e.duty.away()) {
                out.add(e);
            }
        }
        return out;
    }

    public static double defense(ServerLevel overworld, VillageRecord target) {
        return defense(overworld, target, 0);
    }

    /**
     * The target's defense against a host bringing {@code attackerEngines} siege engines: its garrison at home, weighted
     * Millénaire defenders, its own war engines at home, times the fortification bonus left after the engines' cut.
     */
    public static double defense(ServerLevel overworld, VillageRecord target, int attackerEngines) {
        SettlementSource source = Services.settlements();
        int mill = source == null ? target.defendingStrength
                : source.raidStrength(overworld, target.villageId).map(a -> a[1]).orElse(target.defendingStrength);
        PoliticsTables.ArsenalRule ar = ArsenalService.rule(target);
        int ownEngines = 0;
        if (target.hywRoster != null) {
            for (RosterEntry e : ArsenalService.engines(target.hywRoster)) {
                if (e.duty != Duty.SIEGE) {
                    ownEngines++;
                }
            }
        }
        double plain = SiegeMath.defense(strength(homeDefenders(target)) + ownEngines * ar.engineStrength(), mill, 0, rule(target));
        double fort = 1 + Math.min(0.5, Math.max(0, target.fortification) / 100.0);
        return plain * dev.hywmill.politics.war.ArsenalPlan.fortificationLeft(fort, attackerEngines, ar);
    }

    /** The host's strength: its soldiers by cost and level, its engines by the arsenal rule (engineers ride with them). */
    static double hostStrength(VillageRecord a, List<RosterEntry> alive) {
        double h = strength(soldiers(a, alive));
        return h + engineCount(a, alive) * ArsenalService.rule(a).engineStrength();
    }

    static List<RosterEntry> soldiers(VillageRecord a, List<RosterEntry> alive) {
        return alive.stream().filter(e -> a.hywRoster == null || !a.hywRoster.isArsenal(e)).toList();
    }

    static int engineCount(VillageRecord a, List<RosterEntry> alive) {
        return (int) alive.stream().filter(e -> a.hywRoster != null && a.hywRoster.isArsenal(e)
                && dev.hywmill.politics.war.ArsenalPlan.isEngine(e.unitKey)).count();
    }

    // ------------------------------------------------------------------ launch

    /** Starts a siege now (the counsel and village decisions come through here; admins too). */
    public Launch launch(ServerLevel overworld, UUID attackerId, UUID targetId, @Nullable UUID counsel, long tick, boolean requireWar) {
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord a = ledger.get(attackerId);
        VillageRecord t = ledger.get(targetId);
        if (a == null || t == null || attackerId.equals(targetId)) {
            return new Launch(SiegeMath.Refusal.NOT_AT_WAR, null, "unknown village");
        }
        PoliticsTables.SiegeRule r = rule(a);
        if (!r.enabled()) {
            return new Launch(SiegeMath.Refusal.DISABLED, null, "sieges are turned off");
        }
        if (requireWar && !RelationProjector.atWar(ledger, attackerId, targetId)) {
            return new Launch(SiegeMath.Refusal.NOT_AT_WAR, null, a.name + " is not at war with " + t.name);
        }
        if (byAttacker(ledger, attackerId) != null) {
            return new Launch(SiegeMath.Refusal.ALREADY_BESIEGING, null, a.name + "'s host is already away");
        }
        if (against(ledger, targetId) != null) {
            return new Launch(SiegeMath.Refusal.TARGET_BESIEGED, null, t.name + " is already besieged");
        }
        List<UUID> host = planHost(a);
        if (host.size() < r.minCommit()) {
            return new Launch(SiegeMath.Refusal.HOST_TOO_SMALL, null, a.name + " can spare only " + host.size() + " soldier(s); a siege needs "
                    + r.minCommit());
        }
        UUID id = UUID.nameUUIDFromBytes((attackerId + ">" + targetId + ">siege>" + tick).getBytes(StandardCharsets.UTF_8));
        Siege s = new Siege(id, attackerId, targetId, counsel, tick);
        s.enter(Siege.Phase.MUSTER, tick, tick + r.musterTicks());
        s.host.addAll(host);
        s.hostStart = host.size();
        int engines = 0;
        for (RosterEntry e : a.hywRoster.arsenal()) {
            // the village's war engines and their crews march with the host
            if (e.state() == UnitState.GARRISONED && e.duty != Duty.SIEGE) {
                s.host.add(e.rosterId);
                engines += dev.hywmill.politics.war.ArsenalPlan.isEngine(e.unitKey) ? 1 : 0;
            }
        }
        UnitProvider units = Services.units();
        BlockPos muster = GarrisonService.anchorOf(a);
        for (UUID rid : s.host) {
            RosterEntry e = a.hywRoster.entry(rid);
            e.transition(UnitState.DEPLOYED, tick);
            e.duty = Duty.SIEGE;
            Entity ent = e.entityUuid != null ? GarrisonService.find(overworld.getServer(), e.entityUuid) : null;
            if (ent != null && units != null) {
                units.disengage(ent);
                units.setHome(ent, muster);
            }
        }
        a.hywRoster.lastSiegeTick = tick;
        ledger.sieges().add(s);
        ReliefService.plan(overworld, ledger, s, a, t, tick);
        ledger.setDirty();
        count(C_LAUNCHED);
        String who = counsel != null ? " on " + PoliticsService.playerName(overworld, counsel) + "'s counsel" : "";
        String text = a.name + " musters " + host.size() + " soldiers" + (engines > 0 ? " and " + engines + " siege engine" + (engines == 1 ? "" : "s") : "")
                + " to besiege " + t.name + who + "; they march in about "
                + r.musterTicks() / 1200 + " min";
        chronicle(overworld, a, t, tick, text);
        announce(overworld, ledger, s, a, t, text);
        HmLog.info("Siege {} launched: {} -> {} with {} unit(s){}", s.id.toString().substring(0, 8), a.name, t.name, host.size(), who);
        return new Launch(SiegeMath.Refusal.OK, s, text);
    }

    // ------------------------------------------------------------------ player counsel

    /**
     * A player on campaign with {@code attackerId} against {@code targetId} suggests a siege. With {@code dryRun} nothing is
     * spent or rolled (the Politics screen's verdict); {@code forcedDraw} (dev only) replaces the draw.
     */
    public Counsel suggest(ServerLevel overworld, UUID player, UUID attackerId, UUID targetId, boolean dryRun, @Nullable Double forcedDraw) {
        return suggest(overworld, player, attackerId, targetId, dryRun, forcedDraw, false);
    }

    /** {@code free} (operator command): no cooldown and no diplomacy points; every other rule and the roll still apply. */
    public Counsel suggest(ServerLevel overworld, UUID player, UUID attackerId, UUID targetId, boolean dryRun, @Nullable Double forcedDraw,
                           boolean free) {
        SettlementSource source = Services.settlements();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord a = ledger.get(attackerId);
        VillageRecord t = ledger.get(targetId);
        if (source == null || a == null || t == null || attackerId.equals(targetId)) {
            return new Counsel(SiegeMath.Refusal.NOT_AT_WAR, 0, false, false, "unknown village");
        }
        long now = overworld.getGameTime();
        PoliticsTables.SiegeRule r = rule(a);
        Campaign c = RelationProjector.campaignOf(ledger, player);
        boolean onCampaign = c != null && c.active(now) && c.ally().equals(attackerId) && c.enemy().equals(targetId);
        PoliticsRecord pr = a.politics.peek(player);
        Standing own = pr == null ? Standing.STRANGER : pr.status;
        Standing standing = PoliticsView.effective(overworld, ledger, source, a, player, own, null);
        List<UUID> host = planHost(a);
        OptionalInt points = source.diplomacyPoints(overworld, attackerId, player);
        SiegeMath.Facts f = new SiegeMath.Facts(RelationProjector.atWar(ledger, attackerId, targetId), onCampaign, standing,
                byAttacker(ledger, attackerId) != null, against(ledger, targetId) != null, host.size(), now,
                free || pr == null ? -1 : pr.lastSiegeCounsel, free || points.isEmpty() ? -1 : points.getAsInt());
        SiegeMath.Refusal refusal = SiegeMath.check(f, r);
        if (refusal != SiegeMath.Refusal.OK) {
            return new Counsel(refusal, 0, false, false, refusalText(refusal, a, t, r, f));
        }
        double h = strength(entries(a, host)), d = defense(overworld, t);
        double chance = SiegeMath.counselChance(standing, h, d, r);
        String odds = dev.hywmill.politics.RaidCounsel.band(chance) + "; host of " + host.size() + (d > 1.5 * h ? ", " + t.name + " looks too strong" : "");
        if (dryRun) {
            return new Counsel(SiegeMath.Refusal.OK, chance, false, false, "costs " + r.counselPoints() + " diplomacy points with " + a.name + "; " + odds);
        }
        for (int i = 0; !free && i < r.counselPoints(); i++) {
            if (!source.consumeDiplomacyPoint(overworld, attackerId, player)) {
                return new Counsel(SiegeMath.Refusal.NO_DIPLOMACY_POINT, chance, false, i > 0, "no Millénaire diplomacy point left with " + a.name);
            }
        }
        if (!free) {
            a.politics.get(player).lastSiegeCounsel = now;
        }
        ledger.setDirty();
        UUID seedId = UUID.nameUUIDFromBytes((player + ">" + attackerId + ">" + targetId + ">siegecounsel>" + now).getBytes(StandardCharsets.UTF_8));
        double draw = forcedDraw != null ? forcedDraw : SiegeMath.draw(seedId.getMostSignificantBits() ^ seedId.getLeastSignificantBits());
        String who = PoliticsService.playerName(overworld, player);
        if (draw >= chance) {
            HmLog.info("Siege counsel: {} asks {} to besiege {}: refused (chance {}, draw {})", who, a.name, t.name, fmt(chance), fmt(draw));
            return new Counsel(SiegeMath.Refusal.OK, chance, false, true, a.name + "'s council will not march on " + t.name + " yet (" + odds + ")");
        }
        Launch l = launch(overworld, attackerId, targetId, player, now, true);
        if (!l.ok()) {
            return new Counsel(l.refusal(), chance, false, true, a.name + " agreed, but " + l.detail());
        }
        HmLog.info("Siege counsel: {} asks {} to besiege {}: agreed (chance {}, draw {})", who, a.name, t.name, fmt(chance), fmt(draw));
        return new Counsel(SiegeMath.Refusal.OK, chance, true, true, a.name + " heeds your counsel: " + l.detail());
    }

    private static String refusalText(SiegeMath.Refusal refusal, VillageRecord a, VillageRecord t, PoliticsTables.SiegeRule r, SiegeMath.Facts f) {
        return switch (refusal) {
            case DISABLED -> "sieges are turned off";
            case NOT_AT_WAR -> a.name + " is not at war with " + t.name;
            case NOT_ON_CAMPAIGN -> "you must be on campaign with " + a.name + " against " + t.name;
            case STANDING_TOO_LOW -> a.name + " only marches on the counsel of a patron or a sworn friend (you are " + f.standing().name().toLowerCase() + ")";
            case ALREADY_BESIEGING -> a.name + "'s host is already away";
            case TARGET_BESIEGED -> t.name + " is already besieged";
            case HOST_TOO_SMALL -> a.name + " can spare only " + f.hostSize() + " soldier(s); a siege needs " + r.minCommit();
            case COOLDOWN -> "you counselled " + a.name + " recently; wait " + (r.counselCooldown() - (f.now() - f.lastCounsel())) / 20 + " s";
            case NO_DIPLOMACY_POINT -> "needs " + r.counselPoints() + " Millénaire diplomacy points with " + a.name + ", you have " + Math.max(0, f.points());
            case OK -> "";
        };
    }

    // ------------------------------------------------------------------ tick

    public void tick(ServerLevel overworld, long tick) {
        if (tick % INTERVAL != OFFSET) {
            return;
        }
        long t0 = perf.start();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        for (Siege s : new ArrayList<>(ledger.sieges())) {
            try {
                step(overworld, ledger, s, tick);
            } catch (RuntimeException ex) {
                HmLog.warn("Siege {} step failed: {}", s.id, ex.toString());
            }
        }
        if (tick % AI_INTERVAL == AI_OFFSET) {
            villageDecisions(overworld, ledger, tick);
        }
        payTributes(overworld, ledger, tick);
        if (tick % AI_INTERVAL == AI_OFFSET) {
            returnOrphans(overworld, ledger, tick); // repair: soldiers left on a siege that no longer exists come home
        }
        try {
            bars(overworld, ledger);
        } catch (RuntimeException ex) {
            HmLog.warn("Siege boss bars failed: {}", ex.toString());
        }
        perf.stop("siege.tick", t0);
    }

    private void step(ServerLevel overworld, GarrisonLedger ledger, Siege s, long tick) {
        VillageRecord a = ledger.get(s.attacker);
        VillageRecord t = ledger.get(s.target);
        if (a == null || a.hywRoster == null) {
            ledger.sieges().remove(s);
            ledger.setDirty();
            HmLog.info("Siege {} dropped: the attacking village is gone", s.id);
            return;
        }
        PoliticsTables.SiegeRule r = rule(a);
        ReliefService.step(overworld, ledger, s, a, t, tick);
        List<RosterEntry> alive = entries(a, s.host);
        if (soldiers(a, alive).isEmpty() && s.phase != Siege.Phase.RETURN) {
            finish(overworld, ledger, s, a, t, Siege.Outcome.LOST, "the whole host fell", tick, false);
            return;
        }
        if (t == null && s.outcome == Siege.Outcome.NONE) {
            s.summary = "the target is gone";
            goHome(overworld, ledger, s, a, alive, tick, r, false);
            return;
        }
        switch (s.phase) {
            case MUSTER -> {
                if (tick >= s.phaseEnd) {
                    alive.forEach(e -> GarrisonService.stow(overworld, e));
                    long march = SiegeMath.marchTicks(Math.sqrt(a.center.distSqr(t.center)), r);
                    s.enter(Siege.Phase.MARCH, tick, tick + march);
                    ledger.setDirty();
                    announce(overworld, ledger, s, a, t, "The host of " + a.name + " (" + alive.size() + " soldiers) marches on " + t.name
                            + "; it arrives in about " + Math.max(1, march / 1200) + " min");
                    HmLog.info("Siege {}: {} unit(s) stowed, marching {} ticks", s.id.toString().substring(0, 8), alive.size(), march);
                }
            }
            case MARCH -> {
                if (!s.mercRolled && tick >= s.phaseEnd - dev.hywmill.politics.war.Mercenaries.LEAD) {
                    hireMercs(overworld, ledger, s, a, t, tick, false);
                }
                if (!s.aidRolled && tick >= s.phaseEnd - dev.hywmill.politics.war.Mercenaries.LEAD) {
                    defenderAid(overworld, ledger, s, a, t, tick, false);
                }
                if (tick >= s.phaseEnd) {
                    BlockPos landing = landing(overworld, a, t);
                    if (!s.forceUnwatched && watched(overworld, t, landing)) {
                        deploy(overworld, ledger, s, a, t, alive, landing, tick, r);
                    } else {
                        s.enter(Siege.Phase.WAIT, tick, tick + r.waitTicks());
                        ledger.setDirty();
                        HmLog.info("Siege {}: the host waits before {} (not loaded)", s.id.toString().substring(0, 8), t.name);
                    }
                }
            }
            case WAIT -> {
                BlockPos landing = landing(overworld, a, t);
                if (!s.forceUnwatched && watched(overworld, t, landing)) {
                    deploy(overworld, ledger, s, a, t, alive, landing, tick, r);
                } else if (tick >= s.phaseEnd) {
                    offscreen(overworld, ledger, s, a, t, alive, tick, r);
                }
            }
            case BATTLE -> battle(overworld, ledger, s, a, t, alive, tick, r);
            case RETURN -> bringHome(overworld, ledger, s, a, alive, tick);
        }
    }

    /**
     * Where the host arrives: outside the target, {@link #STAGING_MARGIN} blocks beyond its village radius on the side facing
     * the attacker's home (on the surface when loaded). Never inside the village: the host marches in from there.
     */
    static BlockPos landing(ServerLevel overworld, VillageRecord a, VillageRecord t) {
        int radius = t.villageRadius > 0 ? t.villageRadius : DEFAULT_RADIUS;
        int dist = radius + STAGING_MARGIN;
        double base = Math.atan2(a.center.getZ() - t.center.getZ(), a.center.getX() - t.center.getX());
        if (a.center.getX() == t.center.getX() && a.center.getZ() == t.center.getZ()) {
            base = 0; // same spot as home (dev setups): any side will do
        }
        BlockPos first = null;
        // the side facing home first, then ever wider round the village: the first dry spot with a dry way in (no lake or
        // river between the host and the village, which it could not cross)
        for (int k = 0; k < 16; k++) {
            double angle = base + Math.toRadians(22.5 * ((k + 1) / 2) * (k % 2 == 1 ? 1 : -1));
            BlockPos p = surface(overworld, t.center.offset((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist)));
            if (first == null) {
                first = p;
            }
            if (dryApproach(overworld, p, t.center, radius / 2)) {
                return p;
            }
        }
        return first;
    }

    /**
     * True if {@code from} and the ground every 4 blocks towards {@code to}, until {@code stopShort} blocks from it, is loaded
     * dry land (no water or lava on top). Unloaded ground counts as not dry.
     */
    static boolean dryApproach(ServerLevel overworld, BlockPos from, BlockPos to, int stopShort) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        for (double d = 0; d <= Math.max(0, len - stopShort); d += 4) {
            int x = (int) Math.round(from.getX() + dx / Math.max(1, len) * d), z = (int) Math.round(from.getZ() + dz / Math.max(1, len) * d);
            if (!overworld.hasChunk(x >> 4, z >> 4)) {
                return false;
            }
            int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (!overworld.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Dry, safe ground for a unit near {@code at}: the spot search (sturdy floor, no fluid), then points every 8 blocks back
     * towards home (further from the target). Null if nothing loaded qualifies.
     */
    @Nullable
    static Vec3 dryGround(ServerLevel overworld, BlockPos at, BlockPos home, BlockPos targetCenter, UUID rosterId) {
        for (int d = 0; d <= 64; d += 8) {
            Vec3 v = GarrisonService.spotNear(overworld, surface(overworld, behind(at, home, d)), rosterId);
            if (v != null) {
                return v;
            }
        }
        return null; // never inside the target: wait and try again
    }

    /**
     * Slot {@code index} of a formation at {@code at}: ranks of {@code perRow} across the line from {@code at} to
     * {@code home}, {@code spacing} blocks apart, each further rank {@code spacing} blocks nearer home.
     */
    static BlockPos formation(BlockPos at, BlockPos home, int index, int perRow, int spacing) {
        double dx = home.getX() - at.getX(), dz = home.getZ() - at.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) {
            dx = 0;
            dz = 1;
            len = 1;
        }
        dx /= len;
        dz /= len;
        double side = (index % perRow - (perRow - 1) / 2.0) * spacing;
        double depth = (index / perRow) * spacing;
        return at.offset((int) Math.round(dx * depth - dz * side), 0, (int) Math.round(dz * depth + dx * side));
    }

    /** {@code p} moved to the surface when its chunk is loaded (the spot search only looks a few blocks up and down). */
    static BlockPos surface(ServerLevel overworld, BlockPos p) {
        return overworld.hasChunk(p.getX() >> 4, p.getZ() >> 4)
                ? p.atY(overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ())) : p;
    }

    /** A point {@code dist} blocks from {@code from} towards {@code toward} (same height). */
    static BlockPos behind(BlockPos from, BlockPos toward, int dist) {
        double dx = toward.getX() - from.getX(), dz = toward.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) {
            return from;
        }
        return from.offset((int) Math.round(dx / len * dist), 0, (int) Math.round(dz / len * dist));
    }

    private static boolean watched(ServerLevel overworld, VillageRecord t, BlockPos landing) {
        return overworld.isPositionEntityTicking(landing) && overworld.isPositionEntityTicking(t.center);
    }

    private void deploy(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, List<RosterEntry> alive,
                        BlockPos landing, long tick, PoliticsTables.SiegeRule r) {
        int n = 0;
        // "behind" is away from the target (the landing may be on any side of the village, not only towards home)
        BlockPos away = landing.offset(landing.getX() - t.center.getX(), 0, landing.getZ() - t.center.getZ());
        BlockPos back = behind(landing, away, 24);
        // the soldiers land in groups of 4-8 round the near half of the village, the main group at the landing (post-M5)
        int soldiers = 0;
        for (RosterEntry e : alive) {
            soldiers += a.hywRoster.isArsenal(e) ? 0 : 1;
        }
        int[] sizes = dev.hywmill.politics.war.SiegeGroups.sizes(soldiers);
        BlockPos[] groups = groupLandings(overworld, t, landing, sizes.length, s.seed(0x6C616E64L));
        int[] placedIn = new int[groups.length];
        UnitProvider units = Services.units();
        int soldier = 0, engine = 0;
        for (RosterEntry e : alive) {
            boolean arsenal = a.hywRoster.isArsenal(e);
            // the engines (and their crews) set up in a line behind the landing point, away from the target; each group of soldiers
            // stands in spaced ranks at its own landing, facing the target
            int slot = arsenal ? engine++ : soldier++;
            if (e.entityUuid != null) {
                n += arsenal ? 0 : 1;
                continue;
            }
            int g = arsenal ? 0 : dev.hywmill.politics.war.SiegeGroups.groupOf(slot, sizes);
            BlockPos gAt = arsenal ? back : groups[g];
            BlockPos gAway = gAt.offset(gAt.getX() - t.center.getX(), 0, gAt.getZ() - t.center.getZ());
            BlockPos at = arsenal ? formation(back, away, slot / 2, 6, 5) : formation(gAt, gAway, placedIn[g]++, 4, 3);
            Vec3 spot = dryGround(overworld, at, arsenal ? away : gAway, t.center, e.rosterId);
            if (spot == null) {
                continue; // no dry, safe ground found this time: this unit tries again on the next step
            }
            if (GarrisonService.materialize(overworld, a, e, spot, BlockPos.containing(spot), tick) && !arsenal) {
                n++;
                Entity ent = GarrisonService.find(overworld.getServer(), e.entityUuid);
                if (ent != null && units != null) {
                    units.setAutonomous(ent, true); // in the thick of a siege every soldier seeks out the enemy
                }
            }
        }
        if (n == 0) {
            // nobody could be placed on dry ground yet: wait before the target and try again (the wait's end still applies)
            if (s.phase != Siege.Phase.WAIT) {
                s.enter(Siege.Phase.WAIT, tick, tick + r.waitTicks());
                ledger.setDirty();
            }
            return;
        }
        musterExtras(overworld, s, t, tick); // the besieged's temporary help stands with them before the fight
        List<LivingEntity> defs = new ArrayList<>(defenders(overworld, t));
        defs.addAll(ReliefService.entities(overworld, ledger, s));
        stance(defs, true);
        s.defendersStart = defs.size();
        s.enter(Siege.Phase.BATTLE, tick, tick + r.battleTicks());
        ledger.setDirty();
        announce(overworld, ledger, s, a, t, "The host of " + a.name + " (" + n + " soldiers in " + groups.length + " group"
                + (groups.length == 1 ? "" : "s") + ") closes on " + t.name + " from every side; " + s.defendersStart + " defenders take up arms");
        HmLog.info("Siege {}: {} unit(s) materialized in {} group(s) round {} (main at {}); {} defender(s)", s.id.toString().substring(0, 8), n,
                groups.length, t.name, landing.toShortString(), s.defendersStart);
    }

    /**
     * Landing points of the host's groups: group 0 at the main landing, the others round the village's rim at the bearings of
     * {@link dev.hywmill.politics.war.SiegeGroups#bearings}, each on dry ground with a dry way in (turned a little either side
     * until one is found), else at the main landing.
     */
    static BlockPos[] groupLandings(ServerLevel overworld, VillageRecord t, BlockPos landing, int groups, long seed) {
        BlockPos[] out = new BlockPos[Math.max(1, groups)];
        out[0] = landing;
        int radius = t.villageRadius > 0 ? t.villageRadius : DEFAULT_RADIUS;
        double base = Math.atan2(landing.getZ() - t.center.getZ(), landing.getX() - t.center.getX());
        double[] bearings = dev.hywmill.politics.war.SiegeGroups.bearings(out.length, seed);
        for (int g = 1; g < out.length; g++) {
            int dist = radius + STAGING_MARGIN + dev.hywmill.politics.war.SiegeGroups.depth(g, seed);
            out[g] = landing;
            for (int k = 0; k < 5; k++) {
                double angle = base + Math.toRadians(bearings[g] + 12.0 * ((k + 1) / 2) * (k % 2 == 1 ? 1 : -1));
                BlockPos p = surface(overworld, t.center.offset((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist)));
                if (dryApproach(overworld, p, t.center, radius / 2)) {
                    out[g] = p;
                    break;
                }
            }
        }
        return out;
    }

    /** Sets the combat stance of HYW units among {@code entities} (Millénaire villagers have none). */
    private static void stance(Collection<? extends Entity> entities, boolean autonomous) {
        UnitProvider units = Services.units();
        if (units == null) {
            return;
        }
        for (Entity e : entities) {
            if (units.isUnit(e)) {
                units.setAutonomous(e, autonomous);
            }
        }
    }

    /**
     * Post-M5 mercenaries: about a minute before the host arrives, a small chance ({@code force}: certainly) that its village
     * has hired a free company, which joins the host for this siege (see {@link dev.hywmill.politics.war.Mercenaries}).
     * Returns the number hired.
     */
    public int hireMercs(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick, boolean force) {
        s.mercRolled = true;
        ledger.setDirty();
        if (!s.mercCompany.isEmpty()) {
            return 0; // one company per siege
        }
        dev.hywmill.politics.war.Mercenaries.Hire h = force ? dev.hywmill.politics.war.Mercenaries.hire(s.seed(tick))
                : dev.hywmill.politics.war.Mercenaries.roll(s.seed(0x4D455243L), dev.hywmill.politics.war.Mercenaries.CHANCE);
        if (h == null) {
            return 0;
        }
        GarrisonTables tables = GarrisonTables.current();
        var table = tables.forCulture(a.culture);
        PoliticsTables.MobilizationRule mob = MobilizationService.rule(a);
        // hired men come as they are: equipped like the village's levies, a step below its regulars
        int level = dev.hywmill.garrison.Mobilization.equipmentLevel(dev.hywmill.garrison.Recruitment.equipmentLevel(a.tier, table),
                mob.equipmentFloor(), mob.equipmentDrop());
        int n = 0;
        for (String key : h.units()) {
            UnitSpec u = tables.units().get(key);
            if (u == null || !u.enabled()) {
                continue;
            }
            RosterEntry e = a.hywRoster.recruit(a.villageId, key, u.entityType(), level, tick, false);
            e.mobilized = true;
            e.mercLook = h.company().look();
            // straight into the host, stowed on the march like the rest of it
            e.transition(UnitState.SPAWNED, tick);
            e.transition(UnitState.GARRISONED, tick);
            e.transition(UnitState.DEPLOYED, tick);
            e.duty = Duty.SIEGE;
            s.host.add(e.rosterId);
            n++;
        }
        if (n == 0) {
            return 0;
        }
        s.hostStart += n;
        s.mercCompany = h.company().name();
        s.mercCount = n;
        ledger.setDirty();
        String text = a.name + " has struck a deal with " + h.company().name() + ": " + n + " mercenaries join its host before " + t.name;
        chronicle(overworld, a, t, tick, text);
        announce(overworld, ledger, s, a, t, text);
        HmLog.info("Siege {}: {} hired ({} soldiers: {})", s.id.toString().substring(0, 8), h.company().name(), n, h.units());
        return n;
    }

    // ------------------------------------------------------------------ help for the besieged (post-M5)

    /**
     * About a minute before the attackers arrive: each by chance ({@code force}: all), the target's militia takes up arms, it
     * hires a mercenary company, and (garrison and stronghold villages) its lord's household joins the defence. They become
     * temporary slots of the target's roster, at home: loaded and placed now if the village is loaded, else when the battle
     * starts; off-screen they count in its defense. They leave when the siege ends. Returns how many were raised.
     */
    public int defenderAid(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick, boolean force) {
        s.aidRolled = true;
        ledger.setDirty();
        if (t.hywRoster == null || t.loneBuilding) {
            return 0;
        }
        boolean lordly = t.tier == dev.hywmill.military.MilitaryTier.GARRISON || t.tier == dev.hywmill.military.MilitaryTier.STRONGHOLD;
        dev.hywmill.politics.war.DefenderAid.Aid aid = dev.hywmill.politics.war.DefenderAid.roll(s.seed(tick ^ 0x616964L), t.population, lordly, force);
        GarrisonTables tables = GarrisonTables.current();
        var table = tables.forCulture(t.culture);
        PoliticsTables.MobilizationRule mob = MobilizationService.rule(t);
        int levyLevel = dev.hywmill.garrison.Mobilization.equipmentLevel(dev.hywmill.garrison.Recruitment.equipmentLevel(t.tier, table),
                mob.equipmentFloor(), mob.equipmentDrop());
        int raised = 0;
        if (aid.militia() > 0) {
            List<String> pool = new ArrayList<>();
            mob.levyUnits().forEach((k, w) -> {
                for (int i = 0; i < w; i++) {
                    pool.add(k);
                }
            });
            int n = raiseExtras(t, s, dev.hywmill.politics.war.DefenderAid.draw(pool, aid.militia(), s.seed(1)), "militia", "", true, levyLevel, tick);
            if (n > 0) {
                raised += n;
                aidNews(overworld, ledger, s, a, t, tick, "The bells of " + t.name + " ring: " + n + " of its people take up arms against the host of " + a.name);
            }
        }
        if (aid.mercs() != null) {
            int n = raiseExtras(t, s, aid.mercs().units(), "merc", aid.mercs().company().look(), true, levyLevel, tick);
            if (n > 0) {
                raised += n;
                aidNews(overworld, ledger, s, a, t, tick, t.name + " has struck a deal with " + aid.mercs().company().name() + ": " + n
                        + " mercenaries man its defences against " + a.name);
            }
        }
        if (aid.household() > 0) {
            dev.hywmill.recruit.Squads.Squad guard = household(t.culture);
            if (guard != null) {
                List<String> pool = new ArrayList<>();
                for (dev.hywmill.recruit.Squads.Member m : guard.members()) {
                    for (int i = 0; i < m.count(); i++) {
                        pool.add(m.unit());
                    }
                }
                int level = table.tier(dev.hywmill.military.MilitaryTier.STRONGHOLD).equipmentLevel();
                int n = raiseExtras(t, s, dev.hywmill.politics.war.DefenderAid.draw(pool, aid.household(), s.seed(2)), "household", guard.look(), false,
                        level, tick);
                if (n > 0) {
                    raised += n;
                    aidNews(overworld, ledger, s, a, t, tick, "The lord of " + t.name + " is at home: his household, " + n + " of " + guard.name()
                            + ", stands with the defenders");
                }
            }
        }
        if (raised > 0 && overworld.isPositionEntityTicking(GarrisonService.anchorOf(t))) {
            musterExtras(overworld, s, t, tick);
        }
        HmLog.info("Siege {}: help for {}: militia {}, mercenaries {}, household {} ({} raised)", s.id.toString().substring(0, 8), t.name,
                aid.militia(), aid.mercs() == null ? 0 : aid.mercs().units().size(), aid.household(), raised);
        return raised;
    }

    /** The culture's elite squad for a lord's household: its best unique squad, else its best squad. */
    @Nullable
    static dev.hywmill.recruit.Squads.Squad household(String culture) {
        dev.hywmill.recruit.Squads.Squad best = null;
        for (dev.hywmill.recruit.Squads.Squad q : dev.hywmill.recruit.Squads.current().forCulture(culture)) {
            if (q.look().isEmpty() || q.category() == dev.hywmill.recruit.Squads.Category.RANGED) {
                continue;
            }
            int score = q.quality().ordinal() * 2 + (q.category() == dev.hywmill.recruit.Squads.Category.UNIQUE ? 1 : 0);
            int bestScore = best == null ? -1 : best.quality().ordinal() * 2 + (best.category() == dev.hywmill.recruit.Squads.Category.UNIQUE ? 1 : 0);
            if (score > bestScore) {
                best = q;
            }
        }
        return best;
    }

    /** New temporary slots of the target, at home (GARRISONED, stowed until mustered). Returns how many. */
    private static int raiseExtras(VillageRecord t, Siege s, List<String> units, String kind, String look, boolean mobilized, int level, long tick) {
        GarrisonTables tables = GarrisonTables.current();
        int n = 0;
        for (String key : units) {
            UnitSpec u = tables.units().get(key);
            if (u == null || !u.enabled()) {
                continue;
            }
            RosterEntry e = t.hywRoster.recruit(t.villageId, key, u.entityType(), level, tick, false);
            e.mobilized = mobilized;
            e.mercLook = look;
            e.extra = kind;
            e.transition(UnitState.SPAWNED, tick);
            e.transition(UnitState.GARRISONED, tick);
            s.extras.add(e.rosterId);
            n++;
        }
        return n;
    }

    /** Places the target's stowed temporary defenders round its anchor (loaded ground only; the rest try again later). */
    static void musterExtras(ServerLevel overworld, Siege s, VillageRecord t, long tick) {
        if (t.hywRoster == null || s.extras.isEmpty()) {
            return;
        }
        BlockPos anchor = GarrisonService.anchorOf(t);
        if (!overworld.isPositionEntityTicking(anchor)) {
            return;
        }
        int i = 0;
        for (UUID id : s.extras) {
            RosterEntry e = t.hywRoster.entry(id);
            if (e == null || e.state().terminal() || e.entityUuid != null) {
                continue;
            }
            Vec3 spot = GarrisonService.spotNear(overworld, formation(anchor, t.center.offset(1, 0, 0), i++, 6, 3), e.rosterId);
            if (spot != null) {
                GarrisonService.materialize(overworld, t, e, spot, anchor, tick);
            }
        }
    }

    /** The siege is over: the besieged's temporary defenders go home (LOST, DISCHARGED; not a loss). */
    static void dismissExtras(ServerLevel overworld, GarrisonLedger ledger, Siege s, long tick) {
        VillageRecord t = ledger.get(s.target);
        if (t == null || t.hywRoster == null || s.extras.isEmpty()) {
            s.extras.clear();
            return;
        }
        int n = 0;
        for (UUID id : s.extras) {
            RosterEntry e = t.hywRoster.entry(id);
            if (e != null && !e.state().terminal()) {
                MobilizationService.discharge(overworld, e, tick);
                n++;
            }
        }
        s.extras.clear();
        ledger.setDirty();
        if (n > 0) {
            HmLog.info("Siege {}: {} temporary defender(s) of {} go home", s.id.toString().substring(0, 8), n, t.name);
        }
    }

    private static void aidNews(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick, String text) {
        chronicle(overworld, a, t, tick, text);
        announce(overworld, ledger, s, a, t, text);
        HmLog.info("Siege {}: {}", s.id.toString().substring(0, 8), text);
    }

    // ------------------------------------------------------------------ boss bars

    /**
     * One boss bar per siege in battle (post-M5), shown to players near the target: the two villages, how many stand on each
     * side, and the defenders' share still standing; in the attacker's first livery colour. Removed when the battle ends.
     */
    private void bars(ServerLevel overworld, GarrisonLedger ledger) {
        Set<UUID> live = new HashSet<>();
        for (Siege s : ledger.sieges()) {
            VillageRecord a = ledger.get(s.attacker), t = ledger.get(s.target);
            if (s.phase != Siege.Phase.BATTLE || s.outcome != Siege.Outcome.NONE || a == null || t == null || a.hywRoster == null) {
                continue;
            }
            live.add(s.id);
            net.minecraft.server.level.ServerBossEvent bar = bars.computeIfAbsent(s.id, id -> {
                HmLog.info("Siege {}: boss bar raised over {} ({})", id.toString().substring(0, 8), t.name, barColour(overworld, a));
                return new net.minecraft.server.level.ServerBossEvent(Component.empty(), barColour(overworld, a),
                        net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_10);
            });
            int host = soldiers(a, entries(a, s.host)).size();
            List<LivingEntity> defs = new ArrayList<>(defenders(overworld, t));
            defs.addAll(ReliefService.entities(overworld, ledger, s));
            bar.setName(Component.literal("Siege of " + t.name + ": " + a.name + " " + host + "/" + s.hostStart + " vs " + t.name + " " + defs.size()
                    + "/" + s.defendersStart + (s.pausedSince >= 0 ? " (paused)" : "")));
            bar.setProgress(s.defendersStart <= 0 ? 0f : Math.max(0f, Math.min(1f, defs.size() / (float) s.defendersStart)));
            double range = (t.villageRadius > 0 ? t.villageRadius : DEFAULT_RADIUS) + STAGING_MARGIN + 64;
            Set<ServerPlayer> near = new HashSet<>();
            for (ServerPlayer p : overworld.players()) {
                if (p.distanceToSqr(Vec3.atCenterOf(t.center)) <= range * range) {
                    near.add(p);
                }
            }
            for (ServerPlayer p : new ArrayList<>(bar.getPlayers())) {
                if (!near.contains(p)) {
                    bar.removePlayer(p);
                }
            }
            near.forEach(bar::addPlayer);
        }
        bars.entrySet().removeIf(en -> {
            if (!live.contains(en.getKey())) {
                en.getValue().removeAllPlayers();
                return true;
            }
            return false;
        });
    }

    /** The boss bar colour nearest the village's first livery colour (pink when it has none). */
    static net.minecraft.world.BossEvent.BossBarColor barColour(ServerLevel overworld, VillageRecord v) {
        int[] liv = LiveryService.of(overworld, v);
        return liv == null ? net.minecraft.world.BossEvent.BossBarColor.PINK : barColour(net.minecraft.world.item.DyeColor.byId(liv[0]));
    }

    static net.minecraft.world.BossEvent.BossBarColor barColour(net.minecraft.world.item.DyeColor d) {
        return switch (d) {
            case RED, ORANGE, BROWN -> net.minecraft.world.BossEvent.BossBarColor.RED;
            case YELLOW -> net.minecraft.world.BossEvent.BossBarColor.YELLOW;
            case LIME, GREEN -> net.minecraft.world.BossEvent.BossBarColor.GREEN;
            case CYAN, LIGHT_BLUE, BLUE -> net.minecraft.world.BossEvent.BossBarColor.BLUE;
            case PURPLE, MAGENTA -> net.minecraft.world.BossEvent.BossBarColor.PURPLE;
            case PINK -> net.minecraft.world.BossEvent.BossBarColor.PINK;
            default -> net.minecraft.world.BossEvent.BossBarColor.WHITE; // white, greys, black
        };
    }

    /** The target's fighting defenders that are loaded and alive: its garrison at home and Millénaire's combatants. */
    static List<LivingEntity> defenders(ServerLevel overworld, VillageRecord t) {
        List<LivingEntity> out = new ArrayList<>();
        for (RosterEntry e : homeDefenders(t)) {
            if (e.entityUuid != null && GarrisonService.find(overworld.getServer(), e.entityUuid) instanceof LivingEntity le && le.isAlive()) {
                out.add(le);
            }
        }
        SettlementSource source = Services.settlements();
        if (source != null) {
            dev.hywmill.military.doctrine.MilitiaPolicy policy = dev.hywmill.settlement.GarrisonUpdater.resolveDoctrine(t).doctrine().militiaPolicy();
            dev.hywmill.military.classify.RoleTable roles = dev.hywmill.military.classify.RoleTables.current();
            for (SettlementSource.RosterEntry d : source.defenseRoster(overworld, t.villageId)) {
                if (dev.hywmill.politics.war.RoeState.combatant(dev.hywmill.military.classify.RoleClassifier.villager(d.facts(), roles), policy)
                        && overworld.getEntity(d.id()) instanceof LivingEntity le && le.isAlive()) {
                    out.add(le);
                }
            }
        }
        return out;
    }

    /** Everyone the host attacks: the target's garrison and every one of its villagers (Millénaire villagers respawn). */
    static List<LivingEntity> targets(ServerLevel overworld, VillageRecord t) {
        List<LivingEntity> out = new ArrayList<>();
        for (RosterEntry e : homeDefenders(t)) {
            if (e.entityUuid != null && GarrisonService.find(overworld.getServer(), e.entityUuid) instanceof LivingEntity le && le.isAlive()) {
                out.add(le);
            }
        }
        SettlementSource source = Services.settlements();
        if (source != null) {
            for (SettlementSource.RosterEntry d : source.defenseRoster(overworld, t.villageId)) {
                if (overworld.getEntity(d.id()) instanceof LivingEntity le && le.isAlive()) {
                    out.add(le);
                }
            }
        }
        return out;
    }

    private void battle(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, List<RosterEntry> alive, long tick,
                        PoliticsTables.SiegeRule r) {
        if (s.forceUnwatched || !overworld.isPositionEntityTicking(t.center)) {
            // nobody near: the battle pauses where it stands (units stay in their chunks) and its clock stops; after a long
            // absence it ends on the shares it stood at, without new losses
            if (s.pausedSince < 0) {
                s.pausedSince = tick;
                ledger.setDirty();
                HmLog.info("Siege {}: the battle pauses (nobody near {})", s.id.toString().substring(0, 8), t.name);
            }
            s.phaseEnd += INTERVAL;
            if (tick - s.pausedSince >= PAUSE_LIMIT) {
                int standing = soldiers(a, alive).size();
                int defLeft = homeDefenders(t).size();
                Siege.Outcome o = SiegeMath.battle(standing, s.hostStart, Math.min(defLeft, s.defendersStart), s.defendersStart, true, r);
                finish(overworld, ledger, s, a, t, o, "left unwatched, it ended as it stood: " + standing + " of " + s.hostStart + " attackers, "
                        + Math.min(defLeft, s.defendersStart) + " of " + s.defendersStart + " defenders", tick, true);
            }
            return;
        }
        if (s.pausedSince >= 0) {
            HmLog.info("Siege {}: the battle resumes", s.id.toString().substring(0, 8));
            s.pausedSince = -1;
            ledger.setDirty();
        }
        List<LivingEntity> relief = ReliefService.entities(overworld, ledger, s);
        List<LivingEntity> defs = new ArrayList<>(defenders(overworld, t));
        defs.addAll(relief); // relief forces stand with the defenders
        List<LivingEntity> foes = new ArrayList<>(targets(overworld, t));
        foes.addAll(relief);
        UnitProvider units = Services.units();
        if (units != null) {
            for (RosterEntry e : alive) {
                Entity ent = e.entityUuid != null ? GarrisonService.find(overworld.getServer(), e.entityUuid) : null;
                if (ent == null || !ent.isAlive() || !(ent.level() instanceof ServerLevel level)) {
                    continue;
                }
                if (a.hywRoster.isArsenal(e)) {
                    continue; // engines hold their ground (their HYW home is where they set up) and fire at what comes in range
                }
                LivingEntity current = units.target(ent);
                if (current != null && current.isAlive()) {
                    continue;
                }
                LivingEntity best = null;
                double bestD = ENGAGE_RANGE * ENGAGE_RANGE;
                for (LivingEntity d : foes) {
                    double d2 = d.distanceToSqr(ent);
                    if (d2 < bestD) {
                        bestD = d2;
                        best = d;
                    }
                }
                if (best != null) {
                    units.engage(ent, best);
                } else {
                    // the main force storms the centre; the other squads sweep round the outskirts
                    int[] o = SiegeMath.sweepOffset(e.rosterId, t.villageRadius, tick - (s.phaseEnd - r.battleTicks()));
                    BlockPos goal = t.center.offset(o[0], 0, o[1]);
                    if (o[0] != 0 || o[1] != 0) {
                        goal = goal.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, goal.getX(), goal.getZ()));
                    }
                    BlockPos hop = DutyService.hopTarget(level, ent, goal, 24);
                    for (int turn = 1; hop == null && turn <= 6; turn++) {
                        hop = DutyService.hopTarget(level, ent, goal, 24, turn); // water or a wall ahead: try a detour to either side
                    }
                    // nothing dry within a hop: let the unit's own pathfinding take it towards the goal (round the water)
                    units.setHome(ent, hop != null ? hop : goal);
                }
            }
        }
        // helpers: players near the battle who are on campaign with a side, or who fight for it (struck the other side)
        double range = Math.max(HELPER_RANGE, (t.villageRadius > 0 ? t.villageRadius : DEFAULT_RADIUS) + STAGING_MARGIN + 32);
        Set<UUID> hostIds = new HashSet<>();
        for (RosterEntry e : alive) {
            if (e.entityUuid != null) {
                hostIds.add(e.entityUuid);
            }
        }
        Set<LivingEntity> foeSet = new HashSet<>(foes);
        for (ServerPlayer p : overworld.players()) {
            if (p.distanceToSqr(Vec3.atCenterOf(t.center)) > range * range) {
                continue;
            }
            Campaign c = RelationProjector.campaignOf(ledger, p.getUUID());
            boolean withAttacker = false, withDefender = false;
            if (c != null && c.active(tick)) {
                withAttacker = c.ally().equals(a.villageId) && c.enemy().equals(t.villageId);
                withDefender = c.ally().equals(t.villageId) && c.enemy().equals(a.villageId);
            }
            if (!withAttacker && !withDefender) {
                LivingEntity struck = p.getLastHurtMob();
                if (struck != null && p.tickCount - p.getLastHurtMobTimestamp() <= INTERVAL * 2) {
                    withAttacker = foeSet.contains(struck);
                    withDefender = hostIds.contains(struck.getUUID());
                }
            }
            if (withAttacker && !s.defenderHelpers.contains(p.getUUID()) && s.attackerHelpers.add(p.getUUID())) {
                ledger.setDirty();
                HmLog.info("Siege {}: {} fights with {}", s.id.toString().substring(0, 8), p.getGameProfile().getName(), a.name);
            } else if (withDefender && !s.attackerHelpers.contains(p.getUUID()) && s.defenderHelpers.add(p.getUUID())) {
                ledger.setDirty();
                HmLog.info("Siege {}: {} fights with {}", s.id.toString().substring(0, 8), p.getGameProfile().getName(), t.name);
            }
        }
        int standing = soldiers(a, alive).size();
        Siege.Outcome o = SiegeMath.battle(standing, s.hostStart, defs.size(), s.defendersStart, tick >= s.phaseEnd, r);
        if (o != Siege.Outcome.NONE) {
            String how = o == Siege.Outcome.WON ? (defs.size() + " of " + s.defendersStart + " defenders still standing")
                    : (standing + " of " + s.hostStart + " attackers still standing");
            finish(overworld, ledger, s, a, t, o, how, tick, true);
        }
    }

    private void offscreen(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, List<RosterEntry> alive, long tick,
                           PoliticsTables.SiegeRule r) {
        List<RosterEntry> home = homeDefenders(t);
        double h = hostStrength(a, alive), d = defense(overworld, t, engineCount(a, alive));
        for (dev.hywmill.politics.war.Relief rl : s.reliefs) {
            d += strength(ReliefService.present(ledger, s, rl.helper)); // relief forces at the target
        }
        double p = SiegeMath.winChance(h, d, r);
        boolean won = SiegeMath.draw(s.seed(tick)) < p;
        double[] loss = SiegeMath.losses(won, h, d, r);
        List<UUID> hostIds = alive.stream().map(e -> e.rosterId).toList();
        List<UUID> defIds = home.stream().map(e -> e.rosterId).toList();
        List<UUID> hostDead = SiegeMath.casualties(hostIds, loss[0], s.seed(1));
        List<UUID> defDead = SiegeMath.casualties(defIds, loss[1], s.seed(2));
        hostDead.forEach(id -> kill(overworld, a, id, tick));
        defDead.forEach(id -> kill(overworld, t, id, tick));
        for (dev.hywmill.politics.war.Relief rl : s.reliefs) {
            VillageRecord hv = ledger.get(rl.helper);
            if (hv != null) {
                // relief forces share the defenders' losses
                List<UUID> ids = ReliefService.present(ledger, s, rl.helper).stream().map(e -> e.rosterId).toList();
                SiegeMath.casualties(ids, loss[1], s.seed(3) ^ rl.helper.getMostSignificantBits()).forEach(id -> {
                    kill(overworld, hv, id, tick);
                    rl.killed++;
                });
            }
        }
        count(C_OFFSCREEN);
        HmLog.info("Siege {} decided off-screen: host {} (strength {}) vs {} (defense {}): P(win) {} -> {}; host lost {}, defenders lost {}",
                s.id.toString().substring(0, 8), alive.size(), fmt(h), t.name, fmt(d), fmt(p), won ? "WON" : "LOST", hostDead.size(), defDead.size());
        finish(overworld, ledger, s, a, t, won ? Siege.Outcome.WON : Siege.Outcome.LOST,
                "far from any witness; " + hostDead.size() + " attackers and " + defDead.size() + " defenders fell", tick, false);
    }

    /** An off-screen death: the slot is DEAD (killed); a loaded entity is removed (its discard is not a second loss). */
    static void kill(ServerLevel overworld, VillageRecord rec, UUID rosterId, long tick) {
        RosterEntry e = rec.hywRoster == null ? null : rec.hywRoster.entry(rosterId);
        if (e == null || e.state().terminal() || !e.state().canTransition(UnitState.DEAD)) {
            return;
        }
        UUID ent = e.entityUuid;
        e.transition(UnitState.DEAD, tick, LossReason.KILLED);
        rec.hywRoster.totals.killed++;
        Entity loaded = ent != null ? GarrisonService.find(overworld.getServer(), ent) : null;
        if (loaded != null) {
            loaded.discard();
        }
    }

    private void finish(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, @Nullable VillageRecord t, Siege.Outcome o, String how,
                        long tick, boolean watched) {
        s.outcome = o;
        PoliticsTables.SiegeRule r = rule(a);
        if (t != null && watched) {
            List<LivingEntity> defs = new ArrayList<>(defenders(overworld, t));
            defs.addAll(ReliefService.entities(overworld, ledger, s));
            stance(defs, false); // the defenders stand down to their usual stance
        }
        count(o == Siege.Outcome.WON ? C_WON : C_LOST);
        StringBuilder text = new StringBuilder();
        if (t != null) {
            VillageRecord winner = o == Siege.Outcome.WON ? a : t;
            VillageRecord loser = o == Siege.Outcome.WON ? t : a;
            int tribute = r.tribute(PoliticsTables.MilitaryTierKey.valueOf(loser.tier.name()));
            Set<UUID> helpers = o == Siege.Outcome.WON ? s.attackerHelpers : s.defenderHelpers;
            // post-M5: the full tribute is paid every day for 3-5 days (the first at once): levy points to the winner, money to
            // the helpers of the winning side
            Tribute due = new Tribute(loser.villageId, winner.villageId, tribute, SiegeMath.levy(tribute, r),
                    SiegeMath.helperPay(tribute, helpers.size(), r), Tribute.days(s.seed(0x545249L)), tick);
            due.helpers.addAll(helpers);
            text.append(o == Siege.Outcome.WON ? t.name + " fell to the host of " + a.name : t.name + " held against the host of " + a.name)
                    .append(" (").append(how).append("); ").append(loser.name).append(" pays ")
                    .append(dev.hywmill.recruit.RecruitOffers.money(tribute)).append(" a day in tribute to ").append(winner.name)
                    .append(" for ").append(due.days).append(" days");
            reward(overworld, ledger, winner, helpers, due, r, text.toString());
            chronicle(overworld, a, t, tick, text.toString());
            ledger.tributes().add(due);
            payTribute(overworld, ledger, due, tick);
        } else {
            text.append("The host of ").append(a.name).append(" comes home: ").append(how);
        }
        s.summary = text.toString();
        announce(overworld, ledger, s, a, t, s.summary);
        HmLog.info("Siege {} ended {}: {}", s.id.toString().substring(0, 8), o, s.summary);
        goHome(overworld, ledger, s, a, entries(a, s.host), tick, r, watched);
        if (t != null && PoliticsService.tables(a).warCounsel().peaceAfterSiege()) {
            // the loser sues for peace: the war ends and the relation rises above open conflict
            dev.hywmill.politics.service.WarCounselService.makePeace(overworld, ledger, a, t, tick,
                    (o == Siege.Outcome.WON ? t.name : a.name) + " lost a siege and sued for peace");
        }
    }

    private static void reward(ServerLevel overworld, GarrisonLedger ledger, VillageRecord winner, Set<UUID> helpers, Tribute due,
                               PoliticsTables.SiegeRule r, String text) {
        SettlementSource source = Services.settlements();
        PoliticsTables tables = PoliticsService.tables(winner);
        for (UUID p : helpers) {
            winner.politics.get(p).favor.earn(FavorSource.SIEGE_VICTORY, tables.favor());
            if (source != null && r.helperRep() != 0) {
                source.adjustReputation(overworld, winner.villageId, p, r.helperRep());
            }
            String note = "Your share of the tribute: " + dev.hywmill.recruit.RecruitOffers.money(due.helperPay) + " a day for " + due.days
                    + " days, and " + winner.name + " remembers your service";
            ServerPlayer online = PoliticsService.onlinePlayer(overworld, p);
            if (online != null) {
                online.sendSystemMessage(Component.literal("[Siege] " + note));
            } else {
                ledger.pendingPay().add(new PoliticsNbt.PendingPay(p, 0, text + ". " + note));
            }
        }
        ledger.setDirty();
    }

    // ------------------------------------------------------------------ tribute (post-M5)

    /** Pays every tribute that is due, once each Minecraft day; a tribute whose payer or payee is gone ends. */
    static void payTributes(ServerLevel overworld, GarrisonLedger ledger, long tick) {
        for (Tribute due : new ArrayList<>(ledger.tributes())) {
            if (tick >= due.nextTick) {
                payTribute(overworld, ledger, due, tick);
            }
        }
    }

    /** One day's tribute: the levy points from the loser to the winner, and each helper's share in money. */
    static void payTribute(ServerLevel overworld, GarrisonLedger ledger, Tribute due, long tick) {
        VillageRecord payer = ledger.get(due.payer), payee = ledger.get(due.payee);
        if (payer == null || payee == null || due.done()) {
            ledger.tributes().remove(due);
            ledger.setDirty();
            return;
        }
        double levy = due.levy; // the full tribute's levy points, every day
        if (payee.hywRoster != null) {
            payee.hywRoster.levyPoints += levy;
        }
        if (payer.hywRoster != null) {
            payer.hywRoster.levyPoints = Math.max(0, payer.hywRoster.levyPoints - levy);
        }
        int share = due.helperInstallment();
        int n = due.paid + 1;
        String what = payer.name + " pays day " + n + " of " + due.days + " of its tribute to " + payee.name;
        SettlementSource source = Services.settlements();
        for (UUID p : due.helpers) {
            String note = what + ": your share is " + dev.hywmill.recruit.RecruitOffers.money(share);
            ServerPlayer online = PoliticsService.onlinePlayer(overworld, p);
            if (online != null) {
                if (source != null && share > 0) {
                    source.giveMoney(online, share);
                }
                online.sendSystemMessage(Component.literal("[Tribute] " + note));
            } else {
                ledger.pendingPay().add(new PoliticsNbt.PendingPay(p, source != null ? share : 0, note));
            }
        }
        due.paid = n;
        due.nextTick = tick + Tribute.DAY;
        if (due.done()) {
            ledger.tributes().remove(due);
            chronicle(overworld, payee, payer, tick, payer.name + " has paid its tribute to " + payee.name + " in full");
        }
        ledger.setDirty();
        HmLog.info("Tribute: {} ({} levy, {} to each of {} helper(s))", what, String.format("%.2f", levy), share, due.helpers.size());
    }

    /** One line per tribute still being paid (for {@code /hywmill war tributes}). */
    public static List<String> describeTributes(ServerLevel overworld, GarrisonLedger ledger) {
        List<String> out = new ArrayList<>();
        long now = overworld.getGameTime();
        for (Tribute due : ledger.tributes()) {
            VillageRecord payer = ledger.get(due.payer), payee = ledger.get(due.payee);
            out.add((payer == null ? "?" : payer.name) + " -> " + (payee == null ? "?" : payee.name) + " " + due.paid + "/" + due.days + " days paid, "
                    + dev.hywmill.recruit.RecruitOffers.money(due.total) + " a day, next in " + Math.max(0, due.nextTick - now) / 20 + " s, helpers "
                    + due.helpers.size());
        }
        return out;
    }

    /**
     * Peace between {@code x} and {@code y} (post-M5): every siege between them that has not ended yet is called off, and its
     * host marches home without an outcome (no tribute, no losses). Returns the number recalled.
     */
    public static int recall(ServerLevel overworld, GarrisonLedger ledger, UUID x, UUID y, long tick) {
        int n = 0;
        for (Siege s : new ArrayList<>(ledger.sieges())) {
            boolean pair = (s.attacker.equals(x) && s.target.equals(y)) || (s.attacker.equals(y) && s.target.equals(x));
            if (!pair || s.outcome != Siege.Outcome.NONE || s.phase == Siege.Phase.RETURN) {
                continue;
            }
            VillageRecord a = ledger.get(s.attacker);
            if (a == null || a.hywRoster == null) {
                continue;
            }
            VillageRecord t = ledger.get(s.target);
            s.summary = "The host of " + a.name + " is called home: peace" + (t != null ? " with " + t.name : "");
            announce(overworld, ledger, s, a, t, s.summary);
            HmLog.info("Siege {} recalled: {}", s.id.toString().substring(0, 8), s.summary);
            goHome(overworld, ledger, s, a, entries(a, s.host), tick, rule(a), false);
            n++;
        }
        return n;
    }

    /** Survivors leave the target (stowed) and march home. */
    private static void goHome(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, List<RosterEntry> alive, long tick,
                               PoliticsTables.SiegeRule r, boolean watched) {
        alive.forEach(e -> GarrisonService.stow(overworld, e));
        List<RosterEntry> going = new ArrayList<>();
        for (RosterEntry e : alive) {
            if (!e.mercLook.isEmpty()) {
                MobilizationService.discharge(overworld, e, tick); // the hired company is paid off and goes its own way
            } else {
                going.add(e);
            }
        }
        if (going.size() < alive.size()) {
            HmLog.info("Siege {}: {} mercenaries of {} paid off", s.id.toString().substring(0, 8), alive.size() - going.size(), s.mercCompany);
        }
        dismissExtras(overworld, ledger, s, tick);
        alive = going;
        VillageRecord t = ledger.get(s.target);
        long march = t == null ? r.minMarch() : SiegeMath.marchTicks(Math.sqrt(a.center.distSqr(t.center)), r);
        s.enter(Siege.Phase.RETURN, tick, tick + march);
        if (alive.isEmpty()) {
            retire(ledger, s);
        }
        ledger.setDirty();
    }

    /** At the end of the march home, once the village's anchor is loaded: the survivors reappear and take the return path. */
    private static void bringHome(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, List<RosterEntry> alive, long tick) {
        if (tick < s.phaseEnd) {
            return;
        }
        BlockPos anchor = GarrisonService.anchorOf(a);
        if (!overworld.isPositionEntityTicking(anchor)) {
            return; // home not loaded: they arrive when it is
        }
        BlockPos toward = ledger.get(s.target) != null ? ledger.get(s.target).center : a.center;
        int[] r = homecoming(overworld, ledger, a, alive, anchor, toward, tick);
        int pending = r[0], back = r[1];
        if (pending == 0) {
            retire(ledger, s);
            if (back > 0) {
                HmLog.info("Siege {}: {} survivor(s) back in {}", s.id.toString().substring(0, 8), back, a.name);
            }
        }
        ledger.setDirty();
    }

    /**
     * Brings stowed or loaded slots of a host home at {@code anchor} (which must be loaded): each reappears in spaced ranks and
     * takes the return path; engines go straight back into position; levies and mercenaries whose war is over are sent home.
     * Returns {pending (could not be placed yet), back}.
     */
    static int[] homecoming(ServerLevel overworld, GarrisonLedger ledger, VillageRecord a, List<RosterEntry> alive, BlockPos anchor, BlockPos toward,
                            long tick) {
        int pending = 0, back = 0, slot = 0;
        for (RosterEntry e : alive) {
            if (a.hywRoster.isArsenal(e) && !a.hywRoster.arsenalWar) {
                a.hywRoster.disarm(e); // the war ended while they were away: they stand down without coming back into the world
                continue;
            }
            if (!e.mercLook.isEmpty() || (e.mobilized && !ArsenalService.atWar(ledger, a.villageId))) {
                MobilizationService.discharge(overworld, e, tick); // the war ended while they were away: they go straight home
                continue;
            }
            int index = slot++;
            if (e.entityUuid == null) {
                // spaced ranks at the anchor, not one block (a crowd on one block dies of entity cramming)
                Vec3 spot = GarrisonService.spotNear(overworld, formation(anchor, toward, index, 8, 3), e.rosterId);
                if (spot == null || !GarrisonService.materialize(overworld, a, e, spot, BlockPos.containing(spot), tick)) {
                    pending++;
                    continue;
                }
            }
            if (e.duty == Duty.SIEGE && a.hywRoster.isArsenal(e)) {
                // war engines are not moved by the garrison's return path: back in position at once
                if (e.state() == UnitState.DEPLOYED) {
                    e.transition(UnitState.RETURNING, tick);
                    e.transition(UnitState.GARRISONED, tick);
                }
                e.duty = Duty.GARRISON;
                back++;
            } else if (e.duty == Duty.SIEGE) {
                if (e.state() == UnitState.DEPLOYED) {
                    e.transition(UnitState.RETURNING, tick);
                    e.duty = Duty.RETURNING;
                } else {
                    e.duty = e.assignedDuty;
                }
                back++;
            }
        }
        return new int[]{pending, back};
    }

    /**
     * Repair (post-M5): slots still on a siege (duty SIEGE) that no siege record holds any more (e.g. a record lost to a mod
     * update) come home when their village is loaded, as if their siege had ended. Returns the number brought home.
     */
    static int returnOrphans(ServerLevel overworld, GarrisonLedger ledger, long tick) {
        return returnOrphans(overworld, ledger, tick, false);
    }

    /** {@code now}: villages that are not loaded take their soldiers back at once too (stowed until they load). */
    static int returnOrphans(ServerLevel overworld, GarrisonLedger ledger, long tick, boolean now) {
        Set<UUID> held = new HashSet<>();
        for (Siege s : ledger.sieges()) {
            held.addAll(s.host);
            for (dev.hywmill.politics.war.Relief r : s.reliefs) {
                held.addAll(r.units); // relief forces are on duty SIEGE too: they have their own way home
                held.addAll(r.strays);
            }
        }
        Set<UUID> extras = new HashSet<>();
        for (Siege s : ledger.sieges()) {
            extras.addAll(s.extras);
        }
        int n = 0;
        for (VillageRecord rec : ledger.all()) {
            if (rec.hywRoster == null) {
                continue;
            }
            for (RosterEntry e : rec.hywRoster.entries()) {
                if (!e.extra.isEmpty() && !e.state().terminal() && !extras.contains(e.rosterId)) {
                    MobilizationService.discharge(overworld, e, tick); // a temporary defender whose siege is gone goes home
                    ledger.setDirty();
                }
            }
            List<RosterEntry> lost = new ArrayList<>();
            for (RosterEntry e : rec.hywRoster.entries()) {
                if (e.duty == Duty.SIEGE && !e.state().terminal() && !held.contains(e.rosterId)) {
                    lost.add(e);
                }
            }
            BlockPos anchor = GarrisonService.anchorOf(rec);
            if (lost.isEmpty()) {
                continue;
            }
            if (!overworld.isPositionEntityTicking(anchor)) {
                if (now) {
                    lost.forEach(e -> stowHome(overworld, ledger, rec, e, tick));
                    n += lost.size();
                    ledger.setDirty();
                    HmLog.info("Siege repair: {} soldier(s) of {} with no siege record go home (they reappear when it is loaded)", lost.size(), rec.name);
                }
                continue;
            }
            for (RosterEntry e : lost) {
                if (e.entityUuid != null && GarrisonService.find(overworld.getServer(), e.entityUuid) == null) {
                    e.entityUuid = null; // its entity is gone (a stale reference): it comes back as its next generation
                }
            }
            int[] r = homecoming(overworld, ledger, rec, lost, anchor, rec.center, tick);
            n += r[1];
            ledger.setDirty();
            HmLog.info("Siege repair: {} soldier(s) of {} with no siege record came home ({} still to place)", r[1], rec.name, r[0]);
        }
        return n;
    }

    /**
     * Admin (post-M5): wipes every siege. Nothing is decided (no outcome, no tribute, no losses): every host and relief force
     * comes home at once, appearing at its village if that is loaded, else put back on its village's roster to reappear
     * there when it next loads; the besieged's temporary help goes home; every siege record is removed. Soldiers with no
     * siege record come home too. Returns one line per siege.
     */
    public List<String> recallAll(ServerLevel overworld, GarrisonLedger ledger, long tick) {
        return recallAll(overworld, ledger, tick, false);
    }

    /** {@code asUnloaded} (DEV): every village is treated as unloaded, so every host is put back on its roster stowed. */
    public List<String> recallAll(ServerLevel overworld, GarrisonLedger ledger, long tick, boolean asUnloaded) {
        List<String> out = new ArrayList<>();
        for (Siege s : new ArrayList<>(ledger.sieges())) {
            VillageRecord a = ledger.get(s.attacker);
            VillageRecord t = ledger.get(s.target);
            String label = (a == null ? "?" : a.name) + " -> " + (t == null ? "?" : t.name);
            if (a != null && a.hywRoster != null) {
                if (s.phase != Siege.Phase.RETURN && s.outcome == Siege.Outcome.NONE) {
                    s.summary = "The host of " + a.name + " is called home" + (t != null ? " from " + t.name : "");
                    announce(overworld, ledger, s, a, t, s.summary);
                }
                for (RosterEntry e : entries(a, s.host)) {
                    if (!e.mercLook.isEmpty()) {
                        GarrisonService.stow(overworld, e);
                        MobilizationService.discharge(overworld, e, tick); // the hired company is paid off
                    }
                }
                label += ": " + bringBack(overworld, ledger, a, entries(a, s.host), t != null ? t.center : a.center, tick, asUnloaded);
            }
            for (dev.hywmill.politics.war.Relief rl : s.reliefs) {
                VillageRecord h = ledger.get(rl.helper);
                if (h != null && h.hywRoster != null) {
                    List<UUID> ids = new ArrayList<>(rl.units);
                    ids.addAll(rl.strays);
                    bringBack(overworld, ledger, h, entries(h, ids), t != null ? t.center : h.center, tick, asUnloaded);
                }
            }
            dismissExtras(overworld, ledger, s, tick);
            ledger.sieges().remove(s);
            out.add(label);
            HmLog.info("Siege {}: wiped by an admin ({})", s.id.toString().substring(0, 8), label);
        }
        int orphans = returnOrphans(overworld, ledger, tick, true);
        if (orphans > 0) {
            out.add(orphans + " soldier(s) with no siege record came home");
        }
        ledger.setDirty();
        return out;
    }

    /**
     * Brings slots of a host home at once: placed at the village if it is loaded, else (or where no spot is found) put back
     * on its roster at home, stowed, to reappear when it loads. Returns a short note.
     */
    static String bringBack(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, List<RosterEntry> list, BlockPos toward, long tick,
                            boolean asUnloaded) {
        if (list.isEmpty()) {
            return "home";
        }
        BlockPos anchor = GarrisonService.anchorOf(rec);
        int placed = 0;
        if (!asUnloaded && overworld.isPositionEntityTicking(anchor)) {
            placed = homecoming(overworld, ledger, rec, list, anchor, toward, tick)[1];
        }
        int stowed = 0;
        for (RosterEntry e : list) {
            if (e.state().terminal() || e.duty != Duty.SIEGE) {
                continue; // home already (placed, discharged or disarmed)
            }
            stowHome(overworld, ledger, rec, e, tick);
            stowed++;
        }
        ledger.setDirty();
        return stowed == 0 ? "home" : "home (" + stowed + " reappear when " + rec.name + " is next loaded)";
    }

    /** One slot of a host goes home without an entity: back to its duty at home, stowed until its village is loaded. */
    static void stowHome(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, RosterEntry e, long tick) {
        GarrisonService.stow(overworld, e);
        if (rec.hywRoster.isArsenal(e) && !rec.hywRoster.arsenalWar) {
            rec.hywRoster.disarm(e);
            return;
        }
        if (!e.mercLook.isEmpty() || !e.extra.isEmpty() || (e.mobilized && !ArsenalService.atWar(ledger, rec.villageId))) {
            MobilizationService.discharge(overworld, e, tick);
            return;
        }
        if (e.state() == UnitState.DEPLOYED) {
            e.transition(UnitState.RETURNING, tick);
        }
        if (e.state() == UnitState.RETURNING) {
            e.transition(UnitState.GARRISONED, tick);
        }
        e.duty = rec.hywRoster.isArsenal(e) ? Duty.GARRISON : e.assignedDuty;
    }

    // ------------------------------------------------------------------ village decisions

    private void villageDecisions(ServerLevel overworld, GarrisonLedger ledger, long tick) {
        for (WarRecord w : new ArrayList<>(ledger.wars().values())) {
            if (!w.atWar()) {
                continue;
            }
            for (UUID[] pair : new UUID[][]{{w.a, w.b}, {w.b, w.a}}) {
                VillageRecord a = ledger.get(pair[0]);
                VillageRecord t = ledger.get(pair[1]);
                if (a == null || t == null || a.hywRoster == null) {
                    continue;
                }
                PoliticsTables.SiegeRule r = rule(a);
                if (!r.enabled() || !r.aiEnabled() || a.politics.truceWith(t.villageId, tick) || t.politics.truceWith(a.villageId, tick)
                        || byAttacker(ledger, a.villageId) != null || against(ledger, t.villageId) != null
                        || (a.hywRoster.lastSiegeTick >= 0 && tick - a.hywRoster.lastSiegeTick < r.aiCooldown())) {
                    continue;
                }
                List<UUID> host = planHost(a);
                if (host.size() < r.minCommit()) {
                    continue;
                }
                double h = strength(entries(a, host)), d = defense(overworld, t);
                double p = SiegeMath.aiCheckChance(h, d, r) * (AI_INTERVAL / (double) r.aiInterval());
                if (p > 0 && new Random(a.villageId.getMostSignificantBits() ^ tick).nextDouble() < p) {
                    HmLog.info("Siege: {} decides to besiege {} (host strength {}, defense {})", a.name, t.name, fmt(h), fmt(d));
                    launch(overworld, a.villageId, t.villageId, null, tick, true);
                }
            }
        }
    }

    // ------------------------------------------------------------------ news

    public void onLogin(ServerPlayer player) {
        GarrisonLedger ledger = GarrisonLedger.get(player.getServer().overworld());
        SettlementSource source = Services.settlements();
        boolean any = false;
        for (var it = ledger.pendingPay().iterator(); it.hasNext(); ) {
            PoliticsNbt.PendingPay p = it.next();
            if (p.player().equals(player.getUUID())) {
                if (source != null && p.deniers() > 0) {
                    source.giveMoney(player, p.deniers());
                }
                player.sendSystemMessage(Component.literal("[Siege] While you were away: " + p.text()));
                it.remove();
                any = true;
            }
        }
        if (any) {
            ledger.setDirty();
        }
    }

    /** The host is home: the record goes, unless relief forces are still out (it stays until they are home too). */
    private static void retire(GarrisonLedger ledger, Siege s) {
        if (s.reliefsDone()) {
            ledger.sieges().remove(s);
        }
    }

    /** Tells the players near either village of {@code s} (relief news). */
    static void announceNear(ServerLevel overworld, GarrisonLedger ledger, Siege s, String text) {
        VillageRecord a = ledger.get(s.attacker);
        if (a != null) {
            announce(overworld, ledger, s, a, ledger.get(s.target), text);
        }
    }

    static void chronicle(ServerLevel overworld, VillageRecord a, @Nullable VillageRecord t, long tick, String text) {
        SettlementSource source = Services.settlements();
        PoliticsService.chronicle(overworld, source, a, tick, text);
        if (t != null) {
            PoliticsService.chronicle(overworld, source, t, tick, text);
        }
    }

    /** Tells players near either village, the counsel, the helpers and everyone on campaign with or against the attacker. */
    private static void announce(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, @Nullable VillageRecord t, String text) {
        Set<UUID> to = new LinkedHashSet<>();
        for (ServerPlayer p : overworld.players()) {
            Vec3 pos = p.position();
            if (pos.distanceToSqr(Vec3.atCenterOf(a.center)) < NEWS_RANGE * NEWS_RANGE
                    || (t != null && pos.distanceToSqr(Vec3.atCenterOf(t.center)) < NEWS_RANGE * NEWS_RANGE)) {
                to.add(p.getUUID());
            }
        }
        if (s.counsel != null) {
            to.add(s.counsel);
        }
        to.addAll(s.attackerHelpers);
        to.addAll(s.defenderHelpers);
        for (Campaign c : ledger.campaigns()) {
            if (s.involves(c.ally()) || s.involves(c.enemy())) {
                to.add(c.player());
            }
        }
        for (UUID id : to) {
            ServerPlayer p = PoliticsService.onlinePlayer(overworld, id);
            if (p != null) {
                p.sendSystemMessage(Component.literal("[Siege] " + text));
            }
        }
    }

    /** One line per siege (status command, Politics screen). */
    public static List<String> describe(ServerLevel overworld, GarrisonLedger ledger) {
        List<String> out = new ArrayList<>();
        long now = overworld.getGameTime();
        for (Siege s : ledger.sieges()) {
            VillageRecord a = ledger.get(s.attacker), t = ledger.get(s.target);
            int alive = a == null ? 0 : entries(a, s.host).size();
            out.add((a == null ? "?" : a.name) + " -> " + (t == null ? "?" : t.name) + " " + s.phase + " " + alive + "/" + s.hostStart
                    + (s.phaseEnd > now ? " next in " + (s.phaseEnd - now) / 20 + " s" : "") + (s.outcome != Siege.Outcome.NONE ? " " + s.outcome : "")
                    + " id " + s.id.toString().substring(0, 8) + ReliefService.describe(ledger, s));
        }
        return out;
    }

    private void count(String c) {
        dev.hywmill.core.HywMillRuntime rt = dev.hywmill.core.HywMillRuntime.get();
        if (rt != null) {
            rt.increment(c);
        }
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}

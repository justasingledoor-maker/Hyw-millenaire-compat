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
import dev.hywmill.politics.war.SiegeWaves;
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
    public static final String C_LAUNCHED = "siege.launched", C_WON = "siege.won", C_LOST = "siege.lost", C_OFFSCREEN = "siege.offscreen", C_STALEMATE = "siege.stalemate";

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
        return planHost(rec, true);
    }

    /**
     * {@code loaded}: only soldiers in the world (a launch's check); false at a prepared siege's muster, when the village may
     * not be loaded. Light horse stay home: they scout (post-M5).
     */
    public static List<UUID> planHost(VillageRecord rec, boolean loaded) {
        GarrisonRoster r = rec.hywRoster;
        if (r == null) {
            return List.of();
        }
        List<RaidPlanner.Candidate> cands = new ArrayList<>();
        for (RosterEntry e : r.entries()) {
            if ((e.state() == UnitState.GARRISONED || e.state() == UnitState.RECOVERED) && e.duty.standing() && (e.entityUuid != null || !loaded)
                    && !DutyMotion.scoutAway(e) && !ColumnService.LIGHT.contains(e.unitKey)) {
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
        return launch(overworld, attackerId, targetId, counsel, tick, requireWar, false);
    }

    /**
     * Post-M5: a siege is announced, then prepared for two days: the host musters on the third and arrives at dawn
     * ({@code quick}, admin: the old muster of a minute and march, no build-up). During the build-up mercenaries, vassals' men
     * and messengers take the road (see {@link ColumnService}). The host is chosen when it musters.
     */
    public Launch launch(ServerLevel overworld, UUID attackerId, UUID targetId, @Nullable UUID counsel, long tick, boolean requireWar, boolean quick) {
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
        long day = overworld.getDayTime();
        s.startDay = day;
        s.march = SiegeMath.marchTicks(Math.sqrt(a.center.distSqr(t.center)), r);
        s.quick = quick;
        a.hywRoster.lastSiegeTick = tick;
        ledger.sieges().add(s);
        String who = counsel != null ? " on " + PoliticsService.playerName(overworld, counsel) + "'s counsel" : "";
        String text;
        if (quick) {
            s.arriveAt = r.musterTicks() + s.march;
            int n = commitHost(overworld, ledger, s, a, tick);
            s.enter(Siege.Phase.MUSTER, tick, tick + r.musterTicks());
            ReliefService.plan(overworld, ledger, s, a, t, tick);
            text = a.name + " musters " + n + " soldiers to besiege " + t.name + who + "; they march in about " + r.musterTicks() / 1200 + " min";
        } else {
            // the host arrives at the dawn of the third day: two days of preparation, then the muster and the march by night
            long dawn = (Math.floorDiv(day, Tribute.DAY) + 3) * Tribute.DAY;
            s.arriveAt = dawn - day;
            s.enter(Siege.Phase.PREPARE, tick, tick + Math.max(0, s.arriveAt - s.march - r.musterTicks()));
            ReliefService.plan(overworld, ledger, s, a, t, tick);
            s.reliefs.forEach(rl -> rl.called = false); // they come only if the besieged's messenger reaches them
            text = a.name + " declares that it will besiege " + t.name + who + ": its host of about " + host.size()
                    + " will be before the walls at dawn on the third day";
        }
        ledger.setDirty();
        count(C_LAUNCHED);
        chronicle(overworld, a, t, tick, text);
        announce(overworld, ledger, s, a, t, text);
        horn(overworld, t.center);
        HmLog.info("Siege {} launched: {} -> {} ({}, arrives in {} ticks){}", s.id.toString().substring(0, 8), a.name, t.name, quick ? "quick" : "prepared",
                s.arriveAt, who);
        if (!quick) {
            ColumnService.announced(overworld, ledger, s, a, t, tick);
        }
        return new Launch(SiegeMath.Refusal.OK, s, text);
    }

    /**
     * The host is chosen and musters (at the launch of a quick siege, else at the end of its build-up): soldiers it can spare,
     * its war engines, and the men who reached it before (mercenaries, vassals' men). Returns the soldiers committed.
     */
    int commitHost(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, long tick) {
        List<UUID> host = planHost(a, s.quick);
        s.host.addAll(host);
        s.hostStart = host.size();
        for (RosterEntry e : a.hywRoster.arsenal()) {
            // the village's war engines and their crews march with the host
            if (e.state() == UnitState.GARRISONED && e.duty != Duty.SIEGE) {
                s.host.add(e.rosterId);
            }
        }
        UnitProvider units = Services.units();
        BlockPos muster = GarrisonService.anchorOf(a);
        for (UUID rid : new ArrayList<>(s.host)) {
            RosterEntry e = a.hywRoster.entry(rid);
            e.transition(UnitState.DEPLOYED, tick);
            e.duty = Duty.SIEGE;
            Entity ent = e.entityUuid != null ? GarrisonService.find(overworld.getServer(), e.entityUuid) : null;
            if (ent != null && units != null) {
                units.disengage(ent);
                units.setHome(ent, muster);
            }
        }
        joinPending(ledger, s, a, tick);
        ledger.setDirty();
        return s.hostStart;
    }

    /** Men who reached the attacker before its muster (mercenaries, vassals' men) join its host, stowed with it. */
    static void joinPending(GarrisonLedger ledger, Siege s, VillageRecord a, long tick) {
        GarrisonTables tables = GarrisonTables.current();
        var table = tables.forCulture(a.culture);
        PoliticsTables.MobilizationRule mob = MobilizationService.rule(a);
        int regular = dev.hywmill.garrison.Recruitment.equipmentLevel(a.tier, table);
        int levy = dev.hywmill.garrison.Mobilization.equipmentLevel(regular, mob.equipmentFloor(), mob.equipmentDrop());
        for (int i = 0; i < s.pendingUnits.size(); i++) {
            UnitSpec u = tables.units().get(s.pendingUnits.get(i));
            if (u == null || !u.enabled()) {
                continue;
            }
            boolean reg = i < s.pendingRegular.size() && s.pendingRegular.get(i);
            String look = i < s.pendingLook.size() ? s.pendingLook.get(i) : "";
            String kind = i < s.pendingKind.size() ? s.pendingKind.get(i) : "merc";
            RosterEntry e = a.hywRoster.recruit(a.villageId, u.key(), u.entityType(), reg ? regular : levy, tick, false);
            e.mobilized = !reg;
            if (kind.equals("merc")) {
                e.mercLook = look;
            } else {
                e.extra = kind;
            }
            e.transition(UnitState.SPAWNED, tick);
            e.transition(UnitState.GARRISONED, tick);
            e.transition(UnitState.DEPLOYED, tick);
            e.duty = Duty.SIEGE;
            s.host.add(e.rosterId);
            s.hostStart++;
        }
        s.pendingUnits.clear();
        s.pendingRegular.clear();
        s.pendingLook.clear();
        s.pendingKind.clear();
        ledger.setDirty();
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
        endVassalages(overworld, ledger, tick);
        try {
            ColumnService.tick(overworld, ledger, tick); // post-M5: columns on the road, scouts
        } catch (RuntimeException ex) {
            HmLog.warn("Columns failed: {}", ex.toString());
        }
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
        if (soldiers(a, alive).isEmpty() && s.phase != Siege.Phase.RETURN && s.phase != Siege.Phase.PREPARE) {
            finish(overworld, ledger, s, a, t, Siege.Outcome.LOST, "the whole host fell", tick, false);
            return;
        }
        if (t == null && s.outcome == Siege.Outcome.NONE) {
            s.summary = "the target is gone";
            goHome(overworld, ledger, s, a, alive, tick, r, false);
            return;
        }
        long el = s.elapsed(tick, overworld.getDayTime());
        if (s.march <= 0) {
            // a siege saved before the build-up existed: its old timing, as a quick siege
            s.march = SiegeMath.marchTicks(Math.sqrt(a.center.distSqr(t.center)), r);
            s.arriveAt = (s.phase == Siege.Phase.MUSTER ? r.musterTicks() : 0) + s.march;
            s.startDay = overworld.getDayTime() - (tick - s.launched);
            s.quick = true;
            el = s.elapsed(tick, overworld.getDayTime());
        }
        switch (s.phase) {
            case PREPARE -> {
                long musterAt = s.arriveAt - s.march - r.musterTicks();
                s.phaseEnd = tick + Math.max(0, musterAt - el);
                if (el >= musterAt) {
                    int n = commitHost(overworld, ledger, s, a, tick);
                    if (soldiers(a, entries(a, s.host)).size() < Math.max(1, r.minCommit() / 2)) {
                        s.summary = a.name + " could not raise a host to march on " + t.name;
                        announce(overworld, ledger, s, a, t, s.summary);
                        chronicle(overworld, a, t, tick, s.summary);
                        goHome(overworld, ledger, s, a, entries(a, s.host), tick, r, false);
                        return;
                    }
                    s.enter(Siege.Phase.MUSTER, tick, tick + Math.max(0, s.arriveAt - s.march - el));
                    ledger.setDirty();
                    horn(overworld, a.center);
                    announce(overworld, ledger, s, a, t, "The host of " + a.name + " (" + n + " soldiers) musters to march on " + t.name + " by night");
                    HmLog.info("Siege {}: the host musters ({} soldiers)", s.id.toString().substring(0, 8), n);
                }
            }
            case MUSTER -> {
                if (el >= s.arriveAt - s.march) {
                    alive = entries(a, s.host);
                    alive.forEach(e -> GarrisonService.stow(overworld, e));
                    long march = Math.max(0, s.arriveAt - el);
                    s.enter(Siege.Phase.MARCH, tick, tick + march);
                    if (!s.quick) {
                        ColumnService.marching(overworld, ledger, s, a, t, tick);
                    }
                    ledger.setDirty();
                    horn(overworld, a.center);
                    announce(overworld, ledger, s, a, t, "The host of " + a.name + " (" + alive.size() + " soldiers) marches on " + t.name
                            + "; it arrives in about " + Math.max(1, march / 1200) + " min");
                    HmLog.info("Siege {}: {} unit(s) stowed, marching {} ticks", s.id.toString().substring(0, 8), alive.size(), march);
                }
            }
            case MARCH -> {
                s.phaseEnd = tick + Math.max(0, s.arriveAt - el);
                boolean soon = el >= s.arriveAt - dev.hywmill.politics.war.Mercenaries.LEAD;
                // a quick siege rolls its mercenaries and vassals here; a prepared one sent them by road during its build-up
                if (s.quick && !s.mercRolled && soon) {
                    hireMercs(overworld, ledger, s, a, t, tick, false);
                }
                if (!s.aidRolled && soon) {
                    defenderAid(overworld, ledger, s, a, t, tick, false);
                }
                if (s.quick && !s.vassalRolled && soon) {
                    vassalHelp(overworld, ledger, s, a, t, tick, false);
                }
                if (el >= s.arriveAt) {
                    startWave(overworld, ledger, s, a, t, tick, r); // dawn before the walls: the first wave
                }
            }
            case WAIT -> startWave(overworld, ledger, s, a, t, tick, r); // a siege saved before waves existed
            case BATTLE -> battle(overworld, ledger, s, a, t, alive, tick, r);
            case NIGHT -> night(overworld, ledger, s, a, t, tick, r);
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

    /** The host (its soldiers fit to fight) comes into the world in groups round the village. Returns how many soldiers stand. */
    private int deploy(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, List<RosterEntry> alive,
                       BlockPos landing, long tick, PoliticsTables.SiegeRule r) {
        alive = alive.stream().filter(e -> !e.wounded && !e.state().terminal()).toList();
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
            return 0; // nobody could be placed on dry ground yet: the next step tries again
        }
        GarrisonService.respawnStowed(overworld, t, t.hywRoster, tick, 64); // the besieged who were carried in at sundown man the walls again
        musterExtras(overworld, s, t, tick); // the besieged's temporary help stands with them before the fight
        List<LivingEntity> defs = new ArrayList<>(defenders(overworld, t));
        defs.addAll(ReliefService.entities(overworld, ledger, s));
        stance(defs, true);
        ledger.setDirty();
        HmLog.info("Siege {}: wave {}: {} unit(s) materialized in {} group(s) round {} (main at {}); {} defender(s) in the world",
                s.id.toString().substring(0, 8), s.wave, n, groups.length, t.name, landing.toShortString(), defs.size());
        return n;
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
        s.notes.add(h.company().name() + " hired by " + a.name + " (" + n + ")");
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
                s.notes.add(n + " of " + t.name + "'s people took up arms");
                aidNews(overworld, ledger, s, a, t, tick, "The bells of " + t.name + " ring: " + n + " of its people take up arms against the host of " + a.name);
            }
        }
        if (aid.mercs() != null && (s.quick || force)) { // a prepared siege's mercenaries come by road (ColumnService)
            int n = raiseExtras(t, s, aid.mercs().units(), "merc", aid.mercs().company().look(), true, levyLevel, tick);
            if (n > 0) {
                raised += n;
                s.notes.add(aid.mercs().company().name() + " hired by " + t.name + " (" + n + ")");
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
                    s.notes.add("the lord of " + t.name + "'s household, " + n + " of " + guard.name());
                    aidNews(overworld, ledger, s, a, t, tick, "The lord of " + t.name + " is at home: his household, " + n + " of " + guard.name()
                            + ", stands with the defenders");
                }
            }
        }
        if (raised > 0) {
            horn(overworld, t.center);
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
    static int raiseExtras(VillageRecord t, Siege s, List<String> units, String kind, String look, boolean mobilized, int level, long tick) {
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

    // ------------------------------------------------------------------ vassals, reports, horns (post-M5)

    /** A battle report for the History tab (newest last; the oldest beyond BattleReport.KEEP go). */
    private static void report(GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, Siege.Outcome o, boolean watched, long tick,
                               String tribute) {
        int hostLost = Math.max(0, s.hostStart - soldiers(a, entries(a, s.host)).size());
        dev.hywmill.politics.war.BattleReport r = new dev.hywmill.politics.war.BattleReport(tick, a.villageId, t.villageId, a.name, t.name, o.name(),
                s.hostStart, hostLost, s.defendersStart > 0 ? s.defendersStart : -1, s.defLost, watched);
        r.notes.addAll(s.notes);
        if (s.wave > 0) {
            r.notes.add("over " + s.wave + " day" + (s.wave == 1 ? "" : "s") + ": attackers " + s.hostDead + " dead, " + s.hostHurt
                    + " wounded who fought again; defenders " + s.defDead + " dead, " + s.defHurt + " wounded who fought again");
        }
        for (dev.hywmill.politics.war.Relief rl : s.reliefs) {
            VillageRecord h = ledger.get(rl.helper);
            if (rl.sent > 0) {
                r.notes.add("relief from " + (h == null ? "?" : h.name) + ": " + rl.sent + " sent, " + rl.killed + " fell"
                        + (rl.fate == dev.hywmill.politics.war.Relief.Fate.CLEAN || rl.fate == dev.hywmill.politics.war.Relief.Fate.NONE ? ""
                        : " (" + rl.fate.name().toLowerCase() + " on the way)"));
            }
        }
        r.tribute = tribute;
        ledger.battles().add(r);
        while (ledger.battles().size() > dev.hywmill.politics.war.BattleReport.KEEP) {
            ledger.battles().remove(0);
        }
        ledger.setDirty();
    }

    /** The loser of a siege swears fealty to the winner for 21 days: allies for that time (a vassalage it had ends). */
    public static void swearFealty(ServerLevel overworld, GarrisonLedger ledger, VillageRecord vassal, VillageRecord overlord, long tick) {
        ledger.vassalages().removeIf(v -> v.vassal.equals(vassal.villageId) || (v.vassal.equals(overlord.villageId) && v.overlord.equals(vassal.villageId)));
        ledger.vassalages().add(dev.hywmill.politics.war.Vassalage.sworn(vassal.villageId, overlord.villageId, tick));
        SettlementSource source = Services.settlements();
        if (source != null) {
            source.setVillageRelation(overworld, vassal.villageId, overlord.villageId, dev.hywmill.politics.war.Vassalage.ALLIED);
        }
        ledger.setDirty();
        String text = vassal.name + " swears fealty to " + overlord.name + " for " + dev.hywmill.politics.war.Vassalage.DAYS
                + " days: they are allies, and " + vassal.name + " owes its overlord men in war";
        chronicle(overworld, vassal, overlord, tick, text);
        HmLog.info("Vassalage: {}", text);
    }

    /** Vassalages whose 21 days are over end: the two villages are neutral again. */
    static void endVassalages(ServerLevel overworld, GarrisonLedger ledger, long tick) {
        for (dev.hywmill.politics.war.Vassalage v : new ArrayList<>(ledger.vassalages())) {
            if (!v.over(tick)) {
                continue;
            }
            ledger.vassalages().remove(v);
            ledger.setDirty();
            VillageRecord vr = ledger.get(v.vassal), or = ledger.get(v.overlord);
            if (vr == null || or == null) {
                continue;
            }
            SettlementSource source = Services.settlements();
            if (source != null) {
                source.setVillageRelation(overworld, v.vassal, v.overlord, dev.hywmill.politics.war.Vassalage.NEUTRAL);
            }
            String text = vr.name + "'s fealty to " + or.name + " is over: they are neutral again";
            chronicle(overworld, vr, or, tick, text);
            HmLog.info("Vassalage: {}", text);
        }
    }

    /**
     * With the mercenaries, a minute before the assault: each vassal of the attacker (not the target itself) and of the target
     * (not the attacker) sends, three times in four, 10-15 soldiers of mixed quality ({@code force}: always): into the host, or
     * to the defence. They are temporary and leave when the siege ends. Returns how many came.
     */
    public int vassalHelp(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick, boolean force) {
        s.vassalRolled = true;
        ledger.setDirty();
        int came = 0;
        for (dev.hywmill.politics.war.Vassalage v : new ArrayList<>(ledger.vassalages())) {
            boolean forAttacker = v.overlord.equals(a.villageId) && !v.vassal.equals(t.villageId);
            boolean forTarget = v.overlord.equals(t.villageId) && !v.vassal.equals(a.villageId);
            VillageRecord vr = ledger.get(v.vassal);
            if ((!forAttacker && !forTarget) || vr == null || v.over(tick)) {
                continue;
            }
            VillageRecord lord = forAttacker ? a : t;
            if (lord.hywRoster == null) {
                continue;
            }
            List<Boolean> men = dev.hywmill.politics.war.Vassalage.levy(s.seed(v.vassal.getMostSignificantBits() ^ tick));
            if (men.isEmpty() && force) {
                men = dev.hywmill.politics.war.Vassalage.levy(s.seed(v.vassal.getMostSignificantBits() ^ tick) ^ 0x1L);
                for (long k = 2; men.isEmpty(); k++) {
                    men = dev.hywmill.politics.war.Vassalage.levy(s.seed(k));
                }
            }
            if (men.isEmpty()) {
                String text = vr.name + ", vassal of " + lord.name + ", sends no one";
                s.notes.add(text);
                aidNews(overworld, ledger, s, a, t, tick, text);
                continue;
            }
            int n = raiseVassalMen(overworld, ledger, s, lord, vr, forAttacker, men, tick);
            if (n == 0) {
                continue;
            }
            came += n;
            String text = vr.name + ", vassal of " + lord.name + ", sends " + n + " men " + (forAttacker ? "to its overlord's host" : "to defend its overlord");
            s.notes.add(vr.name + " (vassal of " + lord.name + ") sent " + n);
            aidNews(overworld, ledger, s, a, t, tick, text);
            horn(overworld, forAttacker ? t.center : t.center);
        }
        if (came > 0 && overworld.isPositionEntityTicking(GarrisonService.anchorOf(t))) {
            musterExtras(overworld, s, t, tick);
        }
        return came;
    }

    /**
     * A vassal's men, as temporary slots of its overlord's roster (they fight under its banner): its levies (levy kit and
     * level) and some regulars (the vassal's own equipment level), drawn from the vassal's units. Into the host (stowed, marching
     * with it) or among the target's temporary defenders.
     */
    private static int raiseVassalMen(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord lord, VillageRecord vassal, boolean intoHost,
                                      List<Boolean> regulars, long tick) {
        GarrisonTables tables = GarrisonTables.current();
        var table = tables.forCulture(vassal.culture);
        List<String> pool = new ArrayList<>();
        dev.hywmill.garrison.Recruitment.eligibleUnits(vassal.tier, table, tables.units()).forEach(u -> pool.add(u.key()));
        PoliticsTables.MobilizationRule mob = MobilizationService.rule(vassal);
        mob.levyUnits().keySet().forEach(pool::add);
        if (pool.isEmpty()) {
            return 0;
        }
        int regular = dev.hywmill.garrison.Recruitment.equipmentLevel(vassal.tier, table);
        int levy = dev.hywmill.garrison.Mobilization.equipmentLevel(regular, mob.equipmentFloor(), mob.equipmentDrop());
        List<String> units = dev.hywmill.politics.war.DefenderAid.draw(pool, regulars.size(), s.seed(vassal.villageId.getLeastSignificantBits()));
        int n = 0;
        for (int i = 0; i < units.size(); i++) {
            UnitSpec u = tables.units().get(units.get(i));
            if (u == null || !u.enabled()) {
                continue;
            }
            boolean reg = regulars.get(i);
            RosterEntry e = lord.hywRoster.recruit(lord.villageId, u.key(), u.entityType(), reg ? regular : levy, tick, false);
            e.mobilized = !reg;
            e.extra = "vassal";
            e.transition(UnitState.SPAWNED, tick);
            e.transition(UnitState.GARRISONED, tick);
            if (intoHost) {
                e.transition(UnitState.DEPLOYED, tick);
                e.duty = Duty.SIEGE;
                s.host.add(e.rosterId);
                s.hostStart++;
            } else {
                s.extras.add(e.rosterId);
            }
            n++;
        }
        ledger.setDirty();
        return n;
    }

    /** A war horn (the raid horn) for players within NEWS_RANGE of {@code at}, from its direction (heard far, like a raid's). */
    static void horn(ServerLevel overworld, BlockPos at) {
        for (ServerPlayer p : overworld.players()) {
            Vec3 me = p.position();
            Vec3 src = Vec3.atCenterOf(at);
            double d = me.distanceTo(src);
            if (d > NEWS_RANGE) {
                continue;
            }
            Vec3 dir = d < 1 ? Vec3.ZERO : src.subtract(me).normalize();
            Vec3 pos = me.add(dir.scale(Math.min(d, 13)));
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.sounds.SoundEvents.RAID_HORN,
                    net.minecraft.sounds.SoundSource.NEUTRAL, pos.x, pos.y, pos.z, 64f, 1f, overworld.getRandom().nextLong()));
        }
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
            if (!s.fighting() || s.outcome != Siege.Outcome.NONE || a == null || t == null || a.hywRoster == null) {
                continue;
            }
            live.add(s.id);
            net.minecraft.server.level.ServerBossEvent bar = bars.computeIfAbsent(s.id, id -> {
                HmLog.info("Siege {}: boss bar raised over {} ({})", id.toString().substring(0, 8), t.name, barColour(overworld, a));
                return new net.minecraft.server.level.ServerBossEvent(Component.empty(), barColour(overworld, a),
                        net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_10);
            });
            int host = soldiers(a, entries(a, s.host)).size();
            int def = defenderCount(overworld, ledger, s, t, s.field && overworld.isPositionEntityTicking(t.center));
            bar.setName(Component.literal("Siege of " + t.name + ", day " + s.wave + "/" + SiegeWaves.WAVES + (s.phase == Siege.Phase.NIGHT ? " (night)" : "")
                    + ": " + a.name + " " + host + "/" + s.hostStart + " vs " + t.name + " " + def + "/" + s.defendersStart));
            bar.setProgress(s.defendersStart <= 0 ? 0f : Math.max(0f, Math.min(1f, def / (float) s.defendersStart)));
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

    /**
     * A wave (post-M5). In the world when a player is near (the host stands round the village and fights), else on paper (its
     * toll is drawn at sundown). The side down to a fifth of its strength (alive or wounded) loses at once; at sundown the host
     * withdraws for the night, and after the third wave a siege neither side lost is a stalemate.
     */
    private void battle(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, List<RosterEntry> alive, long tick,
                        PoliticsTables.SiegeRule r) {
        long since = tick - s.phaseSince;
        boolean loaded = !s.forceUnwatched && overworld.isPositionEntityTicking(t.center);
        if (!s.field && loaded && since < SiegeWaves.WAVE_MAX - 1200) {
            BlockPos landing = landing(overworld, a, t);
            if (watched(overworld, t, landing) && deploy(overworld, ledger, s, a, t, alive, landing, tick, r) > 0) {
                s.field = true; // a witness came: from now on this wave is fought in the world
                horn(overworld, t.center);
                announce(overworld, ledger, s, a, t, "The host of " + a.name + " closes on " + t.name + " from every side ("
                        + SiegeWaves.ordinal(s.wave) + " day of the siege)");
                ledger.setDirty();
            }
        }
        if (s.field && loaded) {
            fight(overworld, ledger, s, a, t, alive, tick, r);
        }
        int host = soldiers(a, entries(a, s.host)).size();
        int def = defenderCount(overworld, ledger, s, t, s.field && loaded);
        Siege.Outcome o = SiegeWaves.verdict(host, s.hostStart, def, s.defendersStart, s.wave, false);
        if (o == Siege.Outcome.NONE && SiegeWaves.waveOver(since, overworld.getDayTime())) {
            if (!s.field) {
                paperWave(overworld, ledger, s, a, t, tick, r);
                host = soldiers(a, entries(a, s.host)).size();
                def = defenderCount(overworld, ledger, s, t, false);
            }
            o = SiegeWaves.verdict(host, s.hostStart, def, s.defendersStart, s.wave, true);
            if (o == Siege.Outcome.NONE) {
                sundown(overworld, ledger, s, a, t, tick, host, def);
                return;
            }
        }
        if (o != Siege.Outcome.NONE) {
            s.defLost = Math.max(0, s.defendersStart - def);
            String how = switch (o) {
                case WON -> "on the " + SiegeWaves.ordinal(s.wave) + " day the defenders broke: " + def + " of " + s.defendersStart + " left, wounded included";
                case LOST -> "on the " + SiegeWaves.ordinal(s.wave) + " day the host broke: " + host + " of " + s.hostStart + " left, wounded included";
                default -> "after three days " + host + " of " + s.hostStart + " attackers and " + def + " of " + s.defendersStart
                        + " defenders are left, wounded included";
            };
            s.notes.add(dayNote(s));
            finish(overworld, ledger, s, a, t, o, how, tick, s.field);
        }
    }

    /** The host in the world seeks out the defenders; players near the battle who fight for a side become its helpers. */
    private void fight(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, List<RosterEntry> alive, long tick,
                       PoliticsTables.SiegeRule r) {
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
                    int[] o = SiegeMath.sweepOffset(e.rosterId, t.villageRadius, tick - s.phaseSince);
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
    }

    // ------------------------------------------------------------------ waves (post-M5)

    /** Dawn: a wave begins (the host before the walls, or back after the night); in the world if a player is near. */
    private void startWave(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick,
                           PoliticsTables.SiegeRule r) {
        s.wave++;
        s.hostDeadW = s.hostHurtW = s.defDeadW = s.defHurtW = 0;
        s.field = false;
        s.enter(Siege.Phase.BATTLE, tick, tick + SiegeWaves.WAVE_MAX);
        if (s.wave == 1) {
            // the strength each side brings to the walls: what the 80% rule counts against
            s.milStart = millenaireFighters(overworld, t);
            s.hostStart = soldiers(a, entries(a, s.host)).size();
            s.defendersStart = defenderCount(overworld, ledger, s, t, false);
        }
        int host = soldiers(a, entries(a, s.host)).size(), def = defenderCount(overworld, ledger, s, t, false);
        horn(overworld, t.center);
        String text = s.wave == 1
                ? "Dawn before " + t.name + ": the host of " + a.name + " (" + host + ") stands before the walls; " + def
                + " defenders man them. The first assault begins"
                : "Dawn of the " + SiegeWaves.ordinal(s.wave) + " day before " + t.name + ": the host of " + a.name + " forms up again (" + host
                + " of " + s.hostStart + "), " + def + " of " + s.defendersStart + " defenders hold the walls"
                + (s.wave == SiegeWaves.WAVES ? ". This is the last assault" : "");
        announce(overworld, ledger, s, a, t, text);
        chronicle(overworld, a, t, tick, text);
        HmLog.info("Siege {}: wave {} begins: host {}/{}, defenders {}/{}", s.id.toString().substring(0, 8), s.wave, host, s.hostStart, def,
                s.defendersStart);
        ledger.setDirty();
        BlockPos landing = landing(overworld, a, t);
        if (!s.forceUnwatched && watched(overworld, t, landing) && deploy(overworld, ledger, s, a, t, entries(a, s.host), landing, tick, r) > 0) {
            s.field = true;
        }
    }

    /** Sundown with neither side beaten: the host withdraws to its lines for the night; the defenders stand down. */
    private void sundown(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick, int host, int def) {
        for (RosterEntry e : entries(a, s.host)) {
            if (e.entityUuid != null) {
                GarrisonService.stow(overworld, e); // back to the camp lines, out of the world until dawn
            }
        }
        if (overworld.isPositionEntityTicking(t.center)) {
            List<LivingEntity> defs = new ArrayList<>(defenders(overworld, t));
            defs.addAll(ReliefService.entities(overworld, ledger, s));
            stance(defs, false);
        }
        String note = dayNote(s);
        s.notes.add(note);
        s.enter(Siege.Phase.NIGHT, tick, tick + SiegeWaves.NIGHT_MAX);
        ledger.setDirty();
        String text = "Sundown on the " + SiegeWaves.ordinal(s.wave) + " day before " + t.name + ": the host of " + a.name
                + " withdraws to its lines. " + note.substring(note.indexOf(':') + 2) + ". " + host + " attackers and " + def
                + " defenders can still fight, wounded included; " + (SiegeWaves.WAVES - s.wave) + " day" + (SiegeWaves.WAVES - s.wave == 1 ? "" : "s")
                + " of assault left";
        announce(overworld, ledger, s, a, t, text);
        HmLog.info("Siege {}: sundown after wave {} ({}): host {}/{}, defenders {}/{}", s.id.toString().substring(0, 8), s.wave, s.field ? "field" : "paper",
                host, s.hostStart, def, s.defendersStart);
    }

    /** The night: the wounded are tended (some die of their wounds; engines are repaired or found beyond repair); at dawn, the next wave. */
    private void night(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick, PoliticsTables.SiegeRule r) {
        if (!SiegeWaves.nightOver(tick - s.phaseSince, overworld.getDayTime())) {
            return;
        }
        int[] h = tend(overworld, a, entries(a, s.host), s.seed(0x6E6967L ^ s.wave), tick);
        List<RosterEntry> defenders = new ArrayList<>(homeDefenders(t));
        int[] d = tend(overworld, t, defenders, s.seed(0x6E6968L ^ s.wave), tick);
        for (dev.hywmill.politics.war.Relief rl : s.reliefs) {
            VillageRecord hv = ledger.get(rl.helper);
            if (hv != null && hv.hywRoster != null) {
                int[] x = tend(overworld, hv, ReliefService.present(ledger, s, rl.helper), s.seed(0x6E6969L ^ s.wave ^ rl.helper.getLeastSignificantBits()), tick);
                d[0] += x[0];
                d[1] += x[1];
                d[2] += x[2];
                rl.killed += x[0];
            }
        }
        s.hostDead += h[0];
        s.hostHurt -= h[0];
        s.defDead += d[0];
        s.defHurt -= d[0];
        if (h[0] + d[0] + h[1] + d[1] + h[2] > 0) {
            String text = "In the night " + (h[1] + d[1]) + " wounded rose to fight again"
                    + (h[0] + d[0] > 0 ? "; " + h[0] + " of " + a.name + "'s and " + d[0] + " of " + t.name + "'s wounded died of their wounds" : "")
                    + (h[2] > 0 ? "; " + h[2] + " of " + a.name + "'s engines were repaired" : "") + (h[3] > 0 ? ", " + h[3] + " are beyond repair" : "");
            s.notes.add("Night " + s.wave + ": " + text.substring("In the night ".length()));
            announce(overworld, ledger, s, a, t, text);
            HmLog.info("Siege {}: night {}: {}", s.id.toString().substring(0, 8), s.wave, text);
        }
        int host = soldiers(a, entries(a, s.host)).size(), def = defenderCount(overworld, ledger, s, t, false);
        Siege.Outcome o = SiegeWaves.verdict(host, s.hostStart, def, s.defendersStart, s.wave, false);
        if (o != Siege.Outcome.NONE) {
            s.defLost = Math.max(0, s.defendersStart - def);
            finish(overworld, ledger, s, a, t, o, o == Siege.Outcome.WON ? "the defenders' wounded could not hold the walls"
                    : "too many of the host died of their wounds to go on", tick, false);
            return;
        }
        startWave(overworld, ledger, s, a, t, tick, r);
    }

    /**
     * The wounded of one side are tended in the night: each dies of his wounds at {@link SiegeWaves#SUCCUMB}, else stands
     * again; a damaged engine is repaired, or found beyond repair at the same chance. Returns {died, recovered, engines
     * repaired, engines lost}.
     */
    private static int[] tend(ServerLevel overworld, VillageRecord v, List<RosterEntry> list, long seed, long tick) {
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(seed);
        int[] out = new int[4];
        for (RosterEntry e : list) {
            if (!e.wounded || e.state().terminal()) {
                continue;
            }
            boolean engine = v.hywRoster != null && v.hywRoster.isArsenal(e);
            if (rnd.nextDouble() < SiegeWaves.SUCCUMB) {
                kill(overworld, v, e.rosterId, tick);
                e.wounded = false;
                out[engine ? 3 : 0]++;
            } else {
                e.wounded = false;
                out[engine ? 2 : 1]++;
            }
        }
        return out;
    }

    /** A wave far from any witness, drawn at sundown by strength; the fallen are dead or wounded at each side's chance. */
    private void paperWave(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick, PoliticsTables.SiegeRule r) {
        List<RosterEntry> host = entries(a, s.host).stream().filter(e -> !e.wounded).toList();
        // the defenders: the target's own (garrison, temporary help) and the relief forces present, each with its village
        List<Object[]> defs = new ArrayList<>();
        for (RosterEntry e : homeDefenders(t)) {
            if (!e.wounded) {
                defs.add(new Object[]{t, e});
            }
        }
        double d = defense(overworld, t, engineCount(a, host));
        for (dev.hywmill.politics.war.Relief rl : s.reliefs) {
            VillageRecord hv = ledger.get(rl.helper);
            List<RosterEntry> present = ReliefService.present(ledger, s, rl.helper).stream().filter(e -> !e.wounded).toList();
            d += strength(present);
            if (hv != null) {
                present.forEach(e -> defs.add(new Object[]{hv, e}));
            }
        }
        double p = SiegeMath.winChance(hostStrength(a, host), d, r);
        SiegeWaves.Paper w = SiegeWaves.paper(host.size(), defs.size(), p, s.seed(0x7761L ^ s.wave));
        List<RosterEntry> hs = new ArrayList<>(host);
        hs.sort(java.util.Comparator.comparing(e -> e.rosterId));
        java.util.Collections.shuffle(hs, new java.util.Random(s.seed(0x7762L ^ s.wave)));
        for (int i = 0; i < w.host().fallen() && i < hs.size(); i++) {
            fall(overworld, a, hs.get(i), i < w.host().dead(), tick);
        }
        defs.sort(java.util.Comparator.comparing(x -> ((RosterEntry) x[1]).rosterId));
        java.util.Collections.shuffle(defs, new java.util.Random(s.seed(0x7763L ^ s.wave)));
        for (int i = 0; i < w.defenders().fallen() && i < defs.size(); i++) {
            VillageRecord v = (VillageRecord) defs.get(i)[0];
            fall(overworld, v, (RosterEntry) defs.get(i)[1], i < w.defenders().dead(), tick);
            if (v != t && i < w.defenders().dead()) {
                s.reliefs.stream().filter(rl -> rl.helper.equals(v.villageId)).forEach(rl -> rl.killed++);
            }
        }
        s.hostDeadW += w.host().dead();
        s.hostHurtW += w.host().wounded();
        s.defDeadW += w.defenders().dead();
        s.defHurtW += w.defenders().wounded();
        s.hostDead += w.host().dead();
        s.hostHurt += w.host().wounded();
        s.defDead += w.defenders().dead();
        s.defHurt += w.defenders().wounded();
        count(C_OFFSCREEN);
        ledger.setDirty();
        HmLog.info("Siege {}: wave {} decided on paper (P(attackers hold) {}): {}; host {} dead {} wounded, defenders {} dead {} wounded",
                s.id.toString().substring(0, 8), s.wave, fmt(p), w.attackersHeld() ? "the host held the field" : "the defenders held",
                w.host().dead(), w.host().wounded(), w.defenders().dead(), w.defenders().wounded());
    }

    /** One who fell in a wave decided on paper: dead, or wounded (out of the world until the next dawn). */
    private static void fall(ServerLevel overworld, VillageRecord v, RosterEntry e, boolean dead, long tick) {
        if (dead) {
            kill(overworld, v, e.rosterId, tick);
        } else {
            e.wounded = true;
            if (e.entityUuid != null) {
                GarrisonService.stow(overworld, e);
            }
        }
    }

    /**
     * Post-M5: one of a siege's soldiers falls in a wave (his death event). By chance (35% attackers, 45% defenders: they are
     * at home) he is only wounded: the death is cancelled and he is carried off the field (stowed) until the next dawn; an
     * engine is knocked out and may be repaired. Returns true if wounded (the caller cancels the death).
     */
    public static boolean onFall(LivingEntity entity, ServerLevel level) {
        dev.hywmill.garrison.tag.GarrisonTag tag = dev.hywmill.garrison.tag.GarrisonAttachments.get(entity);
        if (tag == null) {
            return false;
        }
        ServerLevel overworld = level.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord v = ledger.get(tag.villageId());
        RosterEntry e = v == null || v.hywRoster == null ? null : v.hywRoster.entry(tag.rosterId());
        if (e == null || e.state().terminal() || !entity.getUUID().equals(e.entityUuid)) {
            return false;
        }
        for (Siege s : ledger.sieges()) {
            if (s.phase != Siege.Phase.BATTLE || s.outcome != Siege.Outcome.NONE) {
                continue;
            }
            boolean attacker = v.villageId.equals(s.attacker) && s.host.contains(e.rosterId);
            boolean defender = (v.villageId.equals(s.target) && e.state().bound() && !e.duty.away())
                    || s.reliefs.stream().anyMatch(rl -> rl.helper.equals(v.villageId) && rl.units.contains(e.rosterId));
            if (!attacker && !defender) {
                continue;
            }
            long tick = overworld.getGameTime();
            double draw = new java.util.SplittableRandom(s.seed(e.rosterId.getLeastSignificantBits() ^ tick)).nextDouble();
            boolean wounded = SiegeWaves.wounded(defender, draw);
            if (attacker) {
                if (wounded) {
                    s.hostHurtW++;
                    s.hostHurt++;
                } else {
                    s.hostDeadW++;
                    s.hostDead++;
                }
            } else if (wounded) {
                s.defHurtW++;
                s.defHurt++;
            } else {
                s.defDeadW++;
                s.defDead++;
            }
            ledger.setDirty();
            if (!wounded) {
                return false; // he is dead: the garrison records it as ever
            }
            entity.setHealth(1f);
            e.wounded = true;
            GarrisonService.stow(overworld, e); // carried off the field: back at the next dawn
            HmLog.info("Siege {}: {} of {} {} (wave {})", s.id.toString().substring(0, 8), e.unitKey, v.name,
                    v.hywRoster.isArsenal(e) ? "knocked out (to be repaired)" : "wounded and carried off the field", s.wave);
            return true;
        }
        return false;
    }

    /**
     * The defenders' strength in men, wounded included: the target's own (garrison, temporary help), the relief forces at
     * the village, and its Millénaire fighters (counted in the world during a wave fought there; else as at the first dawn).
     */
    static int defenderCount(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord t, boolean live) {
        int n = homeDefenders(t).size();
        for (dev.hywmill.politics.war.Relief rl : s.reliefs) {
            n += ReliefService.present(ledger, s, rl.helper).size();
        }
        if (live) {
            n += Math.min(s.milStart, defenders(overworld, t).size() - (int) homeDefenders(t).stream().filter(e -> e.entityUuid != null).count());
        } else {
            n += s.milStart;
        }
        return n;
    }

    /** The target's Millénaire fighters (its residents who take up arms by its doctrine). */
    static int millenaireFighters(ServerLevel overworld, VillageRecord t) {
        SettlementSource source = Services.settlements();
        if (source == null) {
            return 0;
        }
        dev.hywmill.military.doctrine.MilitiaPolicy policy = dev.hywmill.settlement.GarrisonUpdater.resolveDoctrine(t).doctrine().militiaPolicy();
        dev.hywmill.military.classify.RoleTable roles = dev.hywmill.military.classify.RoleTables.current();
        int n = 0;
        for (SettlementSource.RosterEntry d : source.defenseRoster(overworld, t.villageId)) {
            if (dev.hywmill.politics.war.RoeState.combatant(dev.hywmill.military.classify.RoleClassifier.villager(d.facts(), roles), policy)) {
                n++;
            }
        }
        return n;
    }

    /** The siege is over (or wiped): every soldier of it still wounded is fit again (and may be placed in the world). */
    static void heal(GarrisonLedger ledger, Siege s) {
        List<VillageRecord> sides = new ArrayList<>();
        for (UUID id : List.of(s.attacker, s.target)) {
            if (ledger.get(id) != null) {
                sides.add(ledger.get(id));
            }
        }
        s.reliefs.forEach(rl -> {
            if (ledger.get(rl.helper) != null) {
                sides.add(ledger.get(rl.helper));
            }
        });
        for (VillageRecord v : sides) {
            if (v.hywRoster != null) {
                v.hywRoster.entries().forEach(e -> e.wounded = false);
            }
        }
        ledger.setDirty();
    }

    /** "Day 2: attackers 3 dead, 4 wounded; defenders 6 dead, 5 wounded". */
    static String dayNote(Siege s) {
        return "Day " + s.wave + ": attackers " + s.hostDeadW + " dead, " + s.hostHurtW + " wounded; defenders " + s.defDeadW + " dead, "
                + s.defHurtW + " wounded";
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
        heal(ledger, s); // post-M5: the siege is over; its wounded go home with the rest
        PoliticsTables.SiegeRule r = rule(a);
        if (t != null && watched) {
            List<LivingEntity> defs = new ArrayList<>(defenders(overworld, t));
            defs.addAll(ReliefService.entities(overworld, ledger, s));
            stance(defs, false); // the defenders stand down to their usual stance
        }
        count(o == Siege.Outcome.WON ? C_WON : o == Siege.Outcome.LOST ? C_LOST : C_STALEMATE);
        StringBuilder text = new StringBuilder();
        if (t != null && o == Siege.Outcome.STALEMATE) {
            // post-M5: three days and neither side broke: the attackers give up and go home; nothing is paid, nobody swears fealty
            text.append("After three days before ").append(t.name).append(" neither side broke: the host of ").append(a.name)
                    .append(" gives up the siege and goes home (").append(how).append("); no tribute is paid");
            chronicle(overworld, a, t, tick, text.toString());
            report(ledger, s, a, t, o, watched, tick, "none (stalemate)");
            horn(overworld, t.center);
        } else if (t != null) {
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
            report(ledger, s, a, t, o, watched, tick, dev.hywmill.recruit.RecruitOffers.money(tribute) + " a day for " + due.days + " days, " + loser.name
                    + " to " + winner.name);
            horn(overworld, t.center);
        } else {
            text.append("The host of ").append(a.name).append(" comes home: ").append(how);
        }
        s.summary = text.toString();
        announce(overworld, ledger, s, a, t, s.summary);
        HmLog.info("Siege {} ended {}: {}", s.id.toString().substring(0, 8), o, s.summary);
        goHome(overworld, ledger, s, a, entries(a, s.host), tick, r, watched);
        if (t != null && o != Siege.Outcome.STALEMATE && PoliticsService.tables(a).warCounsel().peaceAfterSiege()) {
            // the loser sues for peace: the war ends and the relation rises above open conflict
            dev.hywmill.politics.service.WarCounselService.makePeace(overworld, ledger, a, t, tick,
                    (o == Siege.Outcome.WON ? t.name : a.name) + " lost a siege and sued for peace");
        }
        if (t != null && o != Siege.Outcome.STALEMATE && !a.loneBuilding && !t.loneBuilding) {
            swearFealty(overworld, ledger, o == Siege.Outcome.WON ? t : a, o == Siege.Outcome.WON ? a : t, tick);
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
            if (e.temporary()) {
                MobilizationService.discharge(overworld, e, tick); // hired men and vassals' levies go their own way
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
            if (e.temporary() || (e.mobilized && !ArsenalService.atWar(ledger, a.villageId))) {
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
        ledger.scoutRides().forEach(sr -> held.add(sr.rider())); // scouts out on a ride
        Set<UUID> extras = new HashSet<>();
        for (Siege s : ledger.sieges()) {
            extras.addAll(s.extras);
            extras.addAll(s.host); // a vassal's levies in a host are temporary too
        }
        Set<UUID> fighting = new HashSet<>();
        for (Siege s : ledger.sieges()) {
            if (s.fighting()) {
                fighting.add(s.attacker);
                fighting.add(s.target);
                s.reliefs.forEach(rl -> fighting.add(rl.helper));
            }
        }
        int n = 0;
        for (VillageRecord rec : ledger.all()) {
            if (rec.hywRoster == null) {
                continue;
            }
            if (!fighting.contains(rec.villageId)) {
                for (RosterEntry e : rec.hywRoster.entries()) {
                    if (e.wounded) {
                        e.wounded = false; // repair: wounded with no siege being fought
                        ledger.setDirty();
                    }
                }
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
        ColumnService.dropSiegeColumns(overworld, ledger, tick);
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
                    if (e.temporary()) {
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
            heal(ledger, s);
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
    static void announce(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, @Nullable VillageRecord t, String text) {
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
            out.add((a == null ? "?" : a.name) + " -> " + (t == null ? "?" : t.name) + " " + s.phase + (s.wave > 0 ? " day " + s.wave + "/" + SiegeWaves.WAVES : "")
                    + " " + alive + "/" + s.hostStart
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

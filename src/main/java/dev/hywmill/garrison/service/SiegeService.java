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

    /** The siege whose host belongs to {@code attacker} (any phase, including the march home). */
    @Nullable
    public static Siege byAttacker(GarrisonLedger ledger, UUID attacker) {
        for (Siege s : ledger.sieges()) {
            if (s.attacker.equals(attacker)) {
                return s;
            }
        }
        return null;
    }

    /** The siege against {@code target} that has not been decided yet. */
    @Nullable
    public static Siege against(GarrisonLedger ledger, UUID target) {
        for (Siege s : ledger.sieges()) {
            if (s.target.equals(target) && s.outcome == Siege.Outcome.NONE) {
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
        BlockPos p = behind(t.center, a.center, radius + STAGING_MARGIN);
        if (p.equals(t.center)) {
            p = t.center.offset(radius + STAGING_MARGIN, 0, 0); // same spot as home (dev setups): any side will do
        }
        return surface(overworld, p);
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
        BlockPos back = behind(landing, a.center, 24);
        int soldier = 0, engine = 0;
        for (RosterEntry e : alive) {
            boolean arsenal = a.hywRoster.isArsenal(e);
            // the engines (and their crews) set up in a line behind the landing point, towards home; the soldiers stand in
            // spaced ranks at it, facing the target
            int slot = arsenal ? engine++ : soldier++;
            if (e.entityUuid != null) {
                n += arsenal ? 0 : 1;
                continue;
            }
            BlockPos at = arsenal ? formation(back, a.center, slot / 2, 6, 5) : formation(landing, a.center, slot, 8, 3);
            Vec3 spot = dryGround(overworld, at, a.center, t.center, e.rosterId);
            if (spot == null) {
                continue; // no dry, safe ground found this time: this unit tries again on the next step
            }
            if (arsenal) {
                GarrisonService.materialize(overworld, a, e, spot, BlockPos.containing(spot), tick);
                continue;
            }
            if (spot == null) {
                spot = Vec3.atBottomCenterOf(landing);
            }
            if (GarrisonService.materialize(overworld, a, e, spot, BlockPos.containing(spot), tick)) {
                n++;
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
        s.defendersStart = defenders(overworld, t).size();
        s.enter(Siege.Phase.BATTLE, tick, tick + r.battleTicks());
        ledger.setDirty();
        announce(overworld, ledger, s, a, t, "The host of " + a.name + " (" + n + " soldiers) stands before " + t.name + "; "
                + s.defendersStart + " defenders take up arms");
        HmLog.info("Siege {}: {} unit(s) materialized at {} before {}; {} defender(s)", s.id.toString().substring(0, 8), n, landing.toShortString(),
                t.name, s.defendersStart);
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
        List<LivingEntity> defs = defenders(overworld, t);
        List<LivingEntity> foes = targets(overworld, t);
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
                    if (hop != null) {
                        units.setHome(ent, hop);
                    }
                }
            }
        }
        for (ServerPlayer p : overworld.players()) {
            if (p.distanceToSqr(Vec3.atCenterOf(t.center)) > HELPER_RANGE * HELPER_RANGE) {
                continue;
            }
            Campaign c = RelationProjector.campaignOf(ledger, p.getUUID());
            if (c != null && c.active(tick)) {
                if (c.ally().equals(a.villageId) && c.enemy().equals(t.villageId) && s.attackerHelpers.add(p.getUUID())) {
                    ledger.setDirty();
                } else if (c.ally().equals(t.villageId) && c.enemy().equals(a.villageId) && s.defenderHelpers.add(p.getUUID())) {
                    ledger.setDirty();
                }
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
        double p = SiegeMath.winChance(h, d, r);
        boolean won = SiegeMath.draw(s.seed(tick)) < p;
        double[] loss = SiegeMath.losses(won, h, d, r);
        List<UUID> hostIds = alive.stream().map(e -> e.rosterId).toList();
        List<UUID> defIds = home.stream().map(e -> e.rosterId).toList();
        List<UUID> hostDead = SiegeMath.casualties(hostIds, loss[0], s.seed(1));
        List<UUID> defDead = SiegeMath.casualties(defIds, loss[1], s.seed(2));
        hostDead.forEach(id -> kill(overworld, a, id, tick));
        defDead.forEach(id -> kill(overworld, t, id, tick));
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
        count(o == Siege.Outcome.WON ? C_WON : C_LOST);
        StringBuilder text = new StringBuilder();
        if (t != null) {
            VillageRecord winner = o == Siege.Outcome.WON ? a : t;
            VillageRecord loser = o == Siege.Outcome.WON ? t : a;
            int tribute = r.tribute(PoliticsTables.MilitaryTierKey.valueOf(loser.tier.name()));
            double levy = SiegeMath.levy(tribute, r);
            if (winner.hywRoster != null) {
                winner.hywRoster.levyPoints += levy;
            }
            if (loser.hywRoster != null) {
                loser.hywRoster.levyPoints = Math.max(0, loser.hywRoster.levyPoints - levy);
            }
            text.append(o == Siege.Outcome.WON ? t.name + " fell to the host of " + a.name : t.name + " held against the host of " + a.name)
                    .append(" (").append(how).append("); ").append(loser.name).append(" pays ")
                    .append(dev.hywmill.recruit.RecruitOffers.money(tribute)).append(" in tribute to ").append(winner.name);
            Set<UUID> helpers = o == Siege.Outcome.WON ? s.attackerHelpers : s.defenderHelpers;
            reward(overworld, ledger, winner, helpers, SiegeMath.helperPay(tribute, helpers.size(), r), r, text.toString());
            chronicle(overworld, a, t, tick, text.toString());
        } else {
            text.append("The host of ").append(a.name).append(" comes home: ").append(how);
        }
        s.summary = text.toString();
        announce(overworld, ledger, s, a, t, s.summary);
        HmLog.info("Siege {} ended {}: {}", s.id.toString().substring(0, 8), o, s.summary);
        goHome(overworld, ledger, s, a, entries(a, s.host), tick, r, watched);
    }

    private static void reward(ServerLevel overworld, GarrisonLedger ledger, VillageRecord winner, Set<UUID> helpers, int pay,
                               PoliticsTables.SiegeRule r, String text) {
        SettlementSource source = Services.settlements();
        PoliticsTables tables = PoliticsService.tables(winner);
        for (UUID p : helpers) {
            winner.politics.get(p).favor.earn(FavorSource.SIEGE_VICTORY, tables.favor());
            if (source != null && r.helperRep() != 0) {
                source.adjustReputation(overworld, winner.villageId, p, r.helperRep());
            }
            String note = "Your share of the tribute: " + dev.hywmill.recruit.RecruitOffers.money(pay) + ", and " + winner.name + " remembers your service";
            ServerPlayer online = PoliticsService.onlinePlayer(overworld, p);
            if (online != null) {
                if (source != null && pay > 0) {
                    source.giveMoney(online, pay);
                }
                online.sendSystemMessage(Component.literal("[Siege] " + note));
            } else {
                ledger.pendingPay().add(new PoliticsNbt.PendingPay(p, source != null ? pay : 0, text + ". " + note));
            }
        }
        ledger.setDirty();
    }

    /** Survivors leave the target (stowed) and march home. */
    private static void goHome(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, List<RosterEntry> alive, long tick,
                               PoliticsTables.SiegeRule r, boolean watched) {
        alive.forEach(e -> GarrisonService.stow(overworld, e));
        VillageRecord t = ledger.get(s.target);
        long march = t == null ? r.minMarch() : SiegeMath.marchTicks(Math.sqrt(a.center.distSqr(t.center)), r);
        s.enter(Siege.Phase.RETURN, tick, tick + march);
        if (alive.isEmpty()) {
            ledger.sieges().remove(s);
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
        int pending = 0, back = 0, slot = 0;
        BlockPos toward = ledger.get(s.target) != null ? ledger.get(s.target).center : a.center;
        for (RosterEntry e : alive) {
            if (a.hywRoster.isArsenal(e) && !a.hywRoster.arsenalWar) {
                a.hywRoster.disarm(e); // the war ended while they were away: they stand down without coming back into the world
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
        if (pending == 0) {
            ledger.sieges().remove(s);
            HmLog.info("Siege {}: {} survivor(s) back in {}", s.id.toString().substring(0, 8), back, a.name);
        }
        ledger.setDirty();
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

    private static void chronicle(ServerLevel overworld, VillageRecord a, @Nullable VillageRecord t, long tick, String text) {
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
                    + " id " + s.id.toString().substring(0, 8));
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

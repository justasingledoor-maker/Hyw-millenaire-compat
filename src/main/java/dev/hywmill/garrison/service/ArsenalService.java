package dev.hywmill.garrison.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.PerfCounters;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.service.PoliticsService;
import dev.hywmill.politics.war.ArsenalPlan;
import dev.hywmill.politics.war.WarRecord;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Post-M5 war arsenal. A village that is at war (declared or declared on) fields 1 to 4 siege engines (catapults,
 * springalds; a stronghold also a nest of bees), each with an engineer, at its defending position. Engines lost during the
 * war are not replaced; the survivors leave when the village is at peace again, and the next war brings a fresh arsenal.
 * A village's engines march with its sieges. The state is the roster's arsenal list; this service only reconciles it with
 * the wars and spawns what is not in the world yet.
 */
public final class ArsenalService {
    public static final int INTERVAL = 100;
    public static final int OFFSET = 41;

    private final PerfCounters perf;

    public ArsenalService(PerfCounters perf) {
        this.perf = perf;
    }

    public static PoliticsTables.ArsenalRule rule(VillageRecord rec) {
        return PoliticsService.tables(rec).arsenal();
    }

    public static boolean atWar(GarrisonLedger ledger, UUID village) {
        for (WarRecord w : ledger.wars().values()) {
            if (w.atWar() && w.involves(village)) {
                return true;
            }
        }
        return false;
    }

    /** Living engines of the arsenal (for strength and siege trains). */
    public static List<RosterEntry> engines(GarrisonRoster r) {
        List<RosterEntry> out = new ArrayList<>();
        for (RosterEntry e : r.arsenal()) {
            if (!e.state().terminal() && ArsenalPlan.isEngine(e.unitKey)) {
                out.add(e);
            }
        }
        return out;
    }

    public void tick(ServerLevel overworld, long tick) {
        if (tick % INTERVAL != OFFSET) {
            return;
        }
        long t0 = perf.start();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        for (VillageRecord rec : ledger.all()) {
            GarrisonRoster r = rec.hywRoster;
            if (r == null) {
                continue;
            }
            try {
                boolean war = atWar(ledger, rec.villageId);
                if (war && !r.arsenalWar) {
                    grant(overworld, ledger, rec, r, tick);
                } else if (!war && r.arsenalWar) {
                    retire(overworld, ledger, rec, r);
                }
                if (r.arsenalWar) {
                    dropRetiredTypes(overworld, ledger, rec, r);
                    spawnPending(overworld, ledger, rec, r, tick);
                }
            } catch (RuntimeException ex) {
                HmLog.warn("War arsenal of village '{}' failed: {}", rec.name, ex.toString());
            }
        }
        perf.stop("arsenal.tick", t0);
    }

    private static void grant(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r, long tick) {
        r.arsenalWar = true;
        PoliticsTables.ArsenalRule rule = rule(rec);
        long seed = UUID.nameUUIDFromBytes((rec.villageId + ">arsenal>" + tick).getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
        List<String> engines = ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.valueOf(rec.tier.name()), rule, seed);
        for (String key : engines) {
            r.arm(rec.villageId, key, "hundred_years_war:" + key, tick);
            r.arm(rec.villageId, ArsenalPlan.ENGINEER, "hundred_years_war:" + ArsenalPlan.ENGINEER, tick);
        }
        ledger.setDirty();
        if (!engines.isEmpty()) {
            String text = rec.name + " goes to war and raises " + engines.size() + " siege engine" + (engines.size() == 1 ? "" : "s") + " ("
                    + String.join(", ", engines.stream().map(ArsenalService::label).toList()) + ")";
            PoliticsService.chronicle(overworld, Services.settlements(), rec, tick, text);
            HmLog.info("War arsenal: {}", text);
        }
    }

    /** At peace again: the engines (and engineers) leave; those away on a siege leave when they come home. */
    private static void retire(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r) {
        r.arsenalWar = false;
        int n = 0;
        for (RosterEntry e : new ArrayList<>(r.arsenal())) {
            if (!e.state().terminal() && e.duty == Duty.SIEGE) {
                continue;
            }
            GarrisonService.stow(overworld, e);
            r.disarm(e);
            n++;
        }
        ledger.setDirty();
        HmLog.info("War arsenal of village '{}' stood down: {} engine/crew slot(s) removed", rec.name, n);
    }

    /**
     * Engines of a type the rule no longer fields (trebuchets, from before they were dropped) leave at once, unless away
     * on a siege; their engineer stays with the village's other engines.
     */
    private static void dropRetiredTypes(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r) {
        PoliticsTables.ArsenalRule rule = rule(rec);
        for (RosterEntry e : new ArrayList<>(r.arsenal())) {
            if (!ArsenalPlan.isEngine(e.unitKey) || rule.types().contains(e.unitKey) || rule.strongholdTypes().contains(e.unitKey)
                    || (!e.state().terminal() && e.duty == Duty.SIEGE)) {
                continue;
            }
            GarrisonService.stow(overworld, e);
            r.disarm(e);
            ledger.setDirty();
            HmLog.info("War arsenal of village '{}': {} no longer fielded, removed", rec.name, label(e.unitKey));
        }
    }

    /**
     * Arsenal entries not in the world yet (new, or back from a siege) appear at their battery once the village is loaded:
     * fix53, the engines spread round the village's edge, each covering an approach beside a road, never in the middle;
     * each engineer next to an engine. An engine at home standing in the middle (from before) is moved out to its battery
     * while no siege is fought there.
     */
    private static void spawnPending(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r, long tick) {
        BlockPos anchor = GarrisonService.anchorOf(rec);
        if (!overworld.isPositionEntityTicking(anchor)) {
            return;
        }
        List<RosterEntry> engines = new ArrayList<>(), crews = new ArrayList<>();
        for (RosterEntry e : r.arsenal()) {
            if (!e.state().terminal() && e.duty != Duty.SIEGE) { // those away on a siege need no battery
                (ArsenalPlan.isEngine(e.unitKey) ? engines : crews).add(e);
            }
        }
        boolean fighting = MobilizationService.inSiegeBattle(ledger, rec);
        int radius = rec.villageRadius > 0 ? rec.villageRadius : SiegeService.DEFAULT_RADIUS;
        List<BlockPos> batteries = null;
        boolean changed = false;
        for (int i = 0; i < engines.size() + crews.size(); i++) {
            boolean engine = i < engines.size();
            RosterEntry e = engine ? engines.get(i) : crews.get(i - engines.size());
            int slot = engine ? i : i - engines.size();
            if (!fighting && e.entityUuid != null && e.duty != Duty.SIEGE && GarrisonService.find(overworld.getServer(), e.entityUuid) instanceof Entity ent
                    && ent.distanceToSqr(rec.center.getX(), ent.getY(), rec.center.getZ()) < (radius / 2.0) * (radius / 2.0)) {
                GarrisonService.stow(overworld, e); // in the middle of the village: out to its battery on this pass
                changed = true;
            }
            boolean pending = e.state() == UnitState.RECRUITED
                    || ((e.state() == UnitState.GARRISONED || e.state() == UnitState.RECOVERED) && e.entityUuid == null && e.duty != Duty.SIEGE && !e.wounded);
            if (!pending) {
                continue;
            }
            if (batteries == null) {
                batteries = batteries(overworld, rec, Math.max(1, engines.size()));
            }
            BlockPos at = batteries.get(slot % batteries.size());
            Vec3 spot = GarrisonService.spotNear(overworld, engine ? at : at.offset(3, 0, 2), e.rosterId);
            if (spot == null || !overworld.isPositionEntityTicking(BlockPos.containing(spot))) {
                continue;
            }
            if (GarrisonService.materialize(overworld, rec, e, spot, BlockPos.containing(spot), tick)) {
                if (e.state() == UnitState.RECRUITED) {
                    e.transition(UnitState.SPAWNED, tick);
                    e.transition(UnitState.GARRISONED, tick);
                }
                e.duty = Duty.GARRISON;
                changed = true;
            }
        }
        if (changed) {
            ledger.setDirty();
        }
    }

    /**
     * Fix53: {@code n} battery positions round the village's edge, one per equal sector (turned by the village's own id):
     * beside the first road found in the sector between 70% of the village's radius and just past it (four blocks to the
     * side of the road, never on it), else the open edge at the sector's middle.
     */
    static List<BlockPos> batteries(ServerLevel level, VillageRecord rec, int n) {
        int radius = rec.villageRadius > 0 ? rec.villageRadius : SiegeService.DEFAULT_RADIUS;
        double sector = 2 * Math.PI / n;
        double turn = (rec.villageId.getLeastSignificantBits() & 0xffff) / 65536.0 * 2 * Math.PI;
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double mid = turn + i * sector;
            BlockPos found = null;
            for (int k = 0; k <= 6 && found == null; k++) {
                for (int sign = 1; sign >= -1 && found == null; sign -= 2) {
                    double th = mid + sign * k * sector / 14;
                    for (int rr = (int) (radius * 0.7); rr <= radius + 8 && found == null; rr += 2) {
                        BlockPos col = new BlockPos(rec.center.getX() + (int) Math.round(rr * Math.cos(th)), 0,
                                rec.center.getZ() + (int) Math.round(rr * Math.sin(th)));
                        if (!level.isLoaded(col)) {
                            continue;
                        }
                        BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col).below();
                        if (road(level.getBlockState(top))) {
                            found = top.offset((int) Math.round(-Math.sin(th) * 4), 1, (int) Math.round(Math.cos(th) * 4));
                        }
                    }
                    if (k == 0) {
                        break;
                    }
                }
            }
            boolean road = found != null;
            if (found == null) {
                double rr = radius * 0.85;
                found = SiegeService.surface(level, rec.center.offset((int) Math.round(rr * Math.cos(mid)), 0, (int) Math.round(rr * Math.sin(mid))));
            }
            out.add(found);
            HmLog.info("War arsenal of village '{}': battery {} at {} ({} blocks out, village radius {}{})", rec.name, i + 1, found.toShortString(),
                    (int) Math.sqrt(found.distSqr(rec.center.atY(found.getY()))), radius, road ? ", beside a road" : ", open edge");
        }
        return out;
    }

    /** A road block: Millénaire's paths (dirt, gravel, slabs, sandstone, tiles) and vanilla dirt paths. */
    static boolean road(net.minecraft.world.level.block.state.BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains("path");
    }

    /** Admin: the village's arsenal stands down and a fresh one is raised now (a war in progress gets the current rule's engines). */
    public static int rearm(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, long tick) {
        GarrisonRoster r = rec.hywRoster;
        if (r == null || !atWar(ledger, rec.villageId)) {
            return -1;
        }
        retire(overworld, ledger, rec, r);
        grant(overworld, ledger, rec, r, tick);
        return engines(r).size();
    }

    public static String label(String key) {
        return switch (key) {
            case "mangonels" -> "catapult";
            case "trebuchets" -> "trebuchet";
            case "springald" -> "springald";
            case "nest_of_bees" -> "nest of bees";
            case "battering_ram" -> "battering ram";
            case "siege_engineer" -> "engineer";
            default -> key.replace('_', ' ');
        };
    }

    /** One line per armed village (status command). */
    public static List<String> describe(GarrisonLedger ledger) {
        List<String> out = new ArrayList<>();
        for (VillageRecord rec : ledger.all()) {
            GarrisonRoster r = rec.hywRoster;
            if (r == null || (!r.arsenalWar && r.arsenal().isEmpty())) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            for (RosterEntry e : r.arsenal()) {
                parts.add(label(e.unitKey) + ":" + e.state() + (e.duty == Duty.SIEGE ? "/SIEGE" : ""));
            }
            out.add(rec.name + " arsenal " + (r.arsenalWar ? "(at war)" : "(standing down)") + " " + parts);
        }
        return out;
    }
}

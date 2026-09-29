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

    /** Arsenal entries not in the world yet appear at the defending position once it is loaded (engine first, then crew). */
    private static void spawnPending(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r, long tick) {
        BlockPos anchor = GarrisonService.anchorOf(rec);
        if (!overworld.isPositionEntityTicking(anchor)) {
            return;
        }
        boolean changed = false;
        int i = 0;
        for (RosterEntry e : r.arsenal()) {
            i++;
            if (e.state() != UnitState.RECRUITED) {
                continue;
            }
            // engines stand in a loose line a little behind the defending position, each crew next to its engine
            BlockPos spot0 = anchor.offset(((i - 1) / 2) * 5 - 8, 0, 6);
            Vec3 spot = GarrisonService.spotNear(overworld, spot0, e.rosterId);
            if (spot == null) {
                continue;
            }
            if (GarrisonService.materialize(overworld, rec, e, spot, BlockPos.containing(spot), tick)) {
                e.transition(UnitState.SPAWNED, tick);
                e.transition(UnitState.GARRISONED, tick);
                changed = true;
            }
        }
        if (changed) {
            ledger.setDirty();
        }
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

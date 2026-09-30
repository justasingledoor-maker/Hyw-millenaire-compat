package dev.hywmill.garrison.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.PerfCounters;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.LossReason;
import dev.hywmill.garrison.Mobilization;
import dev.hywmill.garrison.Recruitment;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.tables.GarrisonTable;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.service.PoliticsService;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-M5 wartime mobilization. When a village that is not a stronghold goes to war (declared or declared on), it fills
 * its garrison up to its current target at once, at no levy cost, with fresh troops equipped a step below its regulars
 * ({@link Mobilization}). They are ordinary garrison units (duties, defense, sieges) until the village is at peace again;
 * then they are sent home (LOST, DISCHARGED), those away on a siege when they come back. Losses during the war are
 * replaced by ordinary recruitment only.
 */
public final class MobilizationService {
    public static final int INTERVAL = 100;
    public static final int OFFSET = 47;

    private final PerfCounters perf;

    public MobilizationService(PerfCounters perf) {
        this.perf = perf;
    }

    public static PoliticsTables.MobilizationRule rule(VillageRecord rec) {
        return PoliticsService.tables(rec).mobilization();
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
                boolean war = ArsenalService.atWar(ledger, rec.villageId);
                if (war && !r.mobilizedWar && r.startingGranted) {
                    mobilize(overworld, ledger, rec, r, tick);
                } else if (war && r.mobilizedWar) {
                    reinforce(overworld, ledger, rec, r, tick);
                } else if (!war && (r.mobilizedWar || hasLevies(r))) {
                    demobilize(overworld, ledger, rec, r, tick);
                }
            } catch (RuntimeException ex) {
                HmLog.warn("Mobilization of village '{}' failed: {}", rec.name, ex.toString());
            }
        }
        perf.stop("mobilization.tick", t0);
    }

    private static boolean hasLevies(GarrisonRoster r) {
        for (RosterEntry e : r.entries()) {
            if (e.mobilized && !e.state().terminal()) {
                return true;
            }
        }
        return false;
    }

    /** Raises the gap between the living garrison and the current target (once per war). Returns the number raised. */
    public static int mobilize(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r, long tick) {
        r.mobilizedWar = true;
        r.lastLevyTick = tick;
        ledger.setDirty();
        PoliticsTables.MobilizationRule rule = rule(rec);
        if (!rule.enabled() || !Mobilization.mobilizes(rec.tier, rec.loneBuilding)) {
            return 0;
        }
        GarrisonTable table = GarrisonTables.current().forCulture(rec.culture);
        int target = target(rec, table);
        List<String> raised = raise(rec, r, Mobilization.count(r.live(), target, table.tier(rec.tier).maxUnits()), rule, tick);
        if (!raised.isEmpty()) {
            String text = rec.name + " mobilizes for war: " + raised.size() + " fresh soldier" + (raised.size() == 1 ? "" : "s")
                    + " fill its ranks (" + r.live() + "/" + target + ")";
            PoliticsService.chronicle(overworld, Services.settlements(), rec, tick, text);
            HmLog.info("Mobilization: {}: {}", text, raised);
        }
        return raised.size();
    }

    /**
     * While at war: losses are made good with more levies, up to {@code reinforceBatch} every {@code reinforceInterval} ticks
     * while the garrison is below target (so a village beaten in one siege is not an empty shell for the next).
     */
    public static int reinforce(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r, long tick) {
        PoliticsTables.MobilizationRule rule = rule(rec);
        if (!rule.enabled() || !Mobilization.mobilizes(rec.tier, rec.loneBuilding) || r.paused) {
            return 0;
        }
        GarrisonTable table = GarrisonTables.current().forCulture(rec.culture);
        int target = target(rec, table);
        int n = Mobilization.topUp(r.live(), target, table.tier(rec.tier).maxUnits(), tick, r.lastLevyTick, rule.reinforceInterval(),
                rule.reinforceBatch());
        if (n <= 0) {
            return 0;
        }
        List<String> raised = raise(rec, r, n, rule, tick);
        r.lastLevyTick = tick;
        ledger.setDirty();
        if (!raised.isEmpty()) {
            HmLog.info("Mobilization: {} raises {} more levies ({}/{}): {}", rec.name, raised.size(), r.live(), target, raised);
        }
        return raised.size();
    }

    private static int target(VillageRecord rec, GarrisonTable table) {
        HywMillRuntime rt = HywMillRuntime.get();
        return rt != null ? rt.garrison().effectiveTarget(rec) : GarrisonService.target(rec, table);
    }

    /**
     * Recruits {@code n} free levies (marked mobilized) from the village's composition plus the levy units, which are allowed
     * below their usual tier, at the mobilized equipment level. Returns their unit keys.
     */
    static List<String> raise(VillageRecord rec, GarrisonRoster r, int n, PoliticsTables.MobilizationRule rule, long tick) {
        GarrisonTables tables = GarrisonTables.current();
        GarrisonTable table = tables.forCulture(rec.culture);
        List<UnitSpec> eligible = new ArrayList<>(Recruitment.eligibleUnits(rec.tier, table, tables.units()));
        for (String key : rule.levyUnits().keySet()) {
            UnitSpec u = tables.units().get(key);
            if (u != null && eligible.stream().noneMatch(x -> x.key().equals(key))) {
                eligible.add(u);
            }
        }
        java.util.Map<String, Integer> weights = Mobilization.weights(table.composition(), rule.levyUnits());
        int level = Mobilization.equipmentLevel(Recruitment.equipmentLevel(rec.tier, table), rule.equipmentFloor(), rule.equipmentDrop());
        List<String> raised = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UnitSpec u = Recruitment.chooseUnit(rec.villageId, r.nextSeq, eligible, weights, Recruitment.liveCounts(r));
            if (u == null) {
                break;
            }
            RosterEntry e = r.recruit(rec.villageId, u.key(), u.entityType(), level, tick, false);
            e.mobilized = true;
            raised.add(u.key());
        }
        return raised;
    }

    /** At peace: the mobilized soldiers go home; those away on a siege go when they are back. Returns the number sent home. */
    public static int demobilize(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r, long tick) {
        int n = 0;
        boolean away = false;
        for (RosterEntry e : r.entries()) {
            if (!e.mobilized || e.state().terminal()) {
                continue;
            }
            if (e.duty == Duty.SIEGE) {
                away = true;
                continue;
            }
            discharge(overworld, e, tick);
            n++;
        }
        if (!away) {
            r.mobilizedWar = false;
            r.lastLevyTick = -1;
        }
        ledger.setDirty();
        if (n > 0) {
            String text = "The war is over: " + n + " mobilized soldier" + (n == 1 ? "" : "s") + " of " + rec.name + " go home";
            PoliticsService.chronicle(overworld, Services.settlements(), rec, tick, text);
            HmLog.info("Mobilization: {}", text);
        }
        return n;
    }

    /** One mobilized soldier goes home: the entity leaves the world and the slot ends (not a loss, no recruitment effect). */
    public static void discharge(ServerLevel overworld, RosterEntry e, long tick) {
        GarrisonService.stow(overworld, e);
        e.clearErrand();
        e.transition(UnitState.LOST, tick, LossReason.DISCHARGED);
    }
}

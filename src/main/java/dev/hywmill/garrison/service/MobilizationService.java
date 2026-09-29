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
        ledger.setDirty();
        PoliticsTables.MobilizationRule rule = rule(rec);
        if (!rule.enabled() || !Mobilization.mobilizes(rec.tier, rec.loneBuilding)) {
            return 0;
        }
        HywMillRuntime rt = HywMillRuntime.get();
        GarrisonTables tables = GarrisonTables.current();
        GarrisonTable table = tables.forCulture(rec.culture);
        int target = rt != null ? rt.garrison().effectiveTarget(rec) : GarrisonService.target(rec, table);
        int n = Mobilization.count(r.live(), target, table.tier(rec.tier).maxUnits());
        List<UnitSpec> eligible = Recruitment.eligibleUnits(rec.tier, table, tables.units());
        int level = Mobilization.equipmentLevel(Recruitment.equipmentLevel(rec.tier, table), rule.equipmentFloor(), rule.equipmentDrop());
        List<String> raised = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UnitSpec u = Recruitment.chooseUnit(rec.villageId, r.nextSeq, eligible, table.composition(), Recruitment.liveCounts(r));
            if (u == null) {
                break;
            }
            RosterEntry e = r.recruit(rec.villageId, u.key(), u.entityType(), level, tick, false);
            e.mobilized = true;
            raised.add(u.key());
        }
        if (!raised.isEmpty()) {
            String text = rec.name + " mobilizes for war: " + raised.size() + " fresh soldier" + (raised.size() == 1 ? "" : "s")
                    + " fill its ranks (" + r.live() + "/" + target + ")";
            PoliticsService.chronicle(overworld, Services.settlements(), rec, tick, text);
            HmLog.info("Mobilization: {} (equipment level {}): {}", text, level, raised);
        }
        return raised.size();
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

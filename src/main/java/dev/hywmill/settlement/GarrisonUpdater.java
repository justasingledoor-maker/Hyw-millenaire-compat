package dev.hywmill.settlement;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.faction.FactionMarker;
import dev.hywmill.military.ThreatTracker;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Optional;

/**
 * Periodic ledger update (~every 200 ticks). Discovers villages cheaply from the settlement
 * list; computes full snapshots only for active villages, plus once for newly discovered ones.
 */
public final class GarrisonUpdater {
    private GarrisonUpdater() {}

    public static void update(ServerLevel overworld) {
        SettlementSource source = Services.settlements();
        if (source == null) {
            return;
        }
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        long tick = overworld.getGameTime();
        List<SettlementSource.SettlementRef> refs = source.list(overworld);
        int updated = 0;
        for (SettlementSource.SettlementRef ref : refs) {
            boolean known = ledger.get(ref.id()) != null;
            if (!known) {
                HmLog.info("Village discovered: '{}' {} at {} (active={})", ref.name(), ref.id(), ref.center().toShortString(), ref.active());
            }
            if (!ref.active() && known) {
                ThreatTracker.markInactive(ref.id());
                continue;
            }
            refreshOne(overworld, source, ledger, ref.id(), tick);
            updated++;
        }
        HmLog.diagThrottled("ledger-summary", 60_000L, "Ledger update: {} village(s) known to Millénaire, {} refreshed", refs.size(), updated);
    }

    /** Recomputes one village now (also used by /hywmill village info when a record is missing). */
    public static Optional<VillageRecord> refreshOne(ServerLevel overworld, SettlementSource source, GarrisonLedger ledger,
                                                      java.util.UUID villageId, long tick) {
        Optional<SettlementSnapshot> snap = source.snapshot(overworld, villageId);
        if (snap.isEmpty()) {
            return Optional.empty();
        }
        SettlementSnapshot s = snap.get();
        VillageRecord record = ledger.getOrCreate(villageId, tick);
        boolean firstUpdate = record.updateCount == 0;
        List<String> diffs = record.apply(s, tick);
        if (s.active()) {
            FactionMarker.SweepResult sweep = FactionMarker.sweep(overworld, villageId);
            record.loadedResidents = sweep.loaded();
            record.markedResidents = sweep.marked();
        }
        ledger.setDirty();
        ThreatTracker.updateVillage(s, record.factionId);
        if (firstUpdate) {
            HmLog.info("Village record initialized: '{}' tier={} garrison={} population={} defending={} fortification={} tags={} walls={} towers={} defensive={} militaryPlans={}",
                    record.name, record.tier, record.garrison, record.population, record.defendingStrength, record.fortification,
                    record.tagCounts, record.wallSegments, record.wallTowers, record.defensiveBuildings, record.militaryPlans);
        } else if (!diffs.isEmpty()) {
            HmLog.info("Village record updated: '{}' {}", record.name, diffs);
        } else {
            HmLog.diag("Village record unchanged: '{}' (update #{})", record.name, record.updateCount);
        }
        return Optional.of(record);
    }
}

package dev.hywmill.settlement;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.Services;
import dev.hywmill.faction.FactionMarker;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Periodic ledger update. The village list is re-read once per interval; each village is then
 * refreshed on its own staggered tick (every ledger interval), so work is spread across ticks.
 * Full snapshots are computed for active villages, plus once for newly discovered ones.
 */
public final class GarrisonUpdater {
    private static final List<String> HEADLINE_FIELDS = List.of("tier", "garrison", "fortification");

    private GarrisonUpdater() {}

    public static void tick(ServerLevel overworld, HywMillRuntime rt) {
        SettlementSource source = Services.settlements();
        if (source == null) {
            return;
        }
        long tick = overworld.getGameTime();
        int interval = HywMillConfig.LEDGER_UPDATE_INTERVAL.get();
        if (rt.cachedVillages == null || tick % interval == 0) {
            rt.cachedVillages = source.list(overworld);
            HmLog.diagThrottled("ledger-summary", 60_000L, "Ledger: {} village(s) known to Millénaire", rt.cachedVillages.size());
        }
        GarrisonLedger ledger = null;
        for (SettlementSource.SettlementRef ref : rt.cachedVillages) {
            if (!rt.scheduler().isDue(ref.id(), tick, interval)) {
                continue;
            }
            if (ledger == null) {
                ledger = GarrisonLedger.get(overworld);
            }
            VillageRecord existing = ledger.get(ref.id());
            boolean known = existing != null;
            if (!known) {
                HmLog.info("Village discovered: '{}' {} at {} (active={})", ref.name(), ref.id(), ref.center().toShortString(), ref.active());
            }
            if (!ref.active() && known && !existing.needsRecompute) {
                rt.threats().markInactive(ref.id());
                continue;
            }
            refreshOne(overworld, rt, source, ledger, ref.id(), tick);
        }
    }

    /** Recomputes one village now (also used by /hywmill village info when a record is missing). */
    public static Optional<VillageRecord> refreshOne(ServerLevel overworld, HywMillRuntime rt, SettlementSource source,
                                                      GarrisonLedger ledger, UUID villageId, long tick) {
        Optional<SettlementSnapshot> snap = source.snapshot(overworld, villageId);
        if (snap.isEmpty()) {
            return Optional.empty();
        }
        SettlementSnapshot s = snap.get();
        VillageRecord record = ledger.getOrCreate(villageId, tick);
        boolean firstUpdate = record.updateCount == 0;
        boolean migrated = record.needsRecompute;
        List<String> diffs = record.apply(s, tick);
        if (s.active()) {
            FactionMarker.SweepResult sweep = FactionMarker.sweep(overworld, villageId);
            record.loadedResidents = sweep.loaded();
            record.markedResidents = sweep.marked();
        }
        ledger.setDirty();
        rt.threats().updateVillage(s, record.factionId);
        if (firstUpdate) {
            HmLog.info("Village record initialized: '{}' tier={} garrison={} population={} defending={} fortification={} villagers={} buildings={} tags={}",
                    record.name, record.tier, record.garrison, record.population, record.defendingStrength, record.fortification,
                    record.villagerRoles, record.buildingRoles, record.tagCounts);
            if (!record.ambiguousTypes.isEmpty()) {
                HmLog.info("Village '{}': villager types without a role-table entry, classified MILITIA by fallback (please review): {}",
                        record.name, record.ambiguousTypes);
            }
        } else if (migrated) {
            HmLog.info("Village record recomputed after ledger migration: '{}' tier={} fortification={} {}", record.name, record.tier, record.fortification, diffs);
        } else if (diffs.stream().anyMatch(d -> HEADLINE_FIELDS.stream().anyMatch(d::startsWith))) {
            HmLog.info("Village record updated: '{}' {}", record.name, diffs);
        } else if (!diffs.isEmpty()) {
            HmLog.diag("Village record updated: '{}' {}", record.name, diffs);
        } else {
            HmLog.diag("Village record unchanged: '{}' (update #{})", record.name, record.updateCount);
        }
        return Optional.of(record);
    }
}

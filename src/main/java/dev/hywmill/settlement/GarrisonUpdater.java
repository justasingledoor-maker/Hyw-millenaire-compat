package dev.hywmill.settlement;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.Services;
import dev.hywmill.faction.FactionMarker;
import dev.hywmill.military.doctrine.DoctrineDefaults;
import dev.hywmill.military.doctrine.DoctrineResolver;
import dev.hywmill.military.doctrine.DoctrineTables;
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
    private static final List<String> HEADLINE_FIELDS = List.of("tier", "garrison", "fortification", "capacity", "controller");

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
            rt.defense().prune(rt.cachedVillages.stream().map(SettlementSource.SettlementRef::id).toList());
            rt.garrison().prune(rt.cachedVillages.stream().map(SettlementSource.SettlementRef::id).toList());
            rt.duties().prune(rt.cachedVillages.stream().map(SettlementSource.SettlementRef::id).toList());
            java.util.Set<UUID> present = new java.util.HashSet<>();
            rt.cachedVillages.forEach(v -> present.add(v.id()));
            rt.garrison().goneCheck(overworld, GarrisonLedger.get(overworld), present, tick);
            HmLog.diagThrottled("ledger-summary", 60_000L, "Ledger: {} village(s) known to Millénaire", rt.cachedVillages.size());
        }
        GarrisonLedger ledger = null;
        for (SettlementSource.SettlementRef ref : rt.cachedVillages) {
            if (ref.active() && rt.scheduler().isDue(ref.id(), tick + interval / 2, interval)) {
                // Identity sweep: once per interval like the refresh, but half an interval later, so the two
                // per-village work slices never land in the same tick.
                if (ledger == null) {
                    ledger = GarrisonLedger.get(overworld);
                }
                VillageRecord rec = ledger.get(ref.id());
                if (rec != null) {
                    long ts = rt.perf().start();
                    FactionMarker.SweepResult sweep = FactionMarker.sweep(overworld, ref.id());
                    rec.loadedResidents = sweep.loaded();
                    rec.markedResidents = sweep.marked();
                    rt.perf().stop("profile.sweep", ts);
                }
            }
            if (ref.active() && rt.scheduler().isDue(ref.id(), tick + interval / 4, interval)) {
                // M3 garrison slot: a quarter interval after the refresh, so it never shares a tick
                // with the refresh (offset 0) or the identity sweep (offset interval/2).
                if (ledger == null) {
                    ledger = GarrisonLedger.get(overworld);
                }
                VillageRecord rec = ledger.get(ref.id());
                if (rec != null && rec.updateCount > 0) {
                    rt.garrison().slot(overworld, ledger, rec, tick);
                }
            }
            int dutyInterval = HywMillConfig.DUTY_INTERVAL.get();
            if (ref.active() && rt.scheduler().isDue(ref.id(), tick + 5, dutyInterval)) {
                // M4 duties: 5 ticks off the village's phase, so with the default 40/200 intervals it never
                // shares a tick with the refresh (0), the garrison slot (interval/4) or the sweep (interval/2).
                if (ledger == null) {
                    ledger = GarrisonLedger.get(overworld);
                }
                VillageRecord rec = ledger.get(ref.id());
                if (rec != null && rec.updateCount > 0) {
                    rt.duties().tick(overworld, ledger, rec, tick);
                }
            }
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
        long t0 = rt.perf().start();
        Optional<SettlementSnapshot> snap = source.snapshot(overworld, villageId);
        rt.perf().stop("profile.snapshot", t0);
        if (snap.isEmpty()) {
            return Optional.empty();
        }
        SettlementSnapshot s = snap.get();
        VillageRecord record = ledger.getOrCreate(villageId, tick);
        boolean firstUpdate = record.updateCount == 0;
        boolean migrated = record.needsRecompute;
        long ta = rt.perf().start();
        List<String> diffs = record.apply(s, tick);
        rt.perf().stop("profile.apply", ta);
        if (firstUpdate && s.active()) {
            // A newly discovered village is swept right away; afterwards on its own staggered slot (see tick).
            FactionMarker.SweepResult sweep = FactionMarker.sweep(overworld, villageId);
            record.loadedResidents = sweep.loaded();
            record.markedResidents = sweep.marked();
        }
        ledger.setDirty();
        long td = rt.perf().start();
        DoctrineResolver.Resolved doctrine = resolveDoctrine(record);
        rt.threats().updateVillage(s, record.factionId, doctrine.doctrine(), record.controllerPlayerId);
        rt.defense().configure(villageId, doctrine, s.center(), s.defendingPos());
        rt.perf().stop("profile.doctrine", td);
        long tl = rt.perf().start();
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
        rt.perf().stop("profile.log", tl);
        rt.perf().stop("profile.refresh", t0);
        return Optional.of(record);
    }

    /**
     * The village's effective doctrine: culture → lone building → village type → tier → per-village
     * override. Cached on the record and re-resolved only when an input changes (context, override,
     * or a datapack reload replacing the defaults).
     */
    public static DoctrineResolver.Resolved resolveDoctrine(VillageRecord r) {
        DoctrineDefaults defaults = DoctrineTables.current();
        DoctrineResolver.Context ctx = new DoctrineResolver.Context(r.culture, r.type, r.villageRadius, r.loneBuilding, r.tier);
        if (r.cachedDoctrine == null || r.cachedDoctrineDefaults != defaults || !ctx.equals(r.cachedDoctrineContext)
                || r.cachedDoctrineOverride != r.doctrineOverride) {
            r.cachedDoctrine = DoctrineResolver.resolve(defaults, ctx, r.doctrineOverride);
            r.cachedDoctrineDefaults = defaults;
            r.cachedDoctrineContext = ctx;
            r.cachedDoctrineOverride = r.doctrineOverride;
        }
        return r.cachedDoctrine;
    }
}

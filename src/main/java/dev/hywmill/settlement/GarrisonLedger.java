package dev.hywmill.settlement;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.faction.FactionIds;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Our own persistent data, stored in the Overworld as {@code data/hywmill_garrison_ledger.dat},
 * keyed by Millénaire VillageId UUID.
 */
public final class GarrisonLedger extends SavedData {
    public static final String DATA_NAME = "hywmill_garrison_ledger";

    private final Map<UUID, VillageRecord> records = new LinkedHashMap<>();
    /** M5: pending envoy missions (ledger level, bounded per player by EnvoyMission.MAX_PER_PLAYER). */
    private final java.util.List<dev.hywmill.politics.EnvoyMission> envoys = new java.util.ArrayList<>();

    /** M5-4: envoy results waiting for their (offline) player; bounded. */
    private final java.util.List<PoliticsNbt.EnvoyReport> reports = new java.util.ArrayList<>();
    public static final int MAX_REPORTS = 256;

    public java.util.List<dev.hywmill.politics.EnvoyMission> envoys() {
        return envoys;
    }

    public java.util.List<PoliticsNbt.EnvoyReport> reports() {
        return reports;
    }

    /** M5-5b: village pair states (key a>b, a < b), campaigns, and the relation projector's "previous relation" records. */
    private final java.util.Map<String, dev.hywmill.politics.war.WarRecord> wars = new java.util.LinkedHashMap<>();
    private final java.util.List<dev.hywmill.politics.war.Campaign> campaigns = new java.util.ArrayList<>();
    /** "from>to" → the relation HywMill replaced when it projected this edge (restored when the cause ends). */
    private final java.util.Map<String, String> projections = new java.util.LinkedHashMap<>();

    public java.util.Map<String, dev.hywmill.politics.war.WarRecord> wars() {
        return wars;
    }

    public java.util.List<dev.hywmill.politics.war.Campaign> campaigns() {
        return campaigns;
    }

    public java.util.Map<String, String> projections() {
        return projections;
    }

    /** Post-M5: sieges under way (and just ended, until their host is home), and tribute owed to offline players. */
    private final java.util.List<dev.hywmill.politics.war.Siege> sieges = new java.util.ArrayList<>();
    private final java.util.List<PoliticsNbt.PendingPay> pendingPay = new java.util.ArrayList<>();

    public java.util.List<dev.hywmill.politics.war.Siege> sieges() {
        return sieges;
    }

    public java.util.List<PoliticsNbt.PendingPay> pendingPay() {
        return pendingPay;
    }

    /** Post-M5: siege tributes still being paid in daily installments. */
    private final java.util.List<dev.hywmill.politics.war.Tribute> tributes = new java.util.ArrayList<>();

    public java.util.List<dev.hywmill.politics.war.Tribute> tributes() {
        return tributes;
    }

    /** Post-M5: vassalages in force, and the reports of finished sieges (newest last, at most BattleReport.KEEP). */
    private final java.util.List<dev.hywmill.politics.war.Vassalage> vassalages = new java.util.ArrayList<>();
    /** Post-M5 realms: official treaties, one per pair (key {@link dev.hywmill.politics.realm.Treaty#key}). */
    private final java.util.Map<String, dev.hywmill.politics.realm.Treaty> treaties = new java.util.LinkedHashMap<>();

    public java.util.Map<String, dev.hywmill.politics.realm.Treaty> treaties() {
        return treaties;
    }

    /** Post-M5 realms: villages that rebelled, and the sovereign they threw off (a grudge the AI director remembers). */
    private final java.util.Map<UUID, UUID> rebels = new java.util.LinkedHashMap<>();

    public java.util.Map<UUID, UUID> rebels() {
        return rebels;
    }

    /** Realm councils (docs/realm-design.md §10): each realm head's last plan. */
    private final java.util.Map<UUID, dev.hywmill.politics.realm.RealmPlan> plans = new java.util.LinkedHashMap<>();

    public java.util.Map<UUID, dev.hywmill.politics.realm.RealmPlan> plans() {
        return plans;
    }

    /** Realm unity: vassals excused (for scutage) from their realm's war with a realm, and that realm's head. */
    private final java.util.Map<UUID, UUID> excused = new java.util.LinkedHashMap<>();

    public java.util.Map<UUID, UUID> excused() {
        return excused;
    }

    /** 1.7 (docs/realm-design.md §11): players in a realm's service. */
    private final java.util.Map<UUID, dev.hywmill.politics.realm.Service> services = new java.util.LinkedHashMap<>();
    /** 1.7: each realm head's officials. */
    private final java.util.Map<UUID, java.util.List<dev.hywmill.politics.realm.Officials.Official>> officials = new java.util.LinkedHashMap<>();
    /** 1.7: "player|head": the mercenary signing bonus already paid. */
    private final java.util.Set<String> bonuses = new java.util.LinkedHashSet<>();
    /** 1.7: "player|head": exiled (or a broken contract) until then. */
    private final java.util.Map<String, Long> exiles = new java.util.LinkedHashMap<>();
    /** 1.7: coalitions: each realm head feared, and the heads of the villages and realms leagued against it. */
    private final java.util.Map<UUID, java.util.Set<UUID>> coalitions = new java.util.LinkedHashMap<>();

    public java.util.Map<UUID, dev.hywmill.politics.realm.Service> services() {
        return services;
    }

    public java.util.Map<UUID, java.util.List<dev.hywmill.politics.realm.Officials.Official>> officials() {
        return officials;
    }

    public java.util.Set<String> bonuses() {
        return bonuses;
    }

    public java.util.Map<String, Long> exiles() {
        return exiles;
    }

    public java.util.Map<UUID, java.util.Set<UUID>> coalitions() {
        return coalitions;
    }

    /** Post-M5 realms: a razed village's record goes for good. */
    public void remove(UUID villageId) {
        if (records.remove(villageId) != null) {
            setDirty();
        }
    }
    private final java.util.List<dev.hywmill.politics.war.BattleReport> battles = new java.util.ArrayList<>();

    public java.util.List<dev.hywmill.politics.war.Vassalage> vassalages() {
        return vassalages;
    }

    public java.util.List<dev.hywmill.politics.war.BattleReport> battles() {
        return battles;
    }

    /** Post-M5: columns on the road (and the recent ended ones, for the War tab), and scouts out on a ride. */
    private final java.util.List<dev.hywmill.politics.war.Column> columns = new java.util.ArrayList<>();
    private final java.util.List<dev.hywmill.politics.war.ScoutRide> scoutRides = new java.util.ArrayList<>();

    public java.util.List<dev.hywmill.politics.war.Column> columns() {
        return columns;
    }

    public java.util.List<dev.hywmill.politics.war.ScoutRide> scoutRides() {
        return scoutRides;
    }

    /** Post-M5: each player's chosen colours for the soldiers they hire on the Muster Roll, as {first, second} dye ids. */
    private final java.util.Map<UUID, int[]> playerColours = new java.util.LinkedHashMap<>();

    public java.util.Map<UUID, int[]> playerColours() {
        return playerColours;
    }

    public static GarrisonLedger get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GarrisonLedger::new, GarrisonLedger::load, null), DATA_NAME);
    }

    @Nullable
    public VillageRecord get(UUID villageId) {
        return records.get(villageId);
    }

    public Collection<VillageRecord> all() {
        return Collections.unmodifiableCollection(records.values());
    }

    public VillageRecord getOrCreate(UUID villageId, long tick) {
        VillageRecord r = records.get(villageId);
        if (r == null) {
            r = new VillageRecord(villageId, FactionIds.forVillage(villageId));
            registerFaction(villageId);
            r.firstSeenTick = tick;
            records.put(villageId, r);
            setDirty();
            HmLog.info("Ledger record created for village {} (faction {})", villageId, r.factionId);
        }
        return r;
    }

    private static void registerFaction(UUID villageId) {
        HywMillRuntime rt = HywMillRuntime.get();
        if (rt != null) {
            rt.factions().register(villageId);
        }
    }

    private static GarrisonLedger load(CompoundTag root, HolderLookup.Provider registries) {
        GarrisonLedger ledger = new GarrisonLedger();
        ListTag list = root.getList("villages", Tag.TAG_COMPOUND);
        int format = root.getInt("format");
        int mismatched = 0;
        int migrated = 0;
        for (int i = 0; i < list.size(); i++) {
            VillageRecord r = VillageRecord.load(list.getCompound(i), format);
            if (r.needsRecompute) {
                migrated++;
            }
            for (String problem : r.overrideProblems) {
                HmLog.warn("Doctrine override entry of village {} dropped at load: {}", r.villageId, problem);
            }
            UUID expected = FactionIds.forVillage(r.villageId);
            if (!expected.equals(r.factionId)) {
                // Would indicate a change in the derivation; keep the deterministic value authoritative.
                mismatched++;
                r.factionId = expected;
            }
            ledger.records.put(r.villageId, r);
            registerFaction(r.villageId);
        }
        HmLog.info("Garrison ledger loaded: {} village record(s), format {}, faction-id mismatches corrected: {}",
                ledger.records.size(), format, mismatched);
        if (format < 4 && !ledger.records.isEmpty()) {
            // Format 3 -> 4: every record gets an empty garrison roster at its first garrison slot.
            ledger.setDirty();
            HmLog.info("Garrison ledger migrated {} record(s) from format {} to {}: empty HYW garrison rosters (one-time starting grant pending).",
                    ledger.records.size(), format, VillageRecord.FORMAT);
        } else if (format == 4 && !ledger.records.isEmpty()) {
            // Format 4 -> 5: politics start empty; nothing else changes.
            ledger.setDirty();
            HmLog.info("Garrison ledger migrated {} record(s) from format 4 to {}: political memory starts empty.",
                    ledger.records.size(), VillageRecord.FORMAT);
        }
        if (format >= 5 && root.contains("envoys", Tag.TAG_LIST)) {
            ledger.envoys.addAll(PoliticsNbt.loadEnvoys(root.getList("envoys", Tag.TAG_COMPOUND)));
        }
        if (format >= 5) {
            PoliticsNbt.loadWar(root, ledger.wars, ledger.campaigns, ledger.projections);
        }
        if (root.contains("sieges", Tag.TAG_LIST)) {
            ledger.sieges.addAll(PoliticsNbt.loadSieges(root.getList("sieges", Tag.TAG_COMPOUND)));
        }
        if (root.contains("pendingPay", Tag.TAG_LIST)) {
            ledger.pendingPay.addAll(PoliticsNbt.loadPending(root.getList("pendingPay", Tag.TAG_COMPOUND)));
        }
        ledger.tributes.addAll(PoliticsNbt.loadTributes(root.getList("tributes", Tag.TAG_COMPOUND)));
        ledger.vassalages.addAll(PoliticsNbt.loadVassalages(root.getList("vassalages", Tag.TAG_COMPOUND)));
        PoliticsNbt.loadTreaties(root.getList("treaties", Tag.TAG_COMPOUND)).forEach(t -> ledger.treaties.put(t.key(), t));
        ListTag rb = root.getList("rebels", Tag.TAG_COMPOUND);
        for (int i = 0; i < rb.size(); i++) {
            CompoundTag x = rb.getCompound(i);
            if (x.hasUUID("rebel") && x.hasUUID("against")) {
                ledger.rebels.put(x.getUUID("rebel"), x.getUUID("against"));
            }
        }
        PoliticsNbt.loadPlans(root.getList("plans", Tag.TAG_COMPOUND), ledger.plans);
        PoliticsNbt.loadServices(root.getCompound("service"), ledger.services, ledger.officials, ledger.bonuses, ledger.exiles, ledger.coalitions);
        ListTag ex = root.getList("excused", Tag.TAG_COMPOUND);
        for (int i = 0; i < ex.size(); i++) {
            CompoundTag x = ex.getCompound(i);
            if (x.hasUUID("vassal") && x.hasUUID("from")) {
                ledger.excused.put(x.getUUID("vassal"), x.getUUID("from"));
            }
        }
        ledger.battles.addAll(PoliticsNbt.loadBattles(root.getList("battles", Tag.TAG_COMPOUND)));
        ledger.columns.addAll(PoliticsNbt.loadColumns(root.getList("columns", Tag.TAG_COMPOUND)));
        ledger.scoutRides.addAll(PoliticsNbt.loadRides(root.getList("scoutRides", Tag.TAG_COMPOUND)));
        ListTag pc = root.getList("playerColours", Tag.TAG_COMPOUND);
        for (int i = 0; i < pc.size(); i++) {
            CompoundTag x = pc.getCompound(i);
            int[] c = x.getIntArray("c");
            if (x.hasUUID("player") && c.length == 2) {
                ledger.playerColours.put(x.getUUID("player"), c);
            }
        }
        if (format >= 5 && root.contains("envoyReports", Tag.TAG_LIST)) {
            ledger.reports.addAll(PoliticsNbt.loadReports(root.getList("envoyReports", Tag.TAG_COMPOUND)));
        }
        if (migrated > 0) {
            // Saved as the current format on the next save; values recomputed at each village's next update.
            ledger.setDirty();
            HmLog.info("Garrison ledger migrated {} record(s) from format {} to {}; tier and fortification will be recomputed at each village's next update.",
                    migrated, format, VillageRecord.FORMAT);
        }
        return ledger;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putInt("format", VillageRecord.FORMAT);
        ListTag list = new ListTag();
        for (VillageRecord r : records.values()) {
            list.add(r.save());
        }
        root.put("villages", list);
        if (!envoys.isEmpty()) {
            root.put("envoys", PoliticsNbt.saveEnvoys(envoys));
        }
        if (!reports.isEmpty()) {
            root.put("envoyReports", PoliticsNbt.saveReports(reports));
        }
        PoliticsNbt.saveWar(root, wars, campaigns, projections);
        if (!sieges.isEmpty()) {
            root.put("sieges", PoliticsNbt.saveSieges(sieges));
        }
        if (!pendingPay.isEmpty()) {
            root.put("pendingPay", PoliticsNbt.savePending(pendingPay));
        }
        if (!tributes.isEmpty()) {
            root.put("tributes", PoliticsNbt.saveTributes(tributes));
        }
        if (!vassalages.isEmpty()) {
            root.put("vassalages", PoliticsNbt.saveVassalages(vassalages));
        }
        if (!treaties.isEmpty()) {
            root.put("treaties", PoliticsNbt.saveTreaties(treaties.values()));
        }
        if (!rebels.isEmpty()) {
            ListTag rb = new ListTag();
            rebels.forEach((k, v) -> {
                CompoundTag x = new CompoundTag();
                x.putUUID("rebel", k);
                x.putUUID("against", v);
                rb.add(x);
            });
            root.put("rebels", rb);
        }
        if (!plans.isEmpty()) {
            root.put("plans", PoliticsNbt.savePlans(plans));
        }
        if (!services.isEmpty() || !officials.isEmpty() || !bonuses.isEmpty() || !exiles.isEmpty() || !coalitions.isEmpty()) {
            root.put("service", PoliticsNbt.saveServices(services, officials, bonuses, exiles, coalitions));
        }
        if (!excused.isEmpty()) {
            ListTag ex = new ListTag();
            excused.forEach((k, v) -> {
                CompoundTag x = new CompoundTag();
                x.putUUID("vassal", k);
                x.putUUID("from", v);
                ex.add(x);
            });
            root.put("excused", ex);
        }
        if (!battles.isEmpty()) {
            root.put("battles", PoliticsNbt.saveBattles(battles));
        }
        if (!columns.isEmpty()) {
            root.put("columns", PoliticsNbt.saveColumns(columns));
        }
        if (!scoutRides.isEmpty()) {
            root.put("scoutRides", PoliticsNbt.saveRides(scoutRides));
        }
        if (!playerColours.isEmpty()) {
            ListTag pc = new ListTag();
            playerColours.forEach((p, c) -> {
                CompoundTag x = new CompoundTag();
                x.putUUID("player", p);
                x.putIntArray("c", c);
                pc.add(x);
            });
            root.put("playerColours", pc);
        }
        return root;
    }
}

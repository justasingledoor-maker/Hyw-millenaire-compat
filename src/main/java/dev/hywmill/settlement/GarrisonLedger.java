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
        return root;
    }
}

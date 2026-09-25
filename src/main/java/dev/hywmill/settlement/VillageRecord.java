package dev.hywmill.settlement;

import dev.hywmill.fortification.FortificationScore;
import dev.hywmill.military.MilitaryTier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One village's entry in our own ledger. Never written into Millénaire's files. */
public final class VillageRecord {
    public final UUID villageId;
    public UUID factionId;
    public String name = "";
    public String culture = "";
    public String type = "";
    public BlockPos center = BlockPos.ZERO;
    public MilitaryTier tier = MilitaryTier.NONE;
    public int population;
    public int adults;
    public int garrison;
    public int defendingStrength;
    public int fortification;
    public Map<String, Integer> tagCounts = new LinkedHashMap<>();
    public int wallSegments;
    public int wallSegmentsPending;
    public int wallTowers;
    public int defensiveBuildings;
    public List<String> militaryPlans = new ArrayList<>();
    public String townhallPlan = "";
    public int buildingsOperational;
    public long firstSeenTick;
    public long lastUpdateTick;
    public int updateCount;
    public int loadedResidents;
    public int markedResidents;

    public VillageRecord(UUID villageId, UUID factionId) {
        this.villageId = villageId;
        this.factionId = factionId;
    }

    /**
     * Applies a fresh snapshot and returns human-readable differences for fields that matter
     * (empty on no change). Tier and fortification are derived here.
     */
    public List<String> apply(SettlementSnapshot s, long tick) {
        int newFort = FortificationScore.compute(s);
        MilitaryTier newTier = MilitaryTier.assess(s, newFort);
        List<String> diffs = new ArrayList<>();
        diff(diffs, "tier", tier, newTier);
        diff(diffs, "garrison", garrison, s.garrison());
        diff(diffs, "population", population, s.population());
        diff(diffs, "defendingStrength", defendingStrength, s.defendingStrength());
        diff(diffs, "fortification", fortification, newFort);
        diff(diffs, "wallSegments", wallSegments, s.wallSegments());
        diff(diffs, "defensiveBuildings", defensiveBuildings, s.defensiveBuildings());
        diff(diffs, "tags", tagCounts, s.tagCounts());
        diff(diffs, "name", name, s.name());

        name = s.name();
        culture = s.culture();
        type = s.type();
        center = s.center();
        tier = newTier;
        population = s.population();
        adults = s.adults();
        garrison = s.garrison();
        defendingStrength = s.defendingStrength();
        fortification = newFort;
        tagCounts = new LinkedHashMap<>(s.tagCounts());
        wallSegments = s.wallSegments();
        wallSegmentsPending = s.wallSegmentsPending();
        wallTowers = s.wallTowers();
        defensiveBuildings = s.defensiveBuildings();
        militaryPlans = new ArrayList<>(s.militaryPlans());
        townhallPlan = s.townhallPlan();
        buildingsOperational = s.buildingsOperational();
        lastUpdateTick = tick;
        updateCount++;
        return diffs;
    }

    private static void diff(List<String> out, String field, Object before, Object after) {
        if (!before.equals(after)) {
            out.add(field + " " + before + " -> " + after);
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("village", villageId);
        t.putUUID("faction", factionId);
        t.putString("name", name);
        t.putString("culture", culture);
        t.putString("type", type);
        t.putLong("center", center.asLong());
        t.putString("tier", tier.name());
        t.putInt("population", population);
        t.putInt("adults", adults);
        t.putInt("garrison", garrison);
        t.putInt("defendingStrength", defendingStrength);
        t.putInt("fortification", fortification);
        CompoundTag tags = new CompoundTag();
        tagCounts.forEach(tags::putInt);
        t.put("tagCounts", tags);
        t.putInt("wallSegments", wallSegments);
        t.putInt("wallSegmentsPending", wallSegmentsPending);
        t.putInt("wallTowers", wallTowers);
        t.putInt("defensiveBuildings", defensiveBuildings);
        ListTag plans = new ListTag();
        militaryPlans.forEach(p -> plans.add(StringTag.valueOf(p)));
        t.put("militaryPlans", plans);
        t.putString("townhallPlan", townhallPlan);
        t.putInt("buildingsOperational", buildingsOperational);
        t.putLong("firstSeenTick", firstSeenTick);
        t.putLong("lastUpdateTick", lastUpdateTick);
        t.putInt("updateCount", updateCount);
        t.putInt("loadedResidents", loadedResidents);
        t.putInt("markedResidents", markedResidents);
        return t;
    }

    public static VillageRecord load(CompoundTag t) {
        VillageRecord r = new VillageRecord(t.getUUID("village"), t.getUUID("faction"));
        r.name = t.getString("name");
        r.culture = t.getString("culture");
        r.type = t.getString("type");
        r.center = BlockPos.of(t.getLong("center"));
        r.tier = MilitaryTier.parse(t.getString("tier"));
        r.population = t.getInt("population");
        r.adults = t.getInt("adults");
        r.garrison = t.getInt("garrison");
        r.defendingStrength = t.getInt("defendingStrength");
        r.fortification = t.getInt("fortification");
        CompoundTag tags = t.getCompound("tagCounts");
        for (String k : tags.getAllKeys()) {
            r.tagCounts.put(k, tags.getInt(k));
        }
        r.wallSegments = t.getInt("wallSegments");
        r.wallSegmentsPending = t.getInt("wallSegmentsPending");
        r.wallTowers = t.getInt("wallTowers");
        r.defensiveBuildings = t.getInt("defensiveBuildings");
        ListTag plans = t.getList("militaryPlans", Tag.TAG_STRING);
        for (int i = 0; i < plans.size(); i++) {
            r.militaryPlans.add(plans.getString(i));
        }
        r.townhallPlan = t.getString("townhallPlan");
        r.buildingsOperational = t.getInt("buildingsOperational");
        r.firstSeenTick = t.getLong("firstSeenTick");
        r.lastUpdateTick = t.getLong("lastUpdateTick");
        r.updateCount = t.getInt("updateCount");
        r.loadedResidents = t.getInt("loadedResidents");
        r.markedResidents = t.getInt("markedResidents");
        return r;
    }
}

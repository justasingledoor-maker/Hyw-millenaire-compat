package dev.hywmill.settlement;

import dev.hywmill.classify.BuildingRole;
import dev.hywmill.classify.VillagerRole;
import dev.hywmill.fortification.FortificationScore;
import dev.hywmill.military.MilitaryTier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One village's entry in our own ledger. Never written into Millénaire's files.
 *
 * <p>Format history: 1 = M1 (patrol-tag/keyword classification); 2 = M1.1 (role tables). Records
 * loaded from an older format keep their identity and history but are flagged
 * {@link #needsRecompute}, so their derived values are recomputed at the next update even if
 * the village is inactive.
 */
public final class VillageRecord {
    public static final int FORMAT = 2;

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
    public Map<VillagerRole, Integer> villagerRoles = new EnumMap<>(VillagerRole.class);
    public Map<BuildingRole, Integer> buildingRoles = new EnumMap<>(BuildingRole.class);
    public Map<String, Integer> tagCounts = new LinkedHashMap<>();
    public int wallSegmentsPending;
    public List<String> ambiguousTypes = new ArrayList<>();
    public String townhallPlan = "";
    public int buildingsOperational;
    public long firstSeenTick;
    public long lastUpdateTick;
    public int updateCount;
    public int loadedResidents;
    public int markedResidents;
    /** Not persisted: set when loaded from an older format. */
    public boolean needsRecompute;

    public VillageRecord(UUID villageId, UUID factionId) {
        this.villageId = villageId;
        this.factionId = factionId;
    }

    /**
     * Applies a fresh snapshot and returns human-readable differences for fields that matter
     * (empty on no change). Tier and fortification are derived here.
     */
    public List<String> apply(SettlementSnapshot s, long tick) {
        int newFort = FortificationScore.compute(s.buildingRoles());
        MilitaryTier newTier = MilitaryTier.assess(s.villagerRoles(), s.buildingRoles(), newFort);
        List<String> diffs = new ArrayList<>();
        diff(diffs, "tier", tier, newTier);
        diff(diffs, "garrison", garrison, s.garrison());
        diff(diffs, "fortification", fortification, newFort);
        diff(diffs, "population", population, s.population());
        diff(diffs, "defendingStrength", defendingStrength, s.defendingStrength());
        diff(diffs, "villagerRoles", villagerRoles, s.villagerRoles());
        diff(diffs, "buildingRoles", buildingRoles, s.buildingRoles());
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
        villagerRoles = copy(VillagerRole.class, s.villagerRoles());
        buildingRoles = copy(BuildingRole.class, s.buildingRoles());
        tagCounts = new LinkedHashMap<>(s.tagCounts());
        wallSegmentsPending = s.wallSegmentsPending();
        ambiguousTypes = new ArrayList<>(s.ambiguousTypes());
        townhallPlan = s.townhallPlan();
        buildingsOperational = s.buildingsOperational();
        lastUpdateTick = tick;
        updateCount++;
        needsRecompute = false;
        return diffs;
    }

    private static <E extends Enum<E>> Map<E, Integer> copy(Class<E> type, Map<E, Integer> in) {
        Map<E, Integer> out = new EnumMap<>(type);
        out.putAll(in);
        return out;
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
        t.put("villagerRoles", saveEnumCounts(villagerRoles));
        t.put("buildingRoles", saveEnumCounts(buildingRoles));
        CompoundTag tags = new CompoundTag();
        tagCounts.forEach(tags::putInt);
        t.put("tagCounts", tags);
        t.putInt("wallSegmentsPending", wallSegmentsPending);
        ListTag ambiguous = new ListTag();
        ambiguousTypes.forEach(a -> ambiguous.add(StringTag.valueOf(a)));
        t.put("ambiguousTypes", ambiguous);
        t.putString("townhallPlan", townhallPlan);
        t.putInt("buildingsOperational", buildingsOperational);
        t.putLong("firstSeenTick", firstSeenTick);
        t.putLong("lastUpdateTick", lastUpdateTick);
        t.putInt("updateCount", updateCount);
        t.putInt("loadedResidents", loadedResidents);
        t.putInt("markedResidents", markedResidents);
        return t;
    }

    private static <E extends Enum<E>> CompoundTag saveEnumCounts(Map<E, Integer> m) {
        CompoundTag t = new CompoundTag();
        m.forEach((k, v) -> t.putInt(k.name(), v));
        return t;
    }

    private static <E extends Enum<E>> Map<E, Integer> loadEnumCounts(Class<E> type, CompoundTag t) {
        Map<E, Integer> out = new EnumMap<>(type);
        for (String k : t.getAllKeys()) {
            try {
                out.put(Enum.valueOf(type, k), t.getInt(k));
            } catch (IllegalArgumentException ignored) {
                // role removed in a later version: drop it, the next update recomputes
            }
        }
        return out;
    }

    /**
     * @param format the ledger format the tag was written with. Format 1 fields that no longer
     *               exist (wallSegments, wallTowers, defensiveBuildings, militaryPlans) are dropped.
     */
    public static VillageRecord load(CompoundTag t, int format) {
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
        r.wallSegmentsPending = t.getInt("wallSegmentsPending");
        r.townhallPlan = t.getString("townhallPlan");
        r.buildingsOperational = t.getInt("buildingsOperational");
        r.firstSeenTick = t.getLong("firstSeenTick");
        r.lastUpdateTick = t.getLong("lastUpdateTick");
        r.updateCount = t.getInt("updateCount");
        r.loadedResidents = t.getInt("loadedResidents");
        r.markedResidents = t.getInt("markedResidents");
        if (format >= 2) {
            r.villagerRoles = loadEnumCounts(VillagerRole.class, t.getCompound("villagerRoles"));
            r.buildingRoles = loadEnumCounts(BuildingRole.class, t.getCompound("buildingRoles"));
            ListTag ambiguous = t.getList("ambiguousTypes", Tag.TAG_STRING);
            for (int i = 0; i < ambiguous.size(); i++) {
                r.ambiguousTypes.add(ambiguous.getString(i));
            }
        } else {
            r.needsRecompute = true;
        }
        return r;
    }
}

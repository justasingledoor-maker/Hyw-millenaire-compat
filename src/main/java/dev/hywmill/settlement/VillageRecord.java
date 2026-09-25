package dev.hywmill.settlement;

import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.military.classify.BuildingRole;
import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.classify.RoleTables;
import dev.hywmill.military.defense.DefenseStats;
import dev.hywmill.military.doctrine.DoctrineDefaults;
import dev.hywmill.military.doctrine.DoctrineOverride;
import dev.hywmill.military.doctrine.DoctrineResolver;
import dev.hywmill.military.doctrine.DoctrinePatch;
import dev.hywmill.military.profile.MilitaryProfile;
import dev.hywmill.military.profile.ProfileCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One village's entry in our own ledger. Never written into Millénaire's files.
 *
 * <p>Format history: 1 = M1 (patrol-tag/keyword classification); 2 = M1.1 (role tables);
 * 3 = M2 (military profile, controller, doctrine override, defense statistics). Records loaded
 * from an older format keep their identity, history and (from format 2) their role counts, and
 * are flagged {@link #needsRecompute}, so their derived values are recomputed at the next update
 * even if the village is inactive. New fields start empty; nothing is discarded.
 */
public final class VillageRecord {
    public static final int FORMAT = 4;

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
    // ---- format 3 (M2) ----
    public int capacity;
    public int readiness;
    /** -1 until measured on loaded defenders; then the last measured value. */
    public double equipmentScore = -1;
    public int villageRadius;
    public boolean loneBuilding;
    /** Controlling player of a player-controlled village. Never replaces {@link #factionId}. */
    @Nullable public UUID controllerPlayerId;
    public DoctrinePatch doctrineOverride = DoctrinePatch.EMPTY;
    public DefenseStats stats = new DefenseStats();
    /** Not persisted: set when loaded from an older format. */
    // ---- format 4 (M3) ----
    /**
     * The persistent HYW garrison. Null until the village's first garrison slot after it was
     * created or migrated from format 3; then an empty roster (startingGranted=false, levy 0,
     * lastAccrualTick = that tick) is created. {@link #garrison} stays the Millénaire soldier count.
     */
    @Nullable public GarrisonRoster hywRoster;

    public boolean needsRecompute;
    /** Not persisted: resolved-doctrine cache (see GarrisonUpdater.resolveDoctrine). */
    public DoctrineResolver.Resolved cachedDoctrine;
    public DoctrineDefaults cachedDoctrineDefaults;
    public DoctrineResolver.Context cachedDoctrineContext;
    public DoctrinePatch cachedDoctrineOverride;
    /** Not persisted: override entries dropped at load (reported by the ledger). */
    public List<String> overrideProblems = List.of();

    public VillageRecord(UUID villageId, UUID factionId) {
        this.villageId = villageId;
        this.factionId = factionId;
    }

    /**
     * Applies a fresh snapshot and returns human-readable differences for fields that matter
     * (empty on no change). Tier and fortification are derived here.
     */
    public List<String> apply(SettlementSnapshot s, long tick) {
        MilitaryProfile p = ProfileCalculator.compute(s.villagerRoles(), s.buildingRoles(), s.buildingSlots(), s.loadedGear(),
                RoleTables.current());
        int newFort = p.fortification();
        MilitaryTier newTier = p.tier();
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
        diff(diffs, "capacity", capacity, p.capacity());
        diff(diffs, "controller", String.valueOf(controllerPlayerId), String.valueOf(s.controllerId()));

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
        capacity = p.capacity();
        readiness = p.readiness();
        if (p.equipmentScore() >= 0) {
            equipmentScore = p.equipmentScore();
        }
        villageRadius = s.villageRadius();
        loneBuilding = s.loneBuilding();
        controllerPlayerId = s.controllerId();
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
        t.putInt("capacity", capacity);
        t.putInt("readiness", readiness);
        t.putDouble("equipmentScore", equipmentScore);
        t.putInt("villageRadius", villageRadius);
        t.putBoolean("loneBuilding", loneBuilding);
        if (controllerPlayerId != null) {
            t.putUUID("controller", controllerPlayerId);
        }
        t.put("doctrineOverride", DoctrineOverride.save(doctrineOverride));
        t.put("stats", stats.save());
        if (hywRoster != null) {
            t.put("hywRoster", hywRoster.save());
        }
        return t;
    }

    /** The profile as last computed (for commands); equipment -1 means "never measured". */
    public MilitaryProfile profile() {
        int soldiers = villagerRoles.getOrDefault(VillagerRole.SOLDIER, 0);
        int militia = villagerRoles.getOrDefault(VillagerRole.MILITIA, 0);
        int leaders = villagerRoles.getOrDefault(VillagerRole.LEADER, 0);
        Map<BuildingRole, Integer> infra = new EnumMap<>(BuildingRole.class);
        buildingRoles.forEach((r, c) -> {
            if (r != BuildingRole.WALL && r != BuildingRole.TOWER && r != BuildingRole.GATE && r != BuildingRole.BORDER_MARKER
                    && r != BuildingRole.NONE && c > 0) {
                infra.put(r, c);
            }
        });
        return new MilitaryProfile(soldiers, militia, leaders, soldiers + militia + leaders,
                villagerRoles.getOrDefault(VillagerRole.OUTLAW, 0), villagerRoles.getOrDefault(VillagerRole.CIVILIAN, 0),
                capacity, readiness, equipmentScore, -1, fortification, tier, buildingRoles, infra);
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
        if (format >= 3) {
            r.capacity = t.getInt("capacity");
            r.readiness = t.getInt("readiness");
            r.equipmentScore = t.contains("equipmentScore") ? t.getDouble("equipmentScore") : -1;
            r.villageRadius = t.getInt("villageRadius");
            r.loneBuilding = t.getBoolean("loneBuilding");
            r.controllerPlayerId = t.hasUUID("controller") ? t.getUUID("controller") : null;
            List<String> problems = new ArrayList<>();
            r.doctrineOverride = DoctrineOverride.load(t.getCompound("doctrineOverride"), problems);
            r.overrideProblems = problems;
            r.stats = DefenseStats.load(t.getCompound("stats"));
        } else {
            r.needsRecompute = true;
        }
        if (format >= 4 && t.contains("hywRoster", Tag.TAG_COMPOUND)) {
            r.hywRoster = GarrisonRoster.load(t.getCompound("hywRoster"), r.lastUpdateTick);
        }
        return r;
    }
}

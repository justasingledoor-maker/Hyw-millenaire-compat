package dev.hywmill.garrison;

import dev.hywmill.garrison.tables.GarrisonTable;
import dev.hywmill.garrison.tables.TierRule;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.classify.BuildingRole;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure recruitment rules: garrison target, levy accrual, unit choice, starting grant, cooldowns.
 * All inputs are explicit; nothing here reads the world.
 */
public final class Recruitment {
    /** HYW entity types M3 may use at all (gunpowder only if a datapack enables it). */
    public static final Set<String> M3_ENTITY_TYPES = Set.of(
            "hundred_years_war:militia", "hundred_years_war:spear_man", "hundred_years_war:shieldman",
            "hundred_years_war:warrior", "hundred_years_war:archer", "hundred_years_war:crossbowman",
            "hundred_years_war:handgonne_man", "hundred_years_war:matchlock_man");
    /** M4: light mounted HYW riders (class CAVALRY, scouts). They manage their own HYW horse; the rider is the roster unit. */
    public static final Set<String> M4_CAVALRY_TYPES = Set.of(
            "hundred_years_war:mounted_light_lancer_rider", "hundred_years_war:mounted_archer_rider",
            // post-M5 squads: the heavy lancer (knights, cataphracts); in no garrison composition
            "hundred_years_war:mounted_lancer_rider");

    /** Every HYW entity type a garrison may use (M3 set plus the M4 riders). */
    public static boolean allowedType(String entityType) {
        return M3_ENTITY_TYPES.contains(entityType) || M4_CAVALRY_TYPES.contains(entityType);
    }

    public static final long DAY = 24000L;
    /** Wipe-out: at least this share of the target killed during one alert. */
    public static final double WIPEOUT_SHARE = 0.75;

    private Recruitment() {}

    /**
     * Inputs of the garrison target, all from the village record (M5-G).
     *
     * @param capacity      M2 military capacity (soldier/militia slots)
     * @param adults        adult residents
     * @param population    all residents
     * @param buildings     operational buildings per role
     * @param fortification fortification score
     * @param type          Millénaire village type key
     */
    public record TargetInputs(int capacity, int adults, int population, Map<BuildingRole, Integer> buildings, int fortification, String type) {
        /** Capacity only: what M3 used. */
        public static TargetInputs capacityOnly(int capacity) {
            return new TargetInputs(capacity, 0, 0, Map.of(), 0, "");
        }
    }

    /**
     * {@code clamp(round(capacity * perCapacity), minTarget, maxTarget)}, then at most the tier's
     * per-village maximum. NONE tier and lone buildings: 0. (M3 signature; same as the M5-G formula
     * with capacity as the only input.)
     */
    public static int target(int capacity, MilitaryTier tier, boolean loneBuilding, GarrisonTable table) {
        return target(TargetInputs.capacityOnly(capacity), tier, loneBuilding, table);
    }

    /**
     * M5-G garrison target:
     * <pre>
     * raw    = capacity * perCapacity + sum(infraBonus[role] * buildings[role])
     *        + min(fortification / fortDiv, fortCap) + levyShare * adults
     * target = min(maxUnits, clamp(round(raw * typeFactor[type]), minTarget, maxTarget))
     * target = min(target, floor(supportRatio * population))   only when supportRatio > 0
     * </pre>
     * With every M5-G term neutral (no infraBonus, fortDiv 0, levyShare 0, no type factor,
     * supportRatio 0) this is exactly the M3 target. NONE tier and lone buildings: 0.
     */
    public static int target(TargetInputs in, MilitaryTier tier, boolean loneBuilding, GarrisonTable table) {
        if (tier == MilitaryTier.NONE || loneBuilding) {
            return 0;
        }
        TierRule r = table.tier(tier);
        double raw = in.capacity() * r.perCapacity();
        for (Map.Entry<BuildingRole, Integer> e : in.buildings().entrySet()) {
            raw += table.infraBonus().getOrDefault(e.getKey(), 0.0) * e.getValue();
        }
        if (r.fortDiv() > 0) {
            raw += Math.min(in.fortification() / r.fortDiv(), r.fortCap());
        }
        raw += r.levyShare() * in.adults();
        raw *= table.typeFactors().getOrDefault(in.type(), 1.0);
        long rounded = Math.round(raw);
        int t = (int) Math.max(r.minTarget(), Math.min(r.maxTarget(), rounded));
        t = Math.max(0, Math.min(t, r.maxUnits()));
        if (r.supportRatio() > 0) {
            t = Math.min(t, (int) Math.floor(r.supportRatio() * in.population() + 1e-9));
        }
        return t;
    }

    /** M3 levy rate (no target term). */
    public static double dailyRate(int capacity, MilitaryTier tier, GarrisonTable table) {
        return dailyRate(capacity, 0, tier, table);
    }

    /** Levy points per active day: {@code baseDaily + perCapacityDaily * capacity + perTargetDaily * target}. */
    public static double dailyRate(int capacity, int target, MilitaryTier tier, GarrisonTable table) {
        TierRule r = table.tier(tier);
        return r.baseDaily() + table.perCapacityDaily() * capacity + r.perTargetDaily() * target;
    }

    /** Levy pool cap: {@code max(poolCap, poolCapShare * target)}. */
    public static double poolCap(int target, MilitaryTier tier, GarrisonTable table) {
        TierRule r = table.tier(tier);
        return Math.max(r.poolCap(), r.poolCapShare() * target);
    }

    /**
     * Adds levy for the active time since the last accrual. The step is capped at
     * {@code maxStep} ticks, so time the village spent inactive (unloaded) never accrues: there is
     * no offline catch-up. Points are capped at the tier's pool cap.
     */
    public static void accrue(GarrisonRoster r, long tick, long maxStep, double dailyRate, double cap) {
        long gap = Math.max(0, tick - r.lastAccrualTick);
        long step = Math.min(gap, maxStep);
        r.lastAccrualTick = tick;
        if (r.levyPoints > cap) {
            r.levyPoints = cap;
        }
        r.levyPoints = Math.min(cap, r.levyPoints + dailyRate * step / (double) DAY);
    }

    /**
     * Whether a village of this tier may have this unit at all: enabled, an M3 entity type, the
     * tier reaches the unit's minTier, and the tier allows its class (e.g. WATCH: LEVY/RANGED only).
     */
    public static boolean allowedAtTier(UnitSpec u, MilitaryTier tier, GarrisonTable table) {
        return u.enabled() && allowedType(u.entityType()) && tier.ordinal() >= u.minTier().ordinal()
                && table.tier(tier).classes().contains(u.unitClass());
    }

    /** Units a village of this tier may recruit, from the table's composition, in composition order. */
    public static List<UnitSpec> eligibleUnits(MilitaryTier tier, GarrisonTable table, Map<String, UnitSpec> units) {
        List<UnitSpec> out = new ArrayList<>();
        for (String key : table.composition().keySet()) {
            UnitSpec u = units.get(key);
            if (u != null && allowedAtTier(u, tier, table)) {
                out.add(u);
            }
        }
        return out;
    }

    /**
     * Deterministic balanced choice: the eligible unit whose live count is furthest below its
     * weighted share of the next garrison size; ties broken by a hash of (village, seq, key).
     * Returns null if nothing is eligible.
     */
    @Nullable
    public static UnitSpec chooseUnit(UUID villageId, int seq, List<UnitSpec> eligible, Map<String, Integer> weights,
                                      Map<String, Integer> liveCounts) {
        if (eligible.isEmpty()) {
            return null;
        }
        int total = 0;
        double weightSum = 0;
        for (UnitSpec u : eligible) {
            total += liveCounts.getOrDefault(u.key(), 0);
            weightSum += weights.getOrDefault(u.key(), 0);
        }
        if (weightSum <= 0) {
            return null;
        }
        final int next = total + 1;
        final double ws = weightSum;
        Map<String, Double> deficit = new HashMap<>();
        for (UnitSpec u : eligible) {
            deficit.put(u.key(), weights.getOrDefault(u.key(), 0) / ws * next - liveCounts.getOrDefault(u.key(), 0));
        }
        return eligible.stream()
                .filter(u -> weights.getOrDefault(u.key(), 0) > 0)
                .max(Comparator.<UnitSpec>comparingDouble(u -> deficit.get(u.key()))
                        .thenComparingLong(u -> -tieBreak(villageId, seq, u.key())))
                .orElse(null);
    }

    static long tieBreak(UUID villageId, int seq, String key) {
        return UUID.nameUUIDFromBytes((villageId + ":" + seq + ":" + key).getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
    }

    /** Live (non-terminal) entries per unit key. */
    public static Map<String, Integer> liveCounts(GarrisonRoster r) {
        Map<String, Integer> m = new HashMap<>();
        for (RosterEntry e : r.entries()) {
            if (!e.state().terminal()) {
                m.merge(e.unitKey, 1, Integer::sum);
            }
        }
        return m;
    }

    public static int startingGrant(int target, double fraction) {
        return target <= 0 ? 0 : (int) Math.ceil(target * fraction - 1e-9);
    }

    /**
     * The one-time free starting garrison. Issued only once, the first time the village has a
     * positive target; {@code startingGranted} is never reset. Returns the new entries.
     */
    public static List<RosterEntry> grantStarting(GarrisonRoster r, UUID villageId, int target, MilitaryTier tier, GarrisonTable table,
                                                  Map<String, UnitSpec> units, int equipmentLevel, long tick) {
        if (r.startingGranted || target <= 0) {
            return List.of();
        }
        List<UnitSpec> eligible = eligibleUnits(tier, table, units);
        if (eligible.isEmpty()) {
            return List.of();
        }
        int n = Math.min(startingGrant(target, table.startingFraction()), Math.max(0, target - r.live()));
        List<RosterEntry> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UnitSpec u = chooseUnit(villageId, r.nextSeq, eligible, table.composition(), liveCounts(r));
            if (u == null) {
                break;
            }
            out.add(r.recruit(villageId, u.key(), u.entityType(), equipmentLevel, tick, false));
        }
        r.startingGranted = true;
        return out;
    }

    public enum Blocker { NONE, DISABLED, PAUSED, NOT_CALM, TARGET_REACHED, TIER_CAP, INTERVAL, NO_UNIT, POINTS }

    /** Why the village cannot recruit now (NONE: it can). {@code unit} is the unit it would recruit. */
    public static Blocker blocker(GarrisonRoster r, boolean enabled, boolean calm, int target, int tierMax, long tick, long recruitInterval,
                                  @Nullable UnitSpec unit) {
        if (!enabled) {
            return Blocker.DISABLED;
        }
        if (r.paused) {
            return Blocker.PAUSED;
        }
        if (!calm) {
            return Blocker.NOT_CALM;
        }
        int live = r.live();
        if (live >= tierMax) {
            return Blocker.TIER_CAP;
        }
        if (live >= target) {
            return Blocker.TARGET_REACHED;
        }
        if (tick < r.lastRecruitTick + recruitInterval) {
            return Blocker.INTERVAL;
        }
        if (unit == null) {
            return Blocker.NO_UNIT;
        }
        if (r.levyPoints + 1e-9 < unit.cost()) {
            return Blocker.POINTS;
        }
        return Blocker.NONE;
    }

    /** Recruits {@code unit} (paid): spends points, stamps the recruit time. */
    public static RosterEntry recruitPaid(GarrisonRoster r, UUID villageId, UnitSpec unit, int equipmentLevel, long tick) {
        r.levyPoints = Math.max(0, r.levyPoints - unit.cost());
        r.lastRecruitTick = tick;
        return r.recruit(villageId, unit.key(), unit.entityType(), equipmentLevel, tick, true);
    }

    /** Delays the next recruit so it happens no earlier than {@code tick + cooldown}. */
    public static void cooldown(GarrisonRoster r, long tick, long cooldown, long recruitInterval) {
        r.lastRecruitTick = Math.max(r.lastRecruitTick, tick + cooldown - recruitInterval);
    }

    public static boolean wipedOut(int deathsThisAlert, int target) {
        return target > 0 && deathsThisAlert >= Math.ceil(WIPEOUT_SHARE * target - 1e-9);
    }

    /** Requested equipment level for the tier (the provider may clamp it). */
    public static int equipmentLevel(MilitaryTier tier, GarrisonTable table) {
        return table.tier(tier).equipmentLevel();
    }

    /** Class of a unit key (for display), or null. */
    @Nullable
    public static UnitClass classOf(String key, Map<String, UnitSpec> units) {
        UnitSpec u = units.get(key);
        return u == null ? null : u.unitClass();
    }
}

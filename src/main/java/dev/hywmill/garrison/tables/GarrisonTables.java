package dev.hywmill.garrison.tables;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hywmill.military.MilitaryTier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Garrison data: unit catalogue plus defaults and per-culture patches, merged from
 * {@code data/<ns>/hywmill_garrison/*.json} in resource-location order (later files override
 * earlier ones key by key). Every unit entity type is validated against the live registry;
 * invalid entries are reported and skipped, never guessed.
 *
 * <p>File shape (any file may contain any of these sections):
 * <pre>
 * { "units":    { key: { "entity", "class", "cost", "minTier", "enabled" } },
 *   "defaults": { "tiers": { TIER: {...TierRule fields...} }, "perCapacityDaily", "startingFraction",
 *                 "commitPerThreat", "composition": { key: weight }, "equipmentProvider" },
 *   "cultures": { "millenaire:norman": { same fields as defaults; composition replaces, tiers merge per field } } }
 * </pre>
 */
public final class GarrisonTables {
    /** Unit classes allowed to M3 (mounted/siege/worker units have no class and cannot be declared). */
    public static final Set<UnitClass> ALL_CLASSES = EnumSet.allOf(UnitClass.class);

    private static volatile GarrisonTables current = empty();

    private final Map<String, UnitSpec> units;
    private final GarrisonTable defaults;
    private final Map<String, GarrisonTable> cultures;

    private GarrisonTables(Map<String, UnitSpec> units, GarrisonTable defaults, Map<String, GarrisonTable> cultures) {
        this.units = units;
        this.defaults = defaults;
        this.cultures = cultures;
    }

    public static GarrisonTables current() {
        return current;
    }

    public static void set(GarrisonTables t) {
        current = t;
    }

    public static GarrisonTables empty() {
        Map<MilitaryTier, TierRule> tiers = new EnumMap<>(MilitaryTier.class);
        for (MilitaryTier t : MilitaryTier.values()) {
            tiers.put(t, TierRule.NONE);
        }
        return new GarrisonTables(Map.of(), new GarrisonTable(tiers, 0, 0, 0, Map.of(), "hyw"), Map.of());
    }

    public Map<String, UnitSpec> units() {
        return units;
    }

    public GarrisonTable defaults() {
        return defaults;
    }

    public Map<String, GarrisonTable> cultures() {
        return cultures;
    }

    public GarrisonTable forCulture(String culture) {
        return cultures.getOrDefault(culture, defaults);
    }

    // ---- parsing ----

    public static GarrisonTables fromJson(List<JsonObject> files, Predicate<String> validEntityType, List<String> problems) {
        Map<String, UnitSpec> units = new LinkedHashMap<>();
        JsonObject defaults = new JsonObject();
        Map<String, JsonObject> culturePatches = new LinkedHashMap<>();
        for (JsonObject f : files) {
            if (f.has("units") && f.get("units").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : f.getAsJsonObject("units").entrySet()) {
                    UnitSpec u = parseUnit(e.getKey(), e.getValue(), validEntityType, problems);
                    if (u != null) {
                        units.put(u.key(), u);
                    } else {
                        units.remove(e.getKey());
                    }
                }
            }
            if (f.has("defaults") && f.get("defaults").isJsonObject()) {
                merge(defaults, f.getAsJsonObject("defaults"));
            }
            if (f.has("cultures") && f.get("cultures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : f.getAsJsonObject("cultures").entrySet()) {
                    if (e.getValue().isJsonObject()) {
                        merge(culturePatches.computeIfAbsent(e.getKey(), k -> new JsonObject()), e.getValue().getAsJsonObject());
                    } else {
                        problems.add("culture " + e.getKey() + ": not an object");
                    }
                }
            }
        }
        GarrisonTable base = table(defaults, null, units, "defaults", problems);
        Map<String, GarrisonTable> cultures = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : culturePatches.entrySet()) {
            cultures.put(e.getKey(), table(e.getValue(), base, units, "culture " + e.getKey(), problems));
        }
        return new GarrisonTables(Collections.unmodifiableMap(units), base, Collections.unmodifiableMap(cultures));
    }

    /** Deep-merges objects ("tiers" per tier and field); "composition" and scalars replace. */
    private static void merge(JsonObject into, JsonObject from) {
        for (Map.Entry<String, JsonElement> e : from.entrySet()) {
            if (e.getKey().equals("tiers") && e.getValue().isJsonObject()) {
                JsonObject tiers = into.has("tiers") && into.get("tiers").isJsonObject() ? into.getAsJsonObject("tiers") : new JsonObject();
                for (Map.Entry<String, JsonElement> t : e.getValue().getAsJsonObject().entrySet()) {
                    if (t.getValue().isJsonObject()) {
                        JsonObject dst = tiers.has(t.getKey()) && tiers.get(t.getKey()).isJsonObject()
                                ? tiers.getAsJsonObject(t.getKey()) : new JsonObject();
                        t.getValue().getAsJsonObject().entrySet().forEach(x -> dst.add(x.getKey(), x.getValue()));
                        tiers.add(t.getKey(), dst);
                    }
                }
                into.add("tiers", tiers);
            } else {
                into.add(e.getKey(), e.getValue());
            }
        }
    }

    private static UnitSpec parseUnit(String key, JsonElement el, Predicate<String> valid, List<String> problems) {
        if (!el.isJsonObject()) {
            problems.add("unit " + key + ": not an object");
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        try {
            String entity = o.get("entity").getAsString();
            if (!valid.test(entity)) {
                problems.add("unit " + key + ": entity type " + entity + " is not a registered HYW unit; skipped");
                return null;
            }
            UnitClass cls = UnitClass.valueOf(o.get("class").getAsString());
            double cost = o.get("cost").getAsDouble();
            if (cost <= 0) {
                problems.add("unit " + key + ": cost must be > 0; skipped");
                return null;
            }
            MilitaryTier minTier = o.has("minTier") ? MilitaryTier.valueOf(o.get("minTier").getAsString()) : MilitaryTier.WATCH;
            boolean enabled = !o.has("enabled") || o.get("enabled").getAsBoolean();
            return new UnitSpec(key, entity, cls, cost, minTier, enabled);
        } catch (RuntimeException ex) {
            problems.add("unit " + key + ": " + ex.getMessage() + "; skipped");
            return null;
        }
    }

    private static GarrisonTable table(JsonObject o, GarrisonTable base, Map<String, UnitSpec> units, String where, List<String> problems) {
        Map<MilitaryTier, TierRule> tiers = new EnumMap<>(MilitaryTier.class);
        JsonObject tj = o.has("tiers") && o.get("tiers").isJsonObject() ? o.getAsJsonObject("tiers") : new JsonObject();
        for (MilitaryTier t : MilitaryTier.values()) {
            TierRule b = base != null ? base.tier(t) : TierRule.NONE;
            tiers.put(t, t == MilitaryTier.NONE ? TierRule.NONE
                    : tj.has(t.name()) ? tierRule(tj.getAsJsonObject(t.name()), b, where + " tier " + t, problems) : b);
        }
        for (String k : tj.keySet()) {
            try {
                MilitaryTier.valueOf(k);
            } catch (IllegalArgumentException ex) {
                problems.add(where + ": unknown tier " + k + "; ignored");
            }
        }
        double perCapacityDaily = num(o, "perCapacityDaily", base != null ? base.perCapacityDaily() : 0, 0, 1000, where, problems);
        double startingFraction = num(o, "startingFraction", base != null ? base.startingFraction() : 0, 0, 1, where, problems);
        int commit = (int) num(o, "commitPerThreat", base != null ? base.commitPerThreat() : 0, 0, 64, where, problems);
        String provider = o.has("equipmentProvider") ? o.get("equipmentProvider").getAsString() : base != null ? base.equipmentProvider() : "hyw";
        Map<String, Integer> composition;
        if (o.has("composition") && o.get("composition").isJsonObject()) {
            composition = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("composition").entrySet()) {
                int w;
                try {
                    w = e.getValue().getAsInt();
                } catch (RuntimeException ex) {
                    problems.add(where + ": composition weight of " + e.getKey() + " is not an integer; skipped");
                    continue;
                }
                if (!units.containsKey(e.getKey())) {
                    problems.add(where + ": composition names unknown or invalid unit " + e.getKey() + "; skipped");
                } else if (w > 0) {
                    composition.put(e.getKey(), w);
                }
            }
            composition = Collections.unmodifiableMap(composition);
        } else {
            composition = base != null ? base.composition() : Map.of();
        }
        return new GarrisonTable(Collections.unmodifiableMap(tiers), perCapacityDaily, startingFraction, commit, composition, provider);
    }

    private static TierRule tierRule(JsonObject o, TierRule b, String where, List<String> problems) {
        Set<UnitClass> classes = b.classes();
        if (o.has("classes") && o.get("classes").isJsonArray()) {
            List<UnitClass> list = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("classes")) {
                try {
                    list.add(UnitClass.valueOf(e.getAsString()));
                } catch (RuntimeException ex) {
                    problems.add(where + ": unknown class " + e + "; ignored");
                }
            }
            classes = list.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(list));
        }
        int maxUnits = (int) num(o, "maxUnits", b.maxUnits(), 0, 1024, where, problems);
        int minTarget = (int) num(o, "minTarget", b.minTarget(), 0, 1024, where, problems);
        int maxTarget = (int) num(o, "maxTarget", b.maxTarget(), 0, 1024, where, problems);
        return new TierRule(num(o, "perCapacity", b.perCapacity(), 0, 100, where, problems), minTarget, Math.max(minTarget, maxTarget),
                maxUnits, num(o, "baseDaily", b.baseDaily(), 0, 1000, where, problems), num(o, "poolCap", b.poolCap(), 0, 100000, where, problems),
                (int) num(o, "equipmentLevel", b.equipmentLevel(), 0, 3, where, problems), classes);
    }

    private static double num(JsonObject o, String key, double dflt, double min, double max, String where, List<String> problems) {
        if (!o.has(key)) {
            return dflt;
        }
        try {
            double v = o.get(key).getAsDouble();
            if (v < min || v > max || Double.isNaN(v)) {
                problems.add(where + ": " + key + "=" + v + " outside [" + min + ", " + max + "]; using " + dflt);
                return dflt;
            }
            return v;
        } catch (RuntimeException ex) {
            problems.add(where + ": " + key + " is not a number; using " + dflt);
            return dflt;
        }
    }
}

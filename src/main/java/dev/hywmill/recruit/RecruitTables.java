package dev.hywmill.recruit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.politics.Standing;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Muster Roll prices and rules ({@code data/<ns>/hywmill_recruitment/*.json}; files merge field by field, later wins).
 * Money is Millénaire's: 1 denier argent = 64 deniers, 1 denier or = 64 argent = 4096 deniers.
 *
 * @param mercs          mercenary unit key → price in denier argent (sold to anyone not in bad standing, light gear)
 * @param soldierBase    cultural soldier price in denier or: {@code (soldierBase + soldierPerCost × unit cost) × tierFactor}
 * @param soldierPerCost see {@code soldierBase}
 * @param tierFactor     price factor of the gear tier the soldier is equipped at
 * @param standingCap    best gear tier a standing unlocks (the village's own tier caps it too); absent = mercenaries only
 * @param discount       price discount (0..1) per standing
 * @param maxPerPurchase most units hired in one purchase
 * @param minRadius      spawn radius range the block's owner may set
 * @param maxRadius      see {@code minRadius}
 * @param useRange       a player must be within this many blocks of the block to use it
 * @param engines        post-M5 siege engines for sale (HYW entity id path, price in denier or, lowest standing), in
 *                       display order; engineer-operated ones come with an engineer, a battering ram is driven by the player
 * @param maxEngines     most engines in one purchase
 */
public record RecruitTables(Map<String, Double> mercs, double soldierBase, double soldierPerCost, Map<MilitaryTier, Double> tierFactor,
                            Map<Standing, MilitaryTier> standingCap, Map<Standing, Double> discount, int maxPerPurchase, int minRadius,
                            int maxRadius, double useRange, java.util.List<EngineOffer> engines, int maxEngines) {
    /** A siege engine for sale. */
    public record EngineOffer(String key, double priceOr, Standing minStanding) {}

    public RecruitTables(Map<String, Double> mercs, double soldierBase, double soldierPerCost, Map<MilitaryTier, Double> tierFactor,
                         Map<Standing, MilitaryTier> standingCap, Map<Standing, Double> discount, int maxPerPurchase, int minRadius,
                         int maxRadius, double useRange) {
        this(mercs, soldierBase, soldierPerCost, tierFactor, standingCap, discount, maxPerPurchase, minRadius, maxRadius, useRange,
                DEFAULT_ENGINES, 4);
    }

    public static final java.util.List<EngineOffer> DEFAULT_ENGINES = java.util.List.of(
            new EngineOffer("mangonels", 2.0, Standing.TRUSTED),
            new EngineOffer("trebuchets", 4.0, Standing.PATRON),
            new EngineOffer("battering_ram", 1.5, Standing.TRUSTED));

    public static final int DENIER_ARGENT = 64;
    public static final int DENIER_OR = 4096;

    public static final RecruitTables DEFAULT = new RecruitTables(
            orderedMap("militia", 3.0, "archer", 4.0, "crossbowman", 5.0), 0.5, 0.25,
            tiers(MilitaryTier.GUARD_POST, 1.0, MilitaryTier.GARRISON, 1.25, MilitaryTier.STRONGHOLD, 1.5),
            caps(), discounts(0.10, 0.20), 32, 2, 36, 8, DEFAULT_ENGINES, 4);

    private static volatile RecruitTables current = DEFAULT;

    public static RecruitTables current() {
        return current;
    }

    public static void set(RecruitTables t) {
        current = t;
    }

    private static Map<String, Double> orderedMap(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Double) kv[i + 1]);
        }
        return Map.copyOf(m);
    }

    private static Map<MilitaryTier, Double> tiers(Object... kv) {
        Map<MilitaryTier, Double> m = new EnumMap<>(MilitaryTier.class);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((MilitaryTier) kv[i], (Double) kv[i + 1]);
        }
        return Map.copyOf(m);
    }

    private static Map<Standing, MilitaryTier> caps() {
        Map<Standing, MilitaryTier> m = new EnumMap<>(Standing.class);
        m.put(Standing.TRUSTED, MilitaryTier.GUARD_POST);
        m.put(Standing.PATRON, MilitaryTier.GARRISON);
        m.put(Standing.SWORN, MilitaryTier.STRONGHOLD);
        return Map.copyOf(m);
    }

    private static Map<Standing, Double> discounts(double patron, double sworn) {
        Map<Standing, Double> m = new EnumMap<>(Standing.class);
        m.put(Standing.PATRON, patron);
        m.put(Standing.SWORN, sworn);
        return Map.copyOf(m);
    }

    /** Merges the files in order over {@link #DEFAULT}; bad entries are reported and ignored. */
    public static RecruitTables fromJson(List<JsonObject> files, List<String> problems) {
        Map<String, Double> mercs = new LinkedHashMap<>(DEFAULT.mercs);
        double base = DEFAULT.soldierBase, perCost = DEFAULT.soldierPerCost;
        Map<MilitaryTier, Double> tierFactor = new EnumMap<>(DEFAULT.tierFactor);
        Map<Standing, MilitaryTier> cap = new EnumMap<>(DEFAULT.standingCap);
        Map<Standing, Double> discount = new EnumMap<>(DEFAULT.discount);
        int max = DEFAULT.maxPerPurchase, minR = DEFAULT.minRadius, maxR = DEFAULT.maxRadius;
        double range = DEFAULT.useRange;
        java.util.List<EngineOffer> engines = DEFAULT.engines;
        int maxEngines = DEFAULT.maxEngines;
        for (JsonObject f : files) {
            try {
                if (f.has("mercs") && f.get("mercs").isJsonObject()) {
                    mercs.clear();
                    for (Map.Entry<String, JsonElement> e : f.getAsJsonObject("mercs").entrySet()) {
                        double v = e.getValue().getAsDouble();
                        if (v > 0) {
                            mercs.put(e.getKey(), v);
                        } else {
                            problems.add("mercs " + e.getKey() + ": price must be > 0; ignored");
                        }
                    }
                }
                base = f.has("soldierBase") ? f.get("soldierBase").getAsDouble() : base;
                perCost = f.has("soldierPerCost") ? f.get("soldierPerCost").getAsDouble() : perCost;
                if (f.has("tierFactor") && f.get("tierFactor").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : f.getAsJsonObject("tierFactor").entrySet()) {
                        tierFactor.put(MilitaryTier.valueOf(e.getKey()), e.getValue().getAsDouble());
                    }
                }
                if (f.has("standingCap") && f.get("standingCap").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : f.getAsJsonObject("standingCap").entrySet()) {
                        cap.put(Standing.valueOf(e.getKey()), MilitaryTier.valueOf(e.getValue().getAsString()));
                    }
                }
                if (f.has("discount") && f.get("discount").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : f.getAsJsonObject("discount").entrySet()) {
                        discount.put(Standing.valueOf(e.getKey()), Math.max(0, Math.min(0.9, e.getValue().getAsDouble())));
                    }
                }
                max = f.has("maxPerPurchase") ? Math.max(1, f.get("maxPerPurchase").getAsInt()) : max;
                minR = f.has("minRadius") ? Math.max(1, f.get("minRadius").getAsInt()) : minR;
                maxR = f.has("maxRadius") ? Math.max(minR, f.get("maxRadius").getAsInt()) : maxR;
                range = f.has("useRange") ? f.get("useRange").getAsDouble() : range;
                maxEngines = f.has("maxEngines") ? Math.max(1, f.get("maxEngines").getAsInt()) : maxEngines;
                if (f.has("engines") && f.get("engines").isJsonObject()) {
                    java.util.List<EngineOffer> list = new java.util.ArrayList<>();
                    for (Map.Entry<String, JsonElement> e : f.getAsJsonObject("engines").entrySet()) {
                        JsonObject o = e.getValue().getAsJsonObject();
                        double price = o.get("price").getAsDouble();
                        Standing st = o.has("standing") ? Standing.valueOf(o.get("standing").getAsString()) : Standing.TRUSTED;
                        if (price > 0) {
                            list.add(new EngineOffer(e.getKey(), price, st));
                        } else {
                            problems.add("engines " + e.getKey() + ": price must be > 0; ignored");
                        }
                    }
                    engines = java.util.List.copyOf(list);
                }
            } catch (RuntimeException ex) {
                problems.add("recruitment data: " + ex.getMessage() + "; the rest of that file is ignored");
            }
        }
        return new RecruitTables(Map.copyOf(mercs), base, perCost, Map.copyOf(tierFactor), Map.copyOf(cap), Map.copyOf(discount), max, minR,
                maxR, range, engines, maxEngines);
    }
}

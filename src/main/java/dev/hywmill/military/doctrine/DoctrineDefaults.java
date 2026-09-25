package dev.hywmill.military.doctrine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hywmill.military.MilitaryTier;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data-driven doctrine layers ({@code data/<ns>/hywmill_doctrine/*.json}):
 * baseline, per culture, lone buildings, per village type (exact id or {@code *} glob), and
 * per-tier modifiers. Files merge in resource-location order, field by field.
 *
 * <p>{@link #BUILTIN_BASELINE} is the approved global baseline; it is also the fallback for any
 * field the loaded baseline omits, so the resolved doctrine is always complete.
 */
public final class DoctrineDefaults {
    public static final DoctrinePatch BUILTIN_BASELINE;

    static {
        Map<DoctrineField, Object> b = new EnumMap<>(DoctrineField.class);
        b.put(DoctrineField.RADIUS_OFFSET, 16);
        b.put(DoctrineField.PROACTIVE, false);
        b.put(DoctrineField.COMMIT_PER_THREAT, 3);
        b.put(DoctrineField.RESERVE, 1);
        b.put(DoctrineField.MILITIA_POLICY, MilitiaPolicy.ON_ENGAGED);
        b.put(DoctrineField.SHELTER_RADIUS, 48);
        b.put(DoctrineField.ASSIST_PLAYERS, AssistMode.MIN_REPUTATION);
        b.put(DoctrineField.ASSIST_MIN_REPUTATION, 0);
        b.put(DoctrineField.ASSIST_PROVOKING_PLAYER, false);
        b.put(DoctrineField.ASSIST_CONTROLLER, ControllerAssist.ALWAYS);
        b.put(DoctrineField.ALERT_TICKS, 100);
        b.put(DoctrineField.ENGAGED_TICKS, 200);
        b.put(DoctrineField.RECOVERY_TICKS, 600);
        BUILTIN_BASELINE = new DoctrinePatch(b);
    }

    public static final DoctrineDefaults BUILTIN = new DoctrineDefaults(BUILTIN_BASELINE, Map.of(), DoctrinePatch.EMPTY, Map.of(), Map.of());

    private final DoctrinePatch baseline;
    private final Map<String, DoctrinePatch> cultures;
    private final DoctrinePatch loneBuilding;
    private final Map<String, DoctrinePatch> villageTypes;
    private final Map<MilitaryTier, TierModifier> tiers;

    public DoctrineDefaults(DoctrinePatch baseline, Map<String, DoctrinePatch> cultures, DoctrinePatch loneBuilding,
                            Map<String, DoctrinePatch> villageTypes, Map<MilitaryTier, TierModifier> tiers) {
        this.baseline = BUILTIN_BASELINE.mergedWith(baseline);
        this.cultures = Collections.unmodifiableMap(new LinkedHashMap<>(cultures));
        this.loneBuilding = loneBuilding;
        this.villageTypes = Collections.unmodifiableMap(new LinkedHashMap<>(villageTypes));
        EnumMap<MilitaryTier, TierModifier> t = new EnumMap<>(MilitaryTier.class);
        t.putAll(tiers);
        this.tiers = Collections.unmodifiableMap(t);
    }

    public DoctrinePatch baseline() {
        return baseline;
    }

    public Map<String, DoctrinePatch> cultures() {
        return cultures;
    }

    public DoctrinePatch loneBuilding() {
        return loneBuilding;
    }

    public Map<String, DoctrinePatch> villageTypes() {
        return villageTypes;
    }

    public TierModifier tier(MilitaryTier tier) {
        return tiers.getOrDefault(tier, TierModifier.NONE);
    }

    /**
     * The village-type layer key for a type id: an exact key wins; otherwise the most specific
     * matching {@code *} glob (most literal characters, then lexicographic). Null if none.
     */
    public String villageTypeKey(String villageTypeId) {
        if (villageTypes.containsKey(villageTypeId)) {
            return villageTypeId;
        }
        String best = null;
        for (String k : villageTypes.keySet()) {
            if (k.indexOf('*') >= 0 && glob(k, villageTypeId)) {
                int lit = k.replace("*", "").length();
                int bestLit = best == null ? -1 : best.replace("*", "").length();
                if (lit > bestLit || (lit == bestLit && k.compareTo(best) < 0)) {
                    best = k;
                }
            }
        }
        return best;
    }

    static boolean glob(String pattern, String s) {
        StringBuilder rx = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            rx.append(c == '*' ? "[^/]*" : java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return s.matches(rx.toString());
    }

    /** Merges JSON files in order; problems are collected, never thrown. */
    public static DoctrineDefaults fromJson(List<JsonObject> files, List<String> problems) {
        DoctrinePatch baseline = DoctrinePatch.EMPTY;
        DoctrinePatch lone = DoctrinePatch.EMPTY;
        Map<String, DoctrinePatch> cultures = new LinkedHashMap<>();
        Map<String, DoctrinePatch> types = new LinkedHashMap<>();
        Map<MilitaryTier, TierModifier> tiers = new EnumMap<>(MilitaryTier.class);
        for (JsonObject f : files) {
            if (f.has("baseline") && f.get("baseline").isJsonObject()) {
                baseline = baseline.mergedWith(DoctrinePatch.fromJson(f.getAsJsonObject("baseline"), "baseline", problems));
            }
            if (f.has("lone_building") && f.get("lone_building").isJsonObject()) {
                lone = lone.mergedWith(DoctrinePatch.fromJson(f.getAsJsonObject("lone_building"), "lone_building", problems));
            }
            mergeMap(f, "cultures", cultures, problems);
            mergeMap(f, "village_types", types, problems);
            if (f.has("tiers") && f.get("tiers").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : f.getAsJsonObject("tiers").entrySet()) {
                    MilitaryTier tier;
                    try {
                        tier = MilitaryTier.valueOf(e.getKey());
                    } catch (IllegalArgumentException ex) {
                        problems.add("tiers: unknown tier '" + e.getKey() + "'");
                        continue;
                    }
                    if (!e.getValue().isJsonObject()) {
                        problems.add("tiers." + e.getKey() + ": expected an object");
                        continue;
                    }
                    JsonObject o = e.getValue().getAsJsonObject();
                    tiers.put(tier, new TierModifier(
                            intOr(o, "commitPerThreat", 0), intOr(o, "reserve", 0), intOr(o, "radius", 0),
                            o.has("reserveSet") ? o.get("reserveSet").getAsInt() : null));
                }
            }
        }
        return new DoctrineDefaults(baseline, cultures, lone, types, tiers);
    }

    private static void mergeMap(JsonObject f, String section, Map<String, DoctrinePatch> into, List<String> problems) {
        if (!f.has(section) || !f.get(section).isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : f.getAsJsonObject(section).entrySet()) {
            if (!e.getValue().isJsonObject()) {
                problems.add(section + "." + e.getKey() + ": expected an object");
                continue;
            }
            DoctrinePatch p = DoctrinePatch.fromJson(e.getValue().getAsJsonObject(), section + "." + e.getKey(), problems);
            into.merge(e.getKey(), p, DoctrinePatch::mergedWith);
        }
    }

    private static int intOr(JsonObject o, String key, int def) {
        return o.has(key) ? o.get(key).getAsInt() : def;
    }
}

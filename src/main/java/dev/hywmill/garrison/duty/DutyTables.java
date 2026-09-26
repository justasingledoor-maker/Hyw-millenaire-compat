package dev.hywmill.garrison.duty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hywmill.military.MilitaryTier;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M4 duty data, merged from {@code data/<ns>/hywmill_duties/*.json} in resource-location order.
 * Separate from the M3 garrison tables and the M2 doctrine (neither schema changes).
 *
 * <pre>
 * { "defaults": { "tiers": { TIER: { DutyRule fields } }, "movement": { MoveRule fields },
 *                 "scouting": { ScoutRule fields }, "raid": { RaidRule fields } },
 *   "cultures": { "millenaire:norman": { same sections; every section merges per field } } }
 * </pre>
 * Missing fields keep the default; out-of-range values are reported and replaced by the default.
 */
public final class DutyTables {
    private static volatile DutyTables current = fallback();

    private final DutyTable defaults;
    private final Map<String, DutyTable> cultures;

    private DutyTables(DutyTable defaults, Map<String, DutyTable> cultures) {
        this.defaults = defaults;
        this.cultures = cultures;
    }

    public static DutyTables current() {
        return current;
    }

    public static void set(DutyTables t) {
        current = t;
    }

    /** No data loaded: no standing duties beyond GARRISON, no raids. */
    public static DutyTables fallback() {
        Map<MilitaryTier, DutyRule> tiers = new EnumMap<>(MilitaryTier.class);
        for (MilitaryTier t : MilitaryTier.values()) {
            tiers.put(t, DutyRule.NONE);
        }
        return new DutyTables(new DutyTable(tiers, MoveRule.DEFAULT, ScoutRule.DEFAULT, RaidRule.OFF), Map.of());
    }

    public DutyTable defaults() {
        return defaults;
    }

    public Map<String, DutyTable> cultures() {
        return cultures;
    }

    public DutyTable forCulture(String culture) {
        return cultures.getOrDefault(culture, defaults);
    }

    public static DutyTables fromJson(List<JsonObject> files, List<String> problems) {
        JsonObject defaults = new JsonObject();
        Map<String, JsonObject> patches = new LinkedHashMap<>();
        for (JsonObject f : files) {
            if (f.has("defaults") && f.get("defaults").isJsonObject()) {
                merge(defaults, f.getAsJsonObject("defaults"));
            }
            if (f.has("cultures") && f.get("cultures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : f.getAsJsonObject("cultures").entrySet()) {
                    if (e.getValue().isJsonObject()) {
                        merge(patches.computeIfAbsent(e.getKey(), k -> new JsonObject()), e.getValue().getAsJsonObject());
                    } else {
                        problems.add("culture " + e.getKey() + ": not an object");
                    }
                }
            }
        }
        DutyTable base = table(defaults, fallback().defaults, "defaults", problems);
        Map<String, DutyTable> cultures = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : patches.entrySet()) {
            cultures.put(e.getKey(), table(e.getValue(), base, "culture " + e.getKey(), problems));
        }
        return new DutyTables(base, Collections.unmodifiableMap(cultures));
    }

    /** Merges per field, one level deep for sections and two levels for "tiers". */
    private static void merge(JsonObject into, JsonObject from) {
        for (Map.Entry<String, JsonElement> e : from.entrySet()) {
            if (e.getValue().isJsonObject() && into.has(e.getKey()) && into.get(e.getKey()).isJsonObject()) {
                JsonObject dst = into.getAsJsonObject(e.getKey());
                if (e.getKey().equals("tiers")) {
                    merge(dst, e.getValue().getAsJsonObject());
                } else {
                    e.getValue().getAsJsonObject().entrySet().forEach(x -> dst.add(x.getKey(), x.getValue()));
                }
            } else {
                into.add(e.getKey(), e.getValue().deepCopy());
            }
        }
    }

    private static DutyTable table(JsonObject o, DutyTable base, String where, List<String> problems) {
        Map<MilitaryTier, DutyRule> tiers = new EnumMap<>(MilitaryTier.class);
        JsonObject tj = obj(o, "tiers");
        for (MilitaryTier t : MilitaryTier.values()) {
            DutyRule b = base.tier(t);
            tiers.put(t, t == MilitaryTier.NONE ? DutyRule.NONE : tj.has(t.name()) && tj.get(t.name()).isJsonObject()
                    ? rule(tj.getAsJsonObject(t.name()), b, where + " tier " + t, problems) : b);
        }
        for (String k : tj.keySet()) {
            try {
                MilitaryTier.valueOf(k);
            } catch (IllegalArgumentException ex) {
                problems.add(where + ": unknown tier " + k + "; ignored");
            }
        }
        JsonObject m = obj(o, "movement");
        MoveRule mb = base.move();
        String mw = where + " movement";
        MoveRule move = new MoveRule((int) num(m, "maxHop", mb.maxHop(), 8, 64, mw, problems),
                (int) num(m, "arriveRadius", mb.arriveRadius(), 1, 16, mw, problems),
                (long) num(m, "hopTimeout", mb.hopTimeout(), 100, 24000, mw, problems),
                num(m, "patrolRadius", mb.patrolRadius(), 0.1, 1.5, mw, problems),
                (int) num(m, "patrolPoints", mb.patrolPoints(), 3, 16, mw, problems),
                (long) num(m, "patrolPause", mb.patrolPause(), 0, 24000, mw, problems),
                (int) num(m, "sentrySpacing", mb.sentrySpacing(), 1, 8, mw, problems));
        JsonObject s = obj(o, "scouting");
        ScoutRule sb = base.scout();
        String sw = where + " scouting";
        ScoutRule scout = new ScoutRule((int) num(s, "distance", sb.distance(), 8, 128, sw, problems),
                (int) num(s, "posts", sb.posts(), 1, 16, sw, problems),
                (long) num(s, "dwell", sb.dwell(), 20, 240000, sw, problems),
                (long) num(s, "rest", sb.rest(), 20, 240000, sw, problems),
                (long) num(s, "phaseTimeout", sb.phaseTimeout(), 200, 240000, sw, problems));
        JsonObject r = obj(o, "raid");
        RaidRule rb = base.raid();
        String rw = where + " raid";
        boolean enabled = rb.enabled();
        if (r.has("enabled")) {
            try {
                enabled = r.get("enabled").getAsBoolean();
            } catch (RuntimeException ex) {
                problems.add(rw + ": enabled is not a boolean; using " + enabled);
            }
        }
        int minCommit = (int) num(r, "minCommit", rb.minCommit(), 1, 64, rw, problems);
        RaidRule raid = new RaidRule(enabled, num(r, "commitFraction", rb.commitFraction(), 0, 0.9, rw, problems), minCommit,
                Math.max(minCommit, (int) num(r, "maxCommit", rb.maxCommit(), 1, 64, rw, problems)),
                num(r, "minHome", rb.minHome(), 0.1, 1, rw, problems),
                (int) num(r, "keepSentryPairs", rb.keepSentryPairs(), 0, 16, rw, problems),
                (int) num(r, "keepReserve", rb.keepReserve(), 0, 64, rw, problems),
                (int) num(r, "minGarrison", rb.minGarrison(), 1, 1024, rw, problems));
        return new DutyTable(Collections.unmodifiableMap(tiers), move, scout, raid);
    }

    private static DutyRule rule(JsonObject o, DutyRule b, String w, List<String> p) {
        return new DutyRule(num(o, "sentryShare", b.sentryShare(), 0, 1, w, p),
                (int) num(o, "maxSentryPairs", b.maxSentryPairs(), 0, 32, w, p),
                (int) num(o, "minUnitsForSentries", b.minUnitsForSentries(), 1, Integer.MAX_VALUE, w, p),
                num(o, "patrolShare", b.patrolShare(), 0, 1, w, p),
                (int) num(o, "maxPatrol", b.maxPatrol(), 0, 64, w, p),
                (int) num(o, "minUnitsForPatrol", b.minUnitsForPatrol(), 1, Integer.MAX_VALUE, w, p),
                num(o, "scoutShare", b.scoutShare(), 0, 1, w, p),
                (int) num(o, "maxScouts", b.maxScouts(), 0, 16, w, p),
                (int) num(o, "minUnitsForScouts", b.minUnitsForScouts(), 1, Integer.MAX_VALUE, w, p),
                num(o, "reserveShare", b.reserveShare(), 0, 1, w, p),
                (int) num(o, "minReserve", b.minReserve(), 0, 64, w, p));
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
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

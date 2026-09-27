package dev.hywmill.politics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The politics tables for every culture: defaults plus per-culture patches (fields merge, like the
 * M3/M4 tables). Parsed from {@code data/<ns>/hywmill_politics/*.json} in resource-location order;
 * unknown or invalid fields are reported and ignored, so a bad datapack never breaks politics.
 */
public final class PoliticsData {
    private final PoliticsTables defaults;
    private final Map<String, PoliticsTables> cultures;

    public PoliticsData(PoliticsTables defaults, Map<String, PoliticsTables> cultures) {
        this.defaults = defaults;
        this.cultures = Map.copyOf(cultures);
    }

    public static final PoliticsData SHIPPED_FALLBACK = new PoliticsData(PoliticsTables.DEFAULTS, Map.of());

    public PoliticsTables defaults() {
        return defaults;
    }

    public PoliticsTables forCulture(String culture) {
        return cultures.getOrDefault(culture, defaults);
    }

    public Map<String, PoliticsTables> cultures() {
        return cultures;
    }

    public static PoliticsData fromJson(List<JsonObject> files, List<String> problems) {
        PoliticsTables def = PoliticsTables.DEFAULTS;
        Map<String, JsonObject> patches = new HashMap<>();
        for (JsonObject f : files) {
            if (f.has("defaults") && f.get("defaults").isJsonObject()) {
                def = patch(def, f.getAsJsonObject("defaults"), problems, "defaults");
            }
            if (f.has("cultures") && f.get("cultures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : f.getAsJsonObject("cultures").entrySet()) {
                    if (e.getValue().isJsonObject()) {
                        patches.computeIfAbsent(e.getKey(), k -> new JsonObject());
                        for (Map.Entry<String, JsonElement> x : e.getValue().getAsJsonObject().entrySet()) {
                            patches.get(e.getKey()).add(x.getKey(), x.getValue());
                        }
                    }
                }
            }
        }
        Map<String, PoliticsTables> cultures = new HashMap<>();
        for (Map.Entry<String, JsonObject> e : patches.entrySet()) {
            cultures.put(e.getKey(), patch(def, e.getValue(), problems, e.getKey()));
        }
        return new PoliticsData(def, cultures);
    }

    static PoliticsTables patch(PoliticsTables base, JsonObject o, List<String> problems, String where) {
        PoliticsTables.StandingRule s = base.standing();
        if (o.has("standing") && o.get("standing").isJsonObject()) {
            JsonObject j = o.getAsJsonObject("standing");
            s = new PoliticsTables.StandingRule(i(j, "boycott", s.boycott()), i(j, "trusted", s.trusted()), i(j, "patron", s.patron()),
                    i(j, "sworn", s.sworn()), i(j, "patronFavor", s.patronFavor()), l(j, "swornFavorEarned", s.swornFavorEarned()),
                    d(j, "keepFactor", s.keepFactor()));
            if (s.keepFactor() <= 0 || s.keepFactor() > 1) {
                problems.add(where + ": standing.keepFactor must be in (0, 1]; using " + base.standing().keepFactor());
                s = new PoliticsTables.StandingRule(s.boycott(), s.trusted(), s.patron(), s.sworn(), s.patronFavor(),
                        s.swornFavorEarned(), base.standing().keepFactor());
            }
        }
        PoliticsTables.GrievanceRule g = base.grievance();
        if (o.has("grievance") && o.get("grievance").isJsonObject()) {
            JsonObject j = o.getAsJsonObject("grievance");
            Map<GrievanceKind, Double> w = new EnumMap<>(GrievanceKind.class);
            w.putAll(g.weights());
            if (j.has("weights") && j.get("weights").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("weights").entrySet()) {
                    try {
                        w.put(GrievanceKind.valueOf(e.getKey()), e.getValue().getAsDouble());
                    } catch (RuntimeException ex) {
                        problems.add(where + ": unknown grievance weight '" + e.getKey() + "'");
                    }
                }
            }
            long halfLife = j.has("halfLifeDays") ? Math.round(d(j, "halfLifeDays", 0) * PoliticsTables.DAY) : g.halfLifeTicks();
            if (halfLife <= 0) {
                problems.add(where + ": grievance.halfLifeDays must be > 0");
                halfLife = g.halfLifeTicks();
            }
            g = new PoliticsTables.GrievanceRule(w, d(j, "insideFactor", g.insideFactor()), d(j, "selfDefenseFactor", g.selfDefenseFactor()),
                    halfLife, d(j, "warning", g.warning()), d(j, "outlaw", g.outlaw()), d(j, "pardon", g.pardon()), d(j, "max", g.max()));
        }
        PoliticsTables.FavorRule f = base.favor();
        if (o.has("favor") && o.get("favor").isJsonObject()) {
            JsonObject j = o.getAsJsonObject("favor");
            Map<FavorSource, Integer> a = new EnumMap<>(FavorSource.class);
            a.putAll(f.amounts());
            if (j.has("sources") && j.get("sources").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("sources").entrySet()) {
                    try {
                        a.put(FavorSource.valueOf(e.getKey()), e.getValue().getAsInt());
                    } catch (RuntimeException ex) {
                        problems.add(where + ": unknown favor source '" + e.getKey() + "' (trade is deliberately not a source)");
                    }
                }
            }
            f = new PoliticsTables.FavorRule(a, i(j, "cap", f.cap()));
        }
        PoliticsTables.PardonRule pr = base.pardon();
        if (o.has("pardon") && o.get("pardon").isJsonObject()) {
            JsonObject j = o.getAsJsonObject("pardon");
            pr = new PoliticsTables.PardonRule(j.has("enabled") ? j.get("enabled").getAsBoolean() : pr.enabled(),
                    d(j, "perGrievance", pr.perGrievance()), i(j, "killFee", pr.killFee()));
            if (pr.perGrievance() < 0 || pr.killFee() < 0) {
                problems.add(where + ": pardon prices must be >= 0; using " + base.pardon());
                pr = base.pardon();
            }
        }
        return new PoliticsTables(s, g, f, pr);
    }

    private static int i(JsonObject j, String k, int def) {
        return j.has(k) ? j.get(k).getAsInt() : def;
    }

    private static long l(JsonObject j, String k, long def) {
        return j.has(k) ? j.get(k).getAsLong() : def;
    }

    private static double d(JsonObject j, String k, double def) {
        return j.has(k) ? j.get(k).getAsDouble() : def;
    }
}

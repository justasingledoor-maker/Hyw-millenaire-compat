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
                    d(j, "perGrievance", pr.perGrievance()), i(j, "killFee", pr.killFee()),
                    d(j, "apologyPerGrievance", pr.apologyPerGrievance()));
            if (pr.perGrievance() < 0 || pr.killFee() < 0 || pr.apologyPerGrievance() < 0) {
                problems.add(where + ": pardon prices must be >= 0; using " + base.pardon());
                pr = base.pardon();
            }
        }
        PoliticsTables.DiplomacyRule dr = base.diplomacy();
        if (o.has("diplomacy") && o.get("diplomacy").isJsonObject()) {
            dr = diplomacy(dr, o.getAsJsonObject("diplomacy"), problems, where);
        }
        PoliticsTables.RequestRule rq = base.requests();
        if (o.has("requests") && o.get("requests").isJsonObject()) {
            JsonObject j = o.getAsJsonObject("requests");
            for (String deferred : List.of("escortMax", "escortTicks", "escortFavor")) {
                if (j.has(deferred)) {
                    problems.add(where + ": requests." + deferred + ": escorts are deferred (not in M5); ignored");
                }
            }
            Map<Standing, Integer> det = standingInts(j, "detachMax", rq.detachMax(), problems, where);
            rq = new PoliticsTables.RequestRule(det, i(j, "detachMaxDays", rq.detachMaxDays()),
                    i(j, "detachRadius", rq.detachRadius()), i(j, "detachFavorDay", rq.detachFavorDay()),
                    i(j, "casualtyFavor", rq.casualtyFavor()), d(j, "casualtyWillingness", rq.casualtyWillingness()),
                    i(j, "favorWillingness", rq.favorWillingness()), l(j, "cooldown", rq.cooldown()));
            if (rq.detachMaxDays() < 0 || rq.favorWillingness() <= 0 || rq.detachFavorDay() < 0) {
                problems.add(where + ": requests values out of range; using " + where + " base");
                rq = base.requests();
            }
        }
        PoliticsTables.RaidCounselRule rc = base.raidCounsel();
        if (o.has("raidCounsel") && o.get("raidCounsel").isJsonObject()) {
            JsonObject j = o.getAsJsonObject("raidCounsel");
            Map<Standing, Double> ch = new EnumMap<>(Standing.class);
            ch.putAll(rc.chance());
            if (j.has("chance") && j.get("chance").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("chance").entrySet()) {
                    try {
                        ch.put(Standing.valueOf(e.getKey()), Math.max(0, Math.min(1, e.getValue().getAsDouble())));
                    } catch (RuntimeException ex) {
                        problems.add(where + ": raidCounsel.chance names unknown standing '" + e.getKey() + "'");
                    }
                }
            }
            rc = new PoliticsTables.RaidCounselRule(j.has("enabled") ? j.get("enabled").getAsBoolean() : rc.enabled(),
                    java.util.Collections.unmodifiableMap(ch), d(j, "tooStrongFactor", rc.tooStrongFactor()), d(j, "minChance", rc.minChance()),
                    d(j, "maxChance", rc.maxChance()), i(j, "pointCost", rc.pointCost()), l(j, "cooldown", rc.cooldown()));
            if (rc.tooStrongFactor() < 0 || rc.minChance() < 0 || rc.maxChance() > 1 || rc.minChance() > rc.maxChance() || rc.pointCost() < 0
                    || rc.cooldown() < 0) {
                problems.add(where + ": raidCounsel values out of range; using " + where + " base");
                rc = base.raidCounsel();
            }
        }
        PoliticsTables.SiegeRule sg = base.siege();
        if (o.has("siege") && o.get("siege").isJsonObject()) {
            sg = siege(sg, o.getAsJsonObject("siege"), problems, where);
        }
        PoliticsTables.ArsenalRule ar = base.arsenal();
        if (o.has("arsenal") && o.get("arsenal").isJsonObject()) {
            ar = arsenal(ar, o.getAsJsonObject("arsenal"), problems, where);
        }
        PoliticsTables.WarCounselRule wc = base.warCounsel();
        if (o.has("warCounsel") && o.get("warCounsel").isJsonObject()) {
            wc = warCounsel(wc, o.getAsJsonObject("warCounsel"), problems, where);
        }
        PoliticsTables.MobilizationRule mb = base.mobilization();
        if (o.has("mobilization") && o.get("mobilization").isJsonObject()) {
            JsonObject j = o.getAsJsonObject("mobilization");
            mb = new PoliticsTables.MobilizationRule(j.has("enabled") ? j.get("enabled").getAsBoolean() : mb.enabled(),
                    i(j, "equipmentFloor", mb.equipmentFloor()), i(j, "equipmentDrop", mb.equipmentDrop()));
            if (mb.equipmentFloor() < 0 || mb.equipmentDrop() < 0) {
                problems.add(where + ": mobilization values out of range; using " + where + " base");
                mb = base.mobilization();
            }
        }
        return new PoliticsTables(s, g, f, pr, dr, rq, rc, sg, ar, wc, mb);
    }

    private static PoliticsTables.WarCounselRule warCounsel(PoliticsTables.WarCounselRule b, JsonObject j, List<String> problems, String where) {
        PoliticsTables.WarCounselRule r = new PoliticsTables.WarCounselRule(j.has("enabled") ? j.get("enabled").getAsBoolean() : b.enabled(),
                standingMap(j, "warChance", b.warChance(), problems, where), standingMap(j, "peaceChance", b.peaceChance(), problems, where),
                d(j, "minChance", b.minChance()), d(j, "maxChance", b.maxChance()), i(j, "warPoints", b.warPoints()),
                i(j, "peacePoints", b.peacePoints()), l(j, "cooldown", b.cooldown()), i(j, "peaceRelation", b.peaceRelation()),
                i(j, "warRelation", b.warRelation()), d(j, "enemyMin", b.enemyMin()), d(j, "enemyMax", b.enemyMax()),
                d(j, "exponent", b.exponent()), j.has("peaceAfterSiege") ? j.get("peaceAfterSiege").getAsBoolean() : b.peaceAfterSiege());
        if (r.minChance() < 0 || r.maxChance() > 1 || r.minChance() > r.maxChance() || r.warPoints() < 0 || r.peacePoints() < 0
                || r.cooldown() < 0 || r.peaceRelation() < -100 || r.peaceRelation() > 100 || r.warRelation() < -100 || r.warRelation() > -90
                || r.enemyMin() < 0 || r.enemyMax() > 1 || r.enemyMin() > r.enemyMax() || r.exponent() <= 0) {
            problems.add(where + ": warCounsel values out of range; using " + where + " base");
            return b;
        }
        return r;
    }

    private static Map<Standing, Double> standingMap(JsonObject j, String key, Map<Standing, Double> base, List<String> problems, String where) {
        Map<Standing, Double> m = new EnumMap<>(Standing.class);
        m.putAll(base);
        if (j.has(key) && j.get(key).isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : j.getAsJsonObject(key).entrySet()) {
                try {
                    m.put(Standing.valueOf(e.getKey()), Math.max(0, Math.min(1, e.getValue().getAsDouble())));
                } catch (RuntimeException ex) {
                    problems.add(where + ": " + key + " names unknown standing '" + e.getKey() + "'");
                }
            }
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    private static PoliticsTables.ArsenalRule arsenal(PoliticsTables.ArsenalRule b, JsonObject j, List<String> problems, String where) {
        Map<PoliticsTables.MilitaryTierKey, Integer> n = new EnumMap<>(PoliticsTables.MilitaryTierKey.class);
        n.putAll(b.engines());
        if (j.has("engines") && j.get("engines").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("engines").entrySet()) {
                try {
                    n.put(PoliticsTables.MilitaryTierKey.valueOf(e.getKey()), Math.max(0, Math.min(8, e.getValue().getAsInt())));
                } catch (RuntimeException ex) {
                    problems.add(where + ": arsenal.engines names unknown tier '" + e.getKey() + "'");
                }
            }
        }
        List<String> types = strings(j, "types", b.types());
        List<String> stronghold = strings(j, "strongholdTypes", b.strongholdTypes());
        PoliticsTables.ArsenalRule r = new PoliticsTables.ArsenalRule(j.has("enabled") ? j.get("enabled").getAsBoolean() : b.enabled(),
                java.util.Collections.unmodifiableMap(n), types, stronghold, d(j, "engineStrength", b.engineStrength()), d(j, "fortCut", b.fortCut()));
        if (r.types().isEmpty() || r.engineStrength() < 0 || r.fortCut() < 0 || r.fortCut() > 1) {
            problems.add(where + ": arsenal values out of range; using " + where + " base");
            return b;
        }
        return r;
    }

    private static List<String> strings(JsonObject j, String key, List<String> base) {
        if (!j.has(key) || !j.get(key).isJsonArray()) {
            return base;
        }
        List<String> out = new java.util.ArrayList<>();
        for (JsonElement e : j.getAsJsonArray(key)) {
            out.add(e.getAsString());
        }
        return List.copyOf(out);
    }

    private static PoliticsTables.SiegeRule siege(PoliticsTables.SiegeRule b, JsonObject j, List<String> problems, String where) {
        Map<Standing, Double> ch = new EnumMap<>(Standing.class);
        ch.putAll(b.counselChance());
        if (j.has("counselChance") && j.get("counselChance").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("counselChance").entrySet()) {
                try {
                    ch.put(Standing.valueOf(e.getKey()), Math.max(0, Math.min(1, e.getValue().getAsDouble())));
                } catch (RuntimeException ex) {
                    problems.add(where + ": siege.counselChance names unknown standing '" + e.getKey() + "'");
                }
            }
        }
        Map<PoliticsTables.MilitaryTierKey, Integer> tr = new EnumMap<>(PoliticsTables.MilitaryTierKey.class);
        tr.putAll(b.tribute());
        if (j.has("tribute") && j.get("tribute").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("tribute").entrySet()) {
                try {
                    tr.put(PoliticsTables.MilitaryTierKey.valueOf(e.getKey()), Math.max(0, e.getValue().getAsInt()));
                } catch (RuntimeException ex) {
                    problems.add(where + ": siege.tribute names unknown tier '" + e.getKey() + "'");
                }
            }
        }
        PoliticsTables.SiegeRule r = new PoliticsTables.SiegeRule(j.has("enabled") ? j.get("enabled").getAsBoolean() : b.enabled(),
                d(j, "commitFraction", b.commitFraction()), i(j, "minCommit", b.minCommit()), i(j, "maxCommit", b.maxCommit()),
                d(j, "minHome", b.minHome()), i(j, "keepSentryPairs", b.keepSentryPairs()), i(j, "keepReserve", b.keepReserve()),
                i(j, "minGarrison", b.minGarrison()), l(j, "musterTicks", b.musterTicks()), l(j, "marchPer100", b.marchPer100()),
                l(j, "minMarch", b.minMarch()), l(j, "maxMarch", b.maxMarch()), l(j, "waitTicks", b.waitTicks()),
                l(j, "battleTicks", b.battleTicks()), d(j, "breakFraction", b.breakFraction()), d(j, "routFraction", b.routFraction()),
                d(j, "millenaireWeight", b.millenaireWeight()), d(j, "exponent", b.exponent()), d(j, "loserLoss", b.loserLoss()),
                d(j, "winnerLossBase", b.winnerLossBase()), d(j, "winnerLossMax", b.winnerLossMax()),
                java.util.Collections.unmodifiableMap(ch), d(j, "tooStrongFactor", b.tooStrongFactor()), i(j, "counselPoints", b.counselPoints()),
                l(j, "counselCooldown", b.counselCooldown()), j.has("aiEnabled") ? j.get("aiEnabled").getAsBoolean() : b.aiEnabled(),
                l(j, "aiInterval", b.aiInterval()), d(j, "aiDailyChance", b.aiDailyChance()), d(j, "aiMinRatio", b.aiMinRatio()),
                l(j, "aiCooldown", b.aiCooldown()), java.util.Collections.unmodifiableMap(tr), d(j, "levyShare", b.levyShare()),
                d(j, "playerShare", b.playerShare()), i(j, "helperRep", b.helperRep()));
        boolean bad = r.commitFraction() < 0 || r.commitFraction() > 1 || r.minHome() < 0 || r.minHome() > 1 || r.minCommit() < 1
                || r.maxCommit() < r.minCommit() || r.minMarch() < 0 || r.maxMarch() < r.minMarch() || r.aiInterval() <= 0
                || r.breakFraction() < 0 || r.breakFraction() >= 1 || r.routFraction() < 0 || r.routFraction() >= 1 || r.exponent() <= 0
                || r.loserLoss() < 0 || r.loserLoss() > 1 || r.winnerLossMax() < 0 || r.winnerLossMax() > 1 || r.playerShare() < 0
                || r.playerShare() > 1 || r.aiDailyChance() < 0 || r.aiDailyChance() > 1 || r.counselPoints() < 0 || r.battleTicks() <= 0;
        if (bad) {
            problems.add(where + ": siege values out of range; using " + where + " base");
            return b;
        }
        return r;
    }

    private static Map<Standing, Integer> standingInts(JsonObject j, String key, Map<Standing, Integer> base, List<String> problems, String where) {
        if (!j.has(key) || !j.get(key).isJsonObject()) {
            return base;
        }
        Map<Standing, Integer> m = new EnumMap<>(Standing.class);
        m.putAll(base);
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject(key).entrySet()) {
            try {
                m.put(Standing.valueOf(e.getKey()), Math.max(0, e.getValue().getAsInt()));
            } catch (RuntimeException ex) {
                problems.add(where + ": requests." + key + " names unknown standing '" + e.getKey() + "'");
            }
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    private static PoliticsTables.DiplomacyRule diplomacy(PoliticsTables.DiplomacyRule b, JsonObject j, List<String> problems, String where) {
        Map<EnvoyKind, Double> bias = new EnumMap<>(EnvoyKind.class);
        bias.putAll(b.bias());
        Map<EnvoyKind, Integer> delta = new EnumMap<>(EnvoyKind.class);
        delta.putAll(b.delta());
        for (String key : List.of("bias", "delta")) {
            if (j.has(key) && j.get(key).isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject(key).entrySet()) {
                    try {
                        EnvoyKind k = EnvoyKind.valueOf(e.getKey());
                        if (key.equals("bias")) {
                            bias.put(k, e.getValue().getAsDouble());
                        } else {
                            delta.put(k, Math.max(0, e.getValue().getAsInt()));
                        }
                    } catch (RuntimeException ex) {
                        problems.add(where + ": diplomacy." + key + " names unknown kind '" + e.getKey() + "'");
                    }
                }
            }
        }
        PoliticsTables.DiplomacyRule r = new PoliticsTables.DiplomacyRule(java.util.Collections.unmodifiableMap(bias),
                d(j, "standingWeight", b.standingWeight()), d(j, "standingBWeight", b.standingBWeight()),
                d(j, "relationWeight", b.relationWeight()), d(j, "conflictWeight", b.conflictWeight()), d(j, "cultureWeight", b.cultureWeight()),
                d(j, "distanceWeight", b.distanceWeight()), d(j, "strengthWeight", b.strengthWeight()), d(j, "attemptWeight", b.attemptWeight()),
                d(j, "implausible", b.implausible()), java.util.Collections.unmodifiableMap(delta), d(j, "jitter", b.jitter()),
                d(j, "backfireShare", b.backfireShare()), i(j, "backfireRep", b.backfireRep()),
                l(j, "travelPer200", b.travelPer200()), l(j, "minTravel", b.minTravel()), l(j, "pairCooldown", b.pairCooldown()),
                j.has("truceDays") ? Math.round(d(j, "truceDays", 0) * PoliticsTables.DAY) : b.truceTicks(), i(j, "truceFloor", b.truceFloor()),
                i(j, "minRepWithOther", b.minRepWithOther()), l(j, "sowPlayerCooldown", b.sowPlayerCooldown()),
                l(j, "sowPairCooldown", b.sowPairCooldown()), i(j, "sowFavorCost", b.sowFavorCost()), i(j, "sowFavorStep", b.sowFavorStep()),
                d(j, "sowExposure", b.sowExposure()), d(j, "sowExposureStep", b.sowExposureStep()), l(j, "sowRecent", b.sowRecent()),
                i(j, "exposedRep", b.exposedRep()));
        if (r.jitter() < 0 || r.jitter() > 1 || r.backfireShare() < 0 || r.backfireShare() > 1 || r.truceTicks() <= 0
                || r.truceFloor() <= -90 || r.truceFloor() > 100 || r.minTravel() < 0 || r.travelPer200() < 0) {
            problems.add(where + ": diplomacy values out of range (jitter/backfireShare in [0,1], truceDays > 0, truceFloor in (-90, 100]); using " + where + " base");
            return b;
        }
        return r;
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

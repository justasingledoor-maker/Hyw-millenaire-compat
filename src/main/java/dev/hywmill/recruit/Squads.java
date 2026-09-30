package dev.hywmill.recruit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.politics.Standing;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Culture squads (post-M5), sold whole on the Muster Roll's Squads tab ({@code data/<ns>/hywmill_squads/*.json}). Pure.
 * A squad is 8-12 soldiers of one culture, each member a unit key, a count, the gear tier it is equipped at and an
 * optional kit ({@code levy}: light gambeson kits). Hired squads are the player's own units, like single hires.
 *
 * @param squadDiscount share taken off the sum of the members' single prices
 */
public record Squads(Map<String, List<Squad>> cultures, List<Squad> defaults, double squadDiscount) {
    public static final int MIN_SIZE = 8;
    public static final int MAX_SIZE = 12;

    public enum Category { INFANTRY, RANGED, CAVALRY, UNIQUE }

    public enum Quality { LOW, MEDIUM, HIGH }

    public record Member(String unit, int count, MilitaryTier gear, String kit) {}

    /**
     * @param standing lowest standing that may hire it
     * @param village  lowest village tier that can raise it
     * @param look     its look in hywmill_equipment ({@code looks}: own armour, colours and shield arms); "" = the culture's gear
     */
    public record Squad(String id, String name, Category category, Quality quality, Standing standing, MilitaryTier village, String description,
                        List<Member> members, String look) {
        /** The equipment role its members are equipped for: the squad's look, else the member's kit. */
        public String role(Member m) {
            return look.isEmpty() ? m.kit() : dev.hywmill.garrison.equip.EquipmentProfiles.LOOK_PREFIX + look;
        }

        public int size() {
            return members.stream().mapToInt(Member::count).sum();
        }

        /** "6 Shieldman, 4 Spear man" (for the screen). */
        public String roster() {
            List<String> parts = new ArrayList<>();
            for (Member m : members) {
                String n = m.unit().replace('_', ' ');
                parts.add(m.count() + " " + Character.toUpperCase(n.charAt(0)) + n.substring(1));
            }
            return String.join(", ", parts);
        }

        /** The best gear any member wears (for the screen). */
        public MilitaryTier topGear() {
            MilitaryTier t = MilitaryTier.NONE;
            for (Member m : members) {
                if (m.gear().ordinal() > t.ordinal()) {
                    t = m.gear();
                }
            }
            return t;
        }
    }

    public static final Squads EMPTY = new Squads(Map.of(), List.of(), 0.1);

    private static volatile Squads current = EMPTY;

    public static Squads current() {
        return current;
    }

    public static void set(Squads s) {
        current = s;
    }

    public List<Squad> forCulture(String culture) {
        return cultures.getOrDefault(culture, defaults);
    }

    @Nullable
    public Squad find(String culture, String id) {
        for (Squad s : forCulture(culture)) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** Default requirements by quality: LOW Trusted anywhere; MEDIUM Patron, Guard Post; HIGH Sworn, Garrison. */
    public static Standing defaultStanding(Quality q) {
        return switch (q) {
            case LOW -> Standing.TRUSTED;
            case MEDIUM -> Standing.PATRON;
            case HIGH -> Standing.SWORN;
        };
    }

    public static MilitaryTier defaultVillage(Quality q) {
        return switch (q) {
            case LOW -> MilitaryTier.WATCH;
            case MEDIUM -> MilitaryTier.GUARD_POST;
            case HIGH -> MilitaryTier.GARRISON;
        };
    }

    /** Why this player cannot hire the squad here, or null if they can. */
    @Nullable
    public static String refusal(Squad s, Standing standing, MilitaryTier village) {
        if (RecruitOffers.refused(standing)) {
            return "they will not hire out soldiers to you";
        }
        if (standing.ordinal() < s.standing().ordinal()) {
            return "needs " + s.standing().name().toLowerCase() + " standing";
        }
        if (village.ordinal() < s.village().ordinal()) {
            return "needs a " + s.village().name().toLowerCase().replace('_', ' ') + " or larger village";
        }
        return null;
    }

    /**
     * The squad's price in deniers: each member at its single Muster Roll price
     * ({@code (soldierBase + soldierPerCost × cost) × tierFactor(gear)} in or), summed, less {@code squadDiscount}, rounded to
     * whole argent, then the standing's discount.
     */
    public int price(Squad s, Map<String, UnitSpec> units, RecruitTables t, Standing standing) {
        double or = 0;
        for (Member m : s.members()) {
            UnitSpec u = units.get(m.unit());
            double cost = u == null ? 2 : u.cost();
            or += m.count() * (t.soldierBase() + t.soldierPerCost() * cost) * t.tierFactor().getOrDefault(m.gear(), 1.0);
        }
        double argent = or * (1 - squadDiscount) * RecruitTables.DENIER_OR / RecruitTables.DENIER_ARGENT;
        return RecruitOffers.discounted(RecruitOffers.argent(argent), t.discount().getOrDefault(standing, 0.0));
    }

    // ------------------------------------------------------------------ data

    /** Parses the files in order; a later file replaces a culture's whole list. Unknown units, bad sizes or tiers are reported. */
    public static Squads fromJson(List<JsonObject> files, Map<String, UnitSpec> units, List<String> problems) {
        Map<String, List<Squad>> cultures = new LinkedHashMap<>();
        List<Squad> defaults = List.of();
        double discount = EMPTY.squadDiscount();
        for (JsonObject f : files) {
            if (f.has("squadDiscount")) {
                double d = f.get("squadDiscount").getAsDouble();
                if (d >= 0 && d < 1) {
                    discount = d;
                } else {
                    problems.add("squadDiscount out of range: " + d);
                }
            }
            if (f.has("cultures") && f.get("cultures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> c : f.getAsJsonObject("cultures").entrySet()) {
                    cultures.put(c.getKey(), list(c.getKey(), c.getValue(), units, problems));
                }
            }
            if (f.has("defaults")) {
                defaults = list("defaults", f.get("defaults"), units, problems);
            }
        }
        return new Squads(Map.copyOf(cultures), defaults, discount);
    }

    private static List<Squad> list(String where, JsonElement e, Map<String, UnitSpec> units, List<String> problems) {
        List<Squad> out = new ArrayList<>();
        if (!e.isJsonArray()) {
            problems.add(where + ": not a list of squads");
            return out;
        }
        for (JsonElement x : e.getAsJsonArray()) {
            try {
                Squad s = squad(x.getAsJsonObject(), units, where, problems);
                if (s != null) {
                    out.add(s);
                }
            } catch (RuntimeException ex) {
                problems.add(where + ": bad squad " + x + " (" + ex.getMessage() + ")");
            }
        }
        return List.copyOf(out);
    }

    @Nullable
    private static Squad squad(JsonObject j, Map<String, UnitSpec> units, String where, List<String> problems) {
        String id = j.get("id").getAsString();
        Category cat = Category.valueOf(j.get("category").getAsString());
        Quality q = Quality.valueOf(j.get("quality").getAsString());
        Standing st = j.has("standing") ? Standing.valueOf(j.get("standing").getAsString()) : defaultStanding(q);
        MilitaryTier vt = j.has("village") ? MilitaryTier.valueOf(j.get("village").getAsString()) : defaultVillage(q);
        List<Member> members = new ArrayList<>();
        for (JsonElement me : j.getAsJsonArray("members")) {
            JsonObject m = me.getAsJsonObject();
            String unit = m.get("unit").getAsString();
            UnitSpec u = units.get(unit);
            if (u == null || !u.enabled()) {
                problems.add(where + " " + id + ": unknown or disabled unit '" + unit + "'; squad skipped");
                return null;
            }
            members.add(new Member(unit, m.get("count").getAsInt(), MilitaryTier.valueOf(m.get("gear").getAsString()),
                    m.has("kit") ? m.get("kit").getAsString() : ""));
        }
        Squad s = new Squad(id, j.get("name").getAsString(), cat, q, st, vt, j.has("description") ? j.get("description").getAsString() : "",
                List.copyOf(members), j.has("look") ? j.get("look").getAsString() : "");
        if (s.size() < MIN_SIZE || s.size() > MAX_SIZE) {
            problems.add(where + " " + id + ": " + s.size() + " soldiers (a squad has " + MIN_SIZE + "-" + MAX_SIZE + "); squad skipped");
            return null;
        }
        return s;
    }
}

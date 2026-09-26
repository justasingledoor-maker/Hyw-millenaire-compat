package dev.hywmill.garrison.equip;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.military.MilitaryTier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M4 equipment profiles ({@code data/<ns>/hywmill_equipment/*.json}): culture → tier → role → slot →
 * item ids. Pure data; whether an item exists and suits a unit is decided by the provider.
 *
 * <pre>
 * { "defaults": { "tiers": { TIER: { ROLE: { SLOT: [ "ns:item", ... ] } } } },
 *   "cultures": { "millenaire:norman": { "tiers": { TIER: { ROLE: { SLOT: [...] } } } } } }
 * </pre>
 * Roles: the duty roles {@code sentry, patrol, scout, reserve}, the class roles
 * {@code militia, line, ranged}, and {@code all}. Slots: {@code mainhand, offhand, head, chest,
 * legs, feet}. Files merge in resource-location order, list by list.
 */
public final class EquipmentProfiles {
    public static final List<String> SLOTS = List.of("mainhand", "offhand", "head", "chest", "legs", "feet");
    public static final Set<String> ROLES = Set.of("militia", "line", "ranged", "sentry", "patrol", "scout", "reserve", "all");
    private static final String DEFAULTS = "";

    private static volatile EquipmentProfiles current = new EquipmentProfiles(Map.of());

    /** culture ("" = defaults) → tier → role → slot → items */
    private final Map<String, Map<String, Map<String, Map<String, List<String>>>>> data;

    private EquipmentProfiles(Map<String, Map<String, Map<String, Map<String, List<String>>>>> data) {
        this.data = data;
    }

    public static EquipmentProfiles current() {
        return current;
    }

    public static void set(EquipmentProfiles p) {
        current = p;
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    public Map<String, Map<String, Map<String, Map<String, List<String>>>>> raw() {
        return data;
    }

    /** Duty role of a standing duty ({@code ""} for GARRISON and non-standing duties). */
    public static String dutyRole(Duty d) {
        return switch (d) {
            case SENTRY -> "sentry";
            case PATROL -> "patrol";
            case SCOUT -> "scout";
            case RESERVE -> "reserve";
            default -> "";
        };
    }

    public static String classRole(UnitClass c) {
        return switch (c) {
            case LEVY -> "militia";
            case RANGED, GUNPOWDER -> "ranged";
            case CAVALRY -> "scout";
            default -> "line";
        };
    }

    /**
     * Candidate item lists for one slot, in order: the duty role (culture, then defaults), then the
     * culture's class role and "all" (its flavour), then the defaults' class role and "all" (all for
     * the village's tier). The provider uses the first list with at least one item valid for the unit.
     */
    public List<List<String>> candidates(String culture, MilitaryTier tier, String dutyRole, String classRole, String slot) {
        List<List<String>> out = new ArrayList<>();
        boolean own = !culture.equals(DEFAULTS);
        if (own) {
            add(out, culture, tier, dutyRole, slot);
        }
        add(out, DEFAULTS, tier, dutyRole, slot);
        if (own) {
            add(out, culture, tier, classRole, slot);
            add(out, culture, tier, "all", slot);
        }
        add(out, DEFAULTS, tier, classRole, slot);
        add(out, DEFAULTS, tier, "all", slot);
        return out;
    }

    private void add(List<List<String>> out, String culture, MilitaryTier tier, String role, String slot) {
        if (role.isEmpty()) {
            return;
        }
        List<String> items = data.getOrDefault(culture, Map.of()).getOrDefault(tier.name(), Map.of()).getOrDefault(role, Map.of()).get(slot);
        if (items != null && !items.isEmpty()) {
            out.add(items);
        }
    }

    public static EquipmentProfiles fromJson(List<JsonObject> files, List<String> problems) {
        Map<String, Map<String, Map<String, Map<String, List<String>>>>> data = new LinkedHashMap<>();
        for (JsonObject f : files) {
            if (f.has("defaults") && f.get("defaults").isJsonObject()) {
                read(data, DEFAULTS, f.getAsJsonObject("defaults"), "defaults", problems);
            }
            if (f.has("cultures") && f.get("cultures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> c : f.getAsJsonObject("cultures").entrySet()) {
                    if (c.getValue().isJsonObject()) {
                        read(data, c.getKey(), c.getValue().getAsJsonObject(), "culture " + c.getKey(), problems);
                    } else {
                        problems.add("culture " + c.getKey() + ": not an object");
                    }
                }
            }
        }
        return new EquipmentProfiles(Collections.unmodifiableMap(data));
    }

    private static void read(Map<String, Map<String, Map<String, Map<String, List<String>>>>> data, String culture, JsonObject o,
                             String where, List<String> problems) {
        if (!o.has("tiers") || !o.get("tiers").isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> t : o.getAsJsonObject("tiers").entrySet()) {
            MilitaryTier tier;
            try {
                tier = MilitaryTier.valueOf(t.getKey());
            } catch (IllegalArgumentException ex) {
                problems.add(where + ": unknown tier " + t.getKey() + "; ignored");
                continue;
            }
            if (!t.getValue().isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> r : t.getValue().getAsJsonObject().entrySet()) {
                if (!ROLES.contains(r.getKey()) || !r.getValue().isJsonObject()) {
                    problems.add(where + " " + tier + ": unknown role " + r.getKey() + "; ignored");
                    continue;
                }
                for (Map.Entry<String, JsonElement> s : r.getValue().getAsJsonObject().entrySet()) {
                    if (!SLOTS.contains(s.getKey()) || !s.getValue().isJsonArray()) {
                        problems.add(where + " " + tier + " " + r.getKey() + ": unknown slot " + s.getKey() + "; ignored");
                        continue;
                    }
                    List<String> items = new ArrayList<>();
                    for (JsonElement e : s.getValue().getAsJsonArray()) {
                        String id = e.isJsonPrimitive() ? e.getAsString() : "";
                        if (id.indexOf(':') <= 0) {
                            problems.add(where + " " + tier + " " + r.getKey() + " " + s.getKey() + ": bad item id " + e + "; ignored");
                        } else {
                            items.add(id);
                        }
                    }
                    data.computeIfAbsent(culture, k -> new LinkedHashMap<>()).computeIfAbsent(tier.name(), k -> new LinkedHashMap<>())
                            .computeIfAbsent(r.getKey(), k -> new LinkedHashMap<>()).put(s.getKey(), List.copyOf(items));
                }
            }
        }
    }

    /**
     * Epic Knights item family: the item path without its material prefix
     * ({@code magistuarmory:steel_kiteshield} → {@code kiteshield}; {@code magistuarmory:longbow} → {@code longbow}).
     */
    public static String family(String itemId) {
        String p = itemId.substring(itemId.indexOf(':') + 1);
        for (String m : MATERIALS) {
            if (p.startsWith(m + "_") && p.length() > m.length() + 1) {
                return p.substring(m.length() + 1);
            }
        }
        return p;
    }

    public static final List<String> MATERIALS = List.of("wood", "wooden", "stone", "iron", "gold", "golden", "diamond", "netherite", "copper",
            "steel", "bronze", "silver", "tin");
}

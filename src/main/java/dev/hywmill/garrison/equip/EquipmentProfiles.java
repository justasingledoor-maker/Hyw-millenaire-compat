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
 *
 * <p>Armour kits (post-M5): a role may list whole armour sets, {@code "kits": [ {"head": id, "chest": id, "legs": id,
 * "feet": id}, ... ]} ({@code "none"} = that slot empty). A unit wears one complete kit, so pieces never mix across
 * lists (no plate helmet over cloth). Kits are resolved in the same order as slot lists; per-slot armour lists are only
 * used where no kit applies. {@code "dye": {"palette": ["#RRGGBB", ...]}} (defaults, per culture) colours dyeable
 * pieces; {@code "heraldry": false} turns off painted shields.
 */
public final class EquipmentProfiles {
    public static final List<String> SLOTS = List.of("mainhand", "offhand", "head", "chest", "legs", "feet");
    public static final List<String> ARMOUR = List.of("head", "chest", "legs", "feet");
    public static final String NONE = "none";

    /** One armour set: slot → item id or {@link #NONE}; every armour slot is present. */
    public record Kit(Map<String, String> pieces) {
        public String piece(String slot) {
            return pieces.getOrDefault(slot, NONE);
        }
    }

    /** A medieval dye palette (RGB), used when no data sets one. */
    public static final List<Integer> DEFAULT_PALETTE = List.of(0x8E2323, 0xA01E2C, 0x2E4A7D, 0x283B63, 0xC9A227, 0xD8912A, 0x3F6B35,
            0x7A4A2A, 0x222222, 0xD8CFB8, 0x6E6E6E, 0x5B2C6F);
    public static final Set<String> ROLES = Set.of("militia", "line", "ranged", "sentry", "patrol", "scout", "reserve", "all", "levy");
    private static final String DEFAULTS = "";

    private static volatile EquipmentProfiles current = new EquipmentProfiles(Map.of(), Map.of(), Map.of(), true);

    /** culture ("" = defaults) → tier → role → slot → items */
    private final Map<String, Map<String, Map<String, Map<String, List<String>>>>> data;
    /** culture → tier → role → kits */
    private final Map<String, Map<String, Map<String, List<Kit>>>> kits;
    /** culture ("" = defaults) → dye palette */
    private final Map<String, List<Integer>> palettes;
    private final boolean heraldry;
    private final String revision;

    private EquipmentProfiles(Map<String, Map<String, Map<String, Map<String, List<String>>>>> data,
                              Map<String, Map<String, Map<String, List<Kit>>>> kits, Map<String, List<Integer>> palettes, boolean heraldry) {
        this.data = data;
        this.kits = kits;
        this.palettes = palettes;
        this.heraldry = heraldry;
        this.revision = Integer.toHexString((data.toString() + "|" + kits + "|" + palettes + "|" + heraldry).hashCode());
    }

    /** Changes whenever the profile data changes (units re-equip once when it does). */
    public String revision() {
        return revision;
    }

    /** The equipment stamp stored on a unit: its duty role and the profile revision it was equipped with. */
    public static String stamp(String dutyRole) {
        return dutyRole + "@" + current.revision;
    }

    public boolean heraldry() {
        return heraldry;
    }

    /** The culture's dye palette, else the defaults', else {@link #DEFAULT_PALETTE}. */
    public List<Integer> palette(String culture) {
        List<Integer> p = palettes.get(culture);
        if (p == null || p.isEmpty()) {
            p = palettes.get(DEFAULTS);
        }
        return p == null || p.isEmpty() ? DEFAULT_PALETTE : p;
    }

    /** Candidate kit lists in the same order as {@link #candidates} (duty role, culture class/all, defaults class/all). */
    public List<List<Kit>> kitCandidates(String culture, MilitaryTier tier, String dutyRole, String classRole) {
        List<List<Kit>> out = new ArrayList<>();
        boolean own = !culture.equals(DEFAULTS);
        if (own) {
            addKits(out, culture, tier, dutyRole);
        }
        addKits(out, DEFAULTS, tier, dutyRole);
        if (own) {
            addKits(out, culture, tier, classRole);
            addKits(out, culture, tier, "all");
        }
        addKits(out, DEFAULTS, tier, classRole);
        addKits(out, DEFAULTS, tier, "all");
        return out;
    }

    private void addKits(List<List<Kit>> out, String culture, MilitaryTier tier, String role) {
        if (role.isEmpty()) {
            return;
        }
        List<Kit> k = kits.getOrDefault(culture, Map.of()).getOrDefault(tier.name(), Map.of()).get(role);
        if (k != null && !k.isEmpty()) {
            out.add(k);
        }
    }

    public Map<String, Map<String, Map<String, List<Kit>>>> rawKits() {
        return kits;
    }

    public static EquipmentProfiles current() {
        return current;
    }

    public static void set(EquipmentProfiles p) {
        current = p;
    }

    public boolean isEmpty() {
        return data.isEmpty() && kits.isEmpty();
    }

    public Map<String, Map<String, Map<String, Map<String, List<String>>>>> raw() {
        return data;
    }

    /** Duty role of a standing duty ({@code ""} for GARRISON and non-standing duties). */
    /** Post-M5: the role of mobilized soldiers (fresh wartime levies): light armour kits, whatever their duty. */
    public static final String LEVY = "levy";

    /**
     * The profile tier and role a unit is equipped for. A mobilized soldier (post-M5) wears {@link #LEVY} kits at the tier of
     * its own equipment level (1: GUARD_POST, iron weapons), not its village's tier, so a Watch's levies are not handed
     * clubs; everyone else uses the village tier and the duty role.
     */
    public static MilitaryTier gearTier(boolean mobilized, int equipmentLevel, MilitaryTier village) {
        if (!mobilized) {
            return village;
        }
        return switch (Math.max(0, Math.min(3, equipmentLevel))) {
            case 0 -> MilitaryTier.WATCH;
            case 1 -> MilitaryTier.GUARD_POST;
            case 2 -> MilitaryTier.GARRISON;
            default -> MilitaryTier.STRONGHOLD;
        };
    }

    public static String role(boolean mobilized, Duty assigned) {
        return mobilized ? LEVY : dutyRole(assigned);
    }

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
        Map<String, Map<String, Map<String, List<Kit>>>> kits = new LinkedHashMap<>();
        Map<String, List<Integer>> palettes = new LinkedHashMap<>();
        boolean heraldry = true;
        for (JsonObject f : files) {
            if (f.has("heraldry") && f.get("heraldry").isJsonPrimitive()) {
                heraldry = f.get("heraldry").getAsBoolean();
            }
            if (f.has("defaults") && f.get("defaults").isJsonObject()) {
                read(data, kits, palettes, DEFAULTS, f.getAsJsonObject("defaults"), "defaults", problems);
            }
            if (f.has("cultures") && f.get("cultures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> c : f.getAsJsonObject("cultures").entrySet()) {
                    if (c.getValue().isJsonObject()) {
                        read(data, kits, palettes, c.getKey(), c.getValue().getAsJsonObject(), "culture " + c.getKey(), problems);
                    } else {
                        problems.add("culture " + c.getKey() + ": not an object");
                    }
                }
            }
        }
        return new EquipmentProfiles(Collections.unmodifiableMap(data), Collections.unmodifiableMap(kits), Map.copyOf(palettes), heraldry);
    }

    private static void read(Map<String, Map<String, Map<String, Map<String, List<String>>>>> data,
                             Map<String, Map<String, Map<String, List<Kit>>>> kits, Map<String, List<Integer>> palettes, String culture,
                             JsonObject o, String where, List<String> problems) {
        if (o.has("dye") && o.get("dye").isJsonObject() && o.getAsJsonObject("dye").has("palette")
                && o.getAsJsonObject("dye").get("palette").isJsonArray()) {
            List<Integer> pal = new ArrayList<>();
            for (JsonElement e : o.getAsJsonObject("dye").getAsJsonArray("palette")) {
                String hex = e.isJsonPrimitive() ? e.getAsString().replace("#", "") : "";
                try {
                    pal.add(Integer.parseInt(hex, 16) & 0xFFFFFF);
                } catch (NumberFormatException ex) {
                    problems.add(where + " dye: bad colour " + e + "; ignored");
                }
            }
            palettes.put(culture, List.copyOf(pal));
        }
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
                    if (s.getKey().equals("kits") && s.getValue().isJsonArray()) {
                        List<Kit> list = new ArrayList<>();
                        for (JsonElement k : s.getValue().getAsJsonArray()) {
                            Kit kit = kit(k, where + " " + tier + " " + r.getKey(), problems);
                            if (kit != null) {
                                list.add(kit);
                            }
                        }
                        kits.computeIfAbsent(culture, k -> new LinkedHashMap<>()).computeIfAbsent(tier.name(), k -> new LinkedHashMap<>())
                                .put(r.getKey(), List.copyOf(list));
                        continue;
                    }
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

    /** A kit object: every armour slot must be given (an item id or "none"), else the kit is reported and ignored. */
    private static Kit kit(JsonElement k, String where, List<String> problems) {
        if (!k.isJsonObject()) {
            problems.add(where + " kits: not an object " + k + "; ignored");
            return null;
        }
        Map<String, String> pieces = new LinkedHashMap<>();
        for (String slot : ARMOUR) {
            JsonElement v = k.getAsJsonObject().get(slot);
            String id = v != null && v.isJsonPrimitive() ? v.getAsString() : "";
            if (!id.equals(NONE) && id.indexOf(':') <= 0) {
                problems.add(where + " kits: " + slot + " missing or bad in " + k + "; kit ignored");
                return null;
            }
            pieces.put(slot, id);
        }
        return new Kit(Map.copyOf(pieces));
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

package dev.hywmill.military.classify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The explicit, data-driven role tables (datapack files {@code data/<ns>/hywmill_roles/*.json}).
 * Keys are Millénaire ids ({@code millenaire:norman/guard}, {@code millenaire:norman/guardhouse}).
 * Later files override earlier ones key by key. Immutable once built.
 *
 * <pre>
 * { "villagers": { "millenaire:norman/guard": "SOLDIER", ... },
 *   "buildings": { "millenaire:norman/guardhouse": "GUARDHOUSE", ... } }
 * </pre>
 */
public final class RoleTable {
    public static final RoleTable EMPTY = new RoleTable(Map.of(), Map.of());

    private final Map<String, VillagerRole> villagers;
    private final Map<String, BuildingRole> buildings;

    public RoleTable(Map<String, VillagerRole> villagers, Map<String, BuildingRole> buildings) {
        this.villagers = Collections.unmodifiableMap(new LinkedHashMap<>(villagers));
        this.buildings = Collections.unmodifiableMap(new LinkedHashMap<>(buildings));
    }

    public Map<String, VillagerRole> villagers() {
        return villagers;
    }

    public Map<String, BuildingRole> buildings() {
        return buildings;
    }

    /**
     * Merges files in order. Invalid entries are skipped and reported in {@code problems}; only
     * SOLDIER/LEADER/MILITIA/CIVILIAN are accepted for villagers (OUTLAW comes from Millénaire's
     * hostile tag, never from the table).
     */
    public static RoleTable merge(List<JsonObject> files, List<String> problems) {
        Map<String, VillagerRole> v = new LinkedHashMap<>();
        Map<String, BuildingRole> b = new LinkedHashMap<>();
        for (JsonObject file : files) {
            if (file.has("villagers") && file.get("villagers").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : file.getAsJsonObject("villagers").entrySet()) {
                    VillagerRole role = parse(VillagerRole.class, e.getValue());
                    if (role == null || role == VillagerRole.OUTLAW) {
                        problems.add("villager " + e.getKey() + ": invalid role " + e.getValue());
                    } else {
                        v.put(e.getKey(), role);
                    }
                }
            }
            if (file.has("buildings") && file.get("buildings").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : file.getAsJsonObject("buildings").entrySet()) {
                    BuildingRole role = parse(BuildingRole.class, e.getValue());
                    if (role == null) {
                        problems.add("building " + e.getKey() + ": invalid role " + e.getValue());
                    } else {
                        b.put(e.getKey(), role);
                    }
                }
            }
        }
        return new RoleTable(v, b);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.getAsString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

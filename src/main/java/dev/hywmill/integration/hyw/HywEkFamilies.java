package dev.hywmill.integration.hyw;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hywmill.garrison.equip.EquipmentProfiles;
import ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Epic Knights item families HYW itself equips each unit with, per slot, read from HYW's own
 * {@code assets/hundred_years_war/hyw/npc/<unit>/equipment_epic_knights.json} (all levels). M4's
 * compatibility rule (docs/m4-spike.md §4): a profile weapon or offhand item suits a unit only if
 * its family is one of these. Units without such a file (or without a slot in it) accept none.
 */
final class HywEkFamilies {
    private static final Map<String, Map<String, Set<String>>> CACHE = new ConcurrentHashMap<>();

    private HywEkFamilies() {}

    /** Families for {@code slot} ({@code mainhand} or {@code offhand}) of HYW entity type {@code entityType}. */
    static Set<String> families(String entityType, String slot) {
        return CACHE.computeIfAbsent(entityType, HywEkFamilies::load).getOrDefault(slot, Set.of());
    }

    private static Map<String, Set<String>> load(String entityType) {
        String path = entityType.substring(entityType.indexOf(':') + 1);
        Map<String, Set<String>> out = new HashMap<>();
        try (InputStream in = BaseCombatEntity.class.getResourceAsStream("/assets/hundred_years_war/hyw/npc/" + path + "/equipment_epic_knights.json")) {
            if (in == null) {
                return out;
            }
            JsonObject levels = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("levels");
            for (Map.Entry<String, JsonElement> lv : levels.entrySet()) {
                for (Map.Entry<String, JsonElement> slot : lv.getValue().getAsJsonObject().entrySet()) {
                    String ours = switch (slot.getKey()) {
                        case "main_hand" -> "mainhand"; // riders' lance_weapon/main_weapon are switched by HYW itself: never overlaid
                        case "off_hand" -> "offhand";
                        default -> null;
                    };
                    if (ours == null || !slot.getValue().isJsonArray()) {
                        continue;
                    }
                    for (JsonElement choice : slot.getValue().getAsJsonArray()) {
                        JsonElement item = choice.isJsonObject() ? choice.getAsJsonObject().get("item") : null;
                        if (item != null && item.isJsonPrimitive()) {
                            out.computeIfAbsent(ours, k -> new HashSet<>()).add(EquipmentProfiles.family(item.getAsString()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            dev.hywmill.core.HmLog.warn("Could not read HYW's Epic Knights equipment for {}: {}", entityType, e.toString());
        }
        return out;
    }
}

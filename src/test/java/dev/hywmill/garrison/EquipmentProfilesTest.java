package dev.hywmill.garrison;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.equip.EquipmentProfiles;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.military.MilitaryTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** M4 equipment profile data and resolution (the item/unit compatibility itself needs the game registry). */
class EquipmentProfilesTest {
    static EquipmentProfiles shipped(List<String> problems) throws IOException {
        JsonObject f = JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/hywmill/hywmill_equipment/profiles.json"))).getAsJsonObject();
        return EquipmentProfiles.fromJson(List.of(f), problems);
    }

    @Test
    void shippedProfilesLoadCleanlyAndCoverTheSevenCultures() throws IOException {
        List<String> problems = new ArrayList<>();
        EquipmentProfiles p = shipped(problems);
        assertEquals(List.of(), problems);
        for (String c : List.of("millenaire:norman", "millenaire:byzantines", "millenaire:seljuk", "millenaire:indian", "millenaire:japanese",
                "millenaire:mayan", "millenaire:inuits")) {
            assertTrue(p.raw().containsKey(c), c);
        }
        for (var c : p.raw().values()) {
            for (var t : c.values()) {
                for (var r : t.values()) {
                    for (List<String> items : r.values()) {
                        for (String i : items) {
                            assertTrue(i.startsWith("magistuarmory:"), "profiles are Epic Knights only: " + i);
                        }
                    }
                }
            }
        }
    }

    @Test
    void resolutionPrefersDutyRoleThenCultureThenDefaults() throws IOException {
        EquipmentProfiles p = shipped(new ArrayList<>());
        List<List<String>> head = p.candidates("millenaire:norman", MilitaryTier.GARRISON, "sentry", "line", "head");
        assertEquals(List.of("magistuarmory:bascinet"), head.get(0), "sentry helmet (defaults' duty role) first");
        assertEquals(List.of("magistuarmory:norman_helmet"), head.get(1), "then the culture's flavour");
        List<List<String>> plain = p.candidates("millenaire:norman", MilitaryTier.GARRISON, "", "line", "head");
        assertEquals(List.of("magistuarmory:norman_helmet"), plain.get(0));
        assertTrue(p.candidates("millenaire:unknown", MilitaryTier.WATCH, "", "militia", "chest").size() >= 1, "defaults apply to any culture");
        assertTrue(p.candidates("millenaire:norman", MilitaryTier.NONE, "", "line", "head").isEmpty());
    }

    @Test
    void equipmentScalesWithTier() throws IOException {
        EquipmentProfiles p = shipped(new ArrayList<>());
        assertEquals(List.of("magistuarmory:gambeson_chestplate"), p.candidates("", MilitaryTier.WATCH, "", "line", "chest").get(0));
        assertTrue(p.candidates("", MilitaryTier.STRONGHOLD, "", "line", "chest").get(0).contains("magistuarmory:platemail_chestplate"));
        assertTrue(p.candidates("", MilitaryTier.WATCH, "", "line", "mainhand").get(0).stream().noneMatch(i -> i.contains("steel_")));
        assertTrue(p.candidates("", MilitaryTier.STRONGHOLD, "", "line", "mainhand").get(0).stream().allMatch(i -> i.contains("steel_")));
    }

    @Test
    void familiesStripTheMaterial() {
        assertEquals("kiteshield", EquipmentProfiles.family("magistuarmory:steel_kiteshield"));
        assertEquals("pike", EquipmentProfiles.family("magistuarmory:wood_pike"));
        assertEquals("longbow", EquipmentProfiles.family("magistuarmory:longbow"));
        assertEquals("heavy_crossbow", EquipmentProfiles.family("magistuarmory:heavy_crossbow"));
        assertEquals("lance", EquipmentProfiles.family("hundred_years_war:iron_lance"));
    }

    @Test
    void rolesMapFromDutiesAndClasses() {
        assertEquals("sentry", EquipmentProfiles.dutyRole(Duty.SENTRY));
        assertEquals("", EquipmentProfiles.dutyRole(Duty.GARRISON));
        assertEquals("", EquipmentProfiles.dutyRole(Duty.DEFENSE));
        assertEquals("militia", EquipmentProfiles.classRole(UnitClass.LEVY));
        assertEquals("ranged", EquipmentProfiles.classRole(UnitClass.RANGED));
        assertEquals("line", EquipmentProfiles.classRole(UnitClass.LINE));
    }

    @Test
    void badEntriesAreReported() {
        JsonObject f = JsonParser.parseString("""
                { "defaults": { "tiers": { "HUGE": {}, "WATCH": { "wizard": {}, "line": { "hat": ["a:b"], "head": ["nocolon", "a:b"] } } } } }
                """).getAsJsonObject();
        List<String> problems = new ArrayList<>();
        EquipmentProfiles p = EquipmentProfiles.fromJson(List.of(f), problems);
        assertEquals(4, problems.size(), problems.toString());
        assertEquals(List.of(List.of("a:b")), p.candidates("", MilitaryTier.WATCH, "", "line", "head"));
    }
}

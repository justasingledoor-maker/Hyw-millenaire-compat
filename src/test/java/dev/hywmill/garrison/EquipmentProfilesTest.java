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
        List<List<String>> off = p.candidates("millenaire:norman", MilitaryTier.GARRISON, "sentry", "line", "offhand");
        assertTrue(off.get(0).stream().allMatch(i -> i.contains("shield")), "sentry shields (defaults' duty role) first: " + off.get(0));
        List<List<EquipmentProfiles.Kit>> scout = p.kitCandidates("millenaire:norman", MilitaryTier.STRONGHOLD, "scout", "line");
        assertEquals("magistuarmory:gambeson_boots", scout.get(0).get(0).piece("feet"), "scouts ride light, whatever their culture");
        List<List<EquipmentProfiles.Kit>> line = p.kitCandidates("millenaire:norman", MilitaryTier.GARRISON, "sentry", "line");
        assertEquals("magistuarmory:norman_helmet", line.get(0).get(0).piece("head"), "no sentry kits: the culture's own kit");
        assertTrue(p.kitCandidates("millenaire:unknown", MilitaryTier.WATCH, "", "militia").size() >= 1, "defaults apply to any culture");
        assertTrue(p.kitCandidates("millenaire:norman", MilitaryTier.NONE, "", "line").isEmpty());
    }

    @Test
    void equipmentScalesWithTier() throws IOException {
        EquipmentProfiles p = shipped(new ArrayList<>());
        assertEquals("magistuarmory:gambeson_chestplate", p.kitCandidates("", MilitaryTier.WATCH, "", "line").get(0).get(0).piece("chest"));
        assertTrue(p.kitCandidates("", MilitaryTier.STRONGHOLD, "", "line").get(0).stream()
                .anyMatch(k -> k.piece("chest").equals("magistuarmory:platemail_chestplate")));
        assertTrue(p.candidates("", MilitaryTier.WATCH, "", "line", "mainhand").get(0).stream().noneMatch(i -> i.contains("steel_")));
        assertTrue(p.candidates("", MilitaryTier.STRONGHOLD, "", "line", "mainhand").get(0).stream().allMatch(i -> i.contains("steel_")));
    }

    /** The bug report: a plate helmet over plain cloth. Armour now comes from whole kits only: no per-slot armour lists remain. */
    @Test
    void armourComesFromWholeKitsThatMatch() throws IOException {
        List<String> problems = new ArrayList<>();
        EquipmentProfiles p = shipped(problems);
        assertEquals(List.of(), problems);
        java.util.Set<String> plate = java.util.Set.of("magistuarmory:greathelm", "magistuarmory:grand_bascinet", "magistuarmory:armet",
                "magistuarmory:bascinet", "magistuarmory:sallet");
        java.util.Set<String> cloth = java.util.Set.of("magistuarmory:gambeson_chestplate");
        int kits = 0;
        for (var c : p.rawKits().entrySet()) {
            for (var t : c.getValue().entrySet()) {
                for (var r : t.getValue().entrySet()) {
                    for (EquipmentProfiles.Kit k : r.getValue()) {
                        kits++;
                        for (String slot : EquipmentProfiles.ARMOUR) {
                            String id = k.piece(slot);
                            assertTrue(id.equals(EquipmentProfiles.NONE) || id.startsWith("magistuarmory:"), id);
                        }
                        assertFalse(plate.contains(k.piece("head")) && cloth.contains(k.piece("chest")),
                                "no plate helmet over cloth: " + c.getKey() + " " + t.getKey() + " " + r.getKey() + " " + k);
                    }
                }
            }
        }
        assertTrue(kits > 50, "every culture, tier and class has kits: " + kits);
        for (var c : p.raw().values()) {
            for (var t : c.values()) {
                for (var r : t.values()) {
                    for (String slot : EquipmentProfiles.ARMOUR) {
                        assertFalse(r.containsKey(slot), "armour comes from kits, not mixed slot lists");
                    }
                }
            }
        }
        assertEquals(12, p.palette("millenaire:norman").size(), "the shipped dye palette applies to every culture");
        assertTrue(p.heraldry());
    }

    @Test
    void kitsNeedEveryArmourSlotAndTheRevisionFollowsTheData() {
        JsonObject f = JsonParser.parseString("""
                { "defaults": { "tiers": { "WATCH": { "line": { "kits": [
                    { "head": "a:h", "chest": "a:c", "legs": "a:l", "feet": "none" },
                    { "head": "a:h", "chest": "a:c" } ] } } } } }
                """).getAsJsonObject();
        List<String> problems = new ArrayList<>();
        EquipmentProfiles p = EquipmentProfiles.fromJson(List.of(f), problems);
        assertEquals(1, problems.size(), "an incomplete kit is reported and ignored: " + problems);
        List<List<EquipmentProfiles.Kit>> k = p.kitCandidates("", MilitaryTier.WATCH, "", "line");
        assertEquals(1, k.get(0).size());
        assertEquals(EquipmentProfiles.NONE, k.get(0).get(0).piece("feet"));
        JsonObject g = JsonParser.parseString("""
                { "defaults": { "tiers": { "WATCH": { "line": { "kits": [
                    { "head": "a:h2", "chest": "a:c", "legs": "a:l", "feet": "none" } ] } } } } }
                """).getAsJsonObject();
        assertNotEquals(p.revision(), EquipmentProfiles.fromJson(List.of(g), new ArrayList<>()).revision(), "new data re-equips units");
        assertEquals(p.revision(), EquipmentProfiles.fromJson(List.of(f), new ArrayList<>()).revision(), "same data, same revision");
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

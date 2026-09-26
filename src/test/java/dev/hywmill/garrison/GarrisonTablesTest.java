package dev.hywmill.garrison;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hywmill.garrison.tables.GarrisonTable;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** The shipped hywmill_garrison data and the table loader's validation/merge rules. */
class GarrisonTablesTest {
    static final Path DIR = Path.of("src/main/resources/data/hywmill/hywmill_garrison");
    /** Registered HYW 0.7.1r-fix1 entity ids relevant here (audited with javap; cavalry/iron_mage are not registered). */
    static final Set<String> REGISTERED = Set.of("hundred_years_war:militia", "hundred_years_war:spear_man", "hundred_years_war:shieldman",
            "hundred_years_war:warrior", "hundred_years_war:archer", "hundred_years_war:crossbowman", "hundred_years_war:handgonne_man",
            "hundred_years_war:matchlock_man", "hundred_years_war:mounted_lancer_rider", "hundred_years_war:siege_engineer",
            "hundred_years_war:farmer", "hundred_years_war:bandit_soldier", "hundred_years_war:mounted_light_lancer_rider",
            "hundred_years_war:mounted_archer_rider");
    static final Predicate<String> VALID = id -> REGISTERED.contains(id) && Recruitment.allowedType(id);

    static JsonObject read(String name) throws IOException {
        return JsonParser.parseString(Files.readString(DIR.resolve(name))).getAsJsonObject();
    }

    static GarrisonTables shipped(List<String> problems) throws IOException {
        // resource-location order: defaults.json before units.json
        return GarrisonTables.fromJson(List.of(read("defaults.json"), read("units.json")), VALID, problems);
    }

    @Test
    void shippedDataLoadsWithoutProblems() throws IOException {
        List<String> problems = new ArrayList<>();
        GarrisonTables t = shipped(problems);
        // defaults.json is read before units.json, so its composition cannot see the units yet: the loader
        // must still resolve compositions against the final unit set.
        assertEquals(List.of(), problems);
        assertEquals(Set.of("militia", "spear_man", "shieldman", "warrior", "archer", "crossbowman", "handgonne_man", "matchlock_man",
                        "light_lancer_rider", "archer_rider"),
                t.units().keySet());
    }

    @Test
    void tierCapsAre0_8_16_32_64() throws IOException {
        GarrisonTable d = shipped(new ArrayList<>()).defaults();
        assertEquals(0, d.tier(MilitaryTier.NONE).maxUnits());
        assertEquals(8, d.tier(MilitaryTier.WATCH).maxUnits());
        assertEquals(16, d.tier(MilitaryTier.GUARD_POST).maxUnits());
        assertEquals(32, d.tier(MilitaryTier.GARRISON).maxUnits());
        assertEquals(64, d.tier(MilitaryTier.STRONGHOLD).maxUnits());
    }

    @Test
    void defaultsMatchTheAuthorization() throws IOException {
        GarrisonTable d = shipped(new ArrayList<>()).defaults();
        assertEquals(Map.of("militia", 1, "spear_man", 3, "archer", 2), d.composition());
        assertEquals(0.25, d.perCapacityDaily());
        assertEquals(0.5, d.startingFraction());
        assertEquals(2, d.commitPerThreat());
        assertEquals("hyw_profiles", d.equipmentProvider());
        assertEquals(0, d.tier(MilitaryTier.WATCH).equipmentLevel());
        assertEquals(1, d.tier(MilitaryTier.GUARD_POST).equipmentLevel());
        assertEquals(2, d.tier(MilitaryTier.GARRISON).equipmentLevel());
        assertEquals(3, d.tier(MilitaryTier.STRONGHOLD).equipmentLevel());
        assertEquals(Set.of(UnitClass.LEVY, UnitClass.RANGED), d.tier(MilitaryTier.WATCH).classes());
    }

    @Test
    void cultureCompositionsMatchTheAuthorization() throws IOException {
        GarrisonTables t = shipped(new ArrayList<>());
        assertEquals(Map.of("militia", 1, "spear_man", 3, "shieldman", 2, "crossbowman", 2, "archer", 1, "light_lancer_rider", 1), t.forCulture("millenaire:norman").composition());
        assertEquals(Map.of("militia", 1, "spear_man", 2, "shieldman", 3, "archer", 2, "light_lancer_rider", 1), t.forCulture("millenaire:byzantines").composition());
        assertEquals(Map.of("militia", 1, "spear_man", 2, "warrior", 1, "archer", 4, "archer_rider", 2), t.forCulture("millenaire:seljuk").composition());
        assertEquals(Map.of("militia", 1, "spear_man", 4, "archer", 3, "archer_rider", 1), t.forCulture("millenaire:japanese").composition());
        assertEquals(Map.of("militia", 2, "spear_man", 2, "warrior", 2, "archer", 2, "light_lancer_rider", 1), t.forCulture("millenaire:indian").composition());
        assertEquals(Map.of("militia", 2, "spear_man", 2, "warrior", 2, "archer", 2), t.forCulture("millenaire:mayan").composition());
        assertEquals(Map.of("militia", 3, "spear_man", 1, "archer", 2), t.forCulture("millenaire:inuits").composition());
        assertEquals(t.defaults().composition(), t.forCulture("millenaire:unknown").composition());
        // culture patches inherit tiers
        assertEquals(64, t.forCulture("millenaire:norman").tier(MilitaryTier.STRONGHOLD).maxUnits());
    }

    @Test
    void gunpowderIsDisabledByDefaultAndEveryUnitIsAnAllowedType() throws IOException {
        GarrisonTables t = shipped(new ArrayList<>());
        for (UnitSpec u : t.units().values()) {
            assertTrue(Recruitment.allowedType(u.entityType()), u.key());
            assertEquals(u.unitClass() == UnitClass.CAVALRY, Recruitment.M4_CAVALRY_TYPES.contains(u.entityType()), u.key());
            assertEquals(u.unitClass() != UnitClass.GUNPOWDER, u.enabled(), u.key());
        }
    }

    @Test
    void invalidAndExcludedEntityTypesAreSkippedWithAWarning() {
        JsonObject f = JsonParser.parseString("""
                { "units": {
                    "cav":   { "entity": "hundred_years_war:cavalry", "class": "LINE", "cost": 2 },
                    "mage":  { "entity": "hundred_years_war:iron_mage", "class": "RANGED", "cost": 2 },
                    "horse": { "entity": "hundred_years_war:mounted_lancer_rider", "class": "LINE", "cost": 2 },
                    "siege": { "entity": "hundred_years_war:siege_engineer", "class": "LINE", "cost": 2 },
                    "bad":   { "entity": "hundred_years_war:archer", "class": "WIZARD", "cost": 2 },
                    "ok":    { "entity": "hundred_years_war:archer", "class": "RANGED", "cost": 2 } },
                  "defaults": { "composition": { "cav": 5, "ok": 1, "nope": 3 } } }""").getAsJsonObject();
        List<String> problems = new ArrayList<>();
        GarrisonTables t = GarrisonTables.fromJson(List.of(f), VALID, problems);
        assertEquals(Set.of("ok"), t.units().keySet());
        assertEquals(Map.of("ok", 1), t.defaults().composition());
        assertTrue(problems.size() >= 7, problems.toString());
    }

    @Test
    void laterFilesOverrideEarlierOnesKeyByKey() {
        JsonObject a = JsonParser.parseString("""
                { "units": { "ok": { "entity": "hundred_years_war:archer", "class": "RANGED", "cost": 2 } },
                  "defaults": { "commitPerThreat": 2, "tiers": { "WATCH": { "maxUnits": 8, "perCapacity": 1.0, "classes": ["RANGED"] } } } }""").getAsJsonObject();
        JsonObject b = JsonParser.parseString("""
                { "defaults": { "tiers": { "WATCH": { "maxUnits": 12 } } },
                  "cultures": { "millenaire:norman": { "commitPerThreat": 4 } } }""").getAsJsonObject();
        GarrisonTables t = GarrisonTables.fromJson(List.of(a, b), VALID, new ArrayList<>());
        assertEquals(12, t.defaults().tier(MilitaryTier.WATCH).maxUnits());
        assertEquals(1.0, t.defaults().tier(MilitaryTier.WATCH).perCapacity());
        assertEquals(4, t.forCulture("millenaire:norman").commitPerThreat());
        assertEquals(2, t.defaults().commitPerThreat());
        // same inputs, same result
        GarrisonTables t2 = GarrisonTables.fromJson(List.of(a, b), VALID, new ArrayList<>());
        assertEquals(t.defaults(), t2.defaults());
    }

    @Test
    void outOfRangeValuesFallBackAndWarn() {
        JsonObject a = JsonParser.parseString("""
                { "defaults": { "startingFraction": 2.5, "tiers": { "WATCH": { "equipmentLevel": 9, "maxUnits": -1 } } } }""").getAsJsonObject();
        List<String> problems = new ArrayList<>();
        GarrisonTables t = GarrisonTables.fromJson(List.of(a), VALID, problems);
        assertEquals(0, t.defaults().startingFraction());
        assertEquals(0, t.defaults().tier(MilitaryTier.WATCH).equipmentLevel());
        assertEquals(3, problems.size(), problems.toString());
    }
}

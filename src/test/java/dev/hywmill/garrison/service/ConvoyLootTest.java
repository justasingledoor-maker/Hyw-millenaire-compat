package dev.hywmill.garrison.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hywmill.garrison.equip.EquipmentProfiles;
import dev.hywmill.military.MilitaryTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5: the weapons a supply convoy carries, the culture's own (Cultures Armory's when it is installed). */
class ConvoyLootTest {
    static EquipmentProfiles shipped() throws IOException {
        JsonObject f = JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/hywmill/hywmill_equipment/profiles.json")))
                .getAsJsonObject();
        List<String> problems = new ArrayList<>();
        EquipmentProfiles p = EquipmentProfiles.fromJson(List.of(f), problems);
        assertEquals(List.of(), problems);
        return p;
    }

    @Test
    void withTheArmoryConvoysCarryTheCulturesOwnWeapons() throws IOException {
        EquipmentProfiles p = shipped();
        List<String> seljuk = ConvoyLoot.weaponPool(p, "millenaire:seljuk", MilitaryTier.GUARD_POST, id -> true);
        assertTrue(seljuk.containsAll(List.of("culturesarmory:steel_kilij", "culturesarmory:steel_mizrak", "culturesarmory:turkic_bow")), seljuk.toString());
        assertTrue(seljuk.stream().allMatch(id -> id.startsWith("culturesarmory:")), "the armory's weapons over Epic Knights': " + seljuk);
        List<String> inuit = ConvoyLoot.weaponPool(p, "millenaire:inuits", MilitaryTier.GARRISON, id -> true);
        assertTrue(inuit.containsAll(List.of("culturesarmory:harpoon", "culturesarmory:bone_club", "culturesarmory:savik", "culturesarmory:sinew_bow")),
                inuit.toString());
        List<String> byz = ConvoyLoot.weaponPool(p, "millenaire:byzantines", MilitaryTier.GARRISON, id -> true);
        assertTrue(byz.containsAll(List.of("culturesarmory:steel_kontarion", "culturesarmory:steel_spathion", "culturesarmory:toxarion")),
                byz.toString());
        List<String> japan = ConvoyLoot.weaponPool(p, "millenaire:japanese", MilitaryTier.GARRISON, id -> true);
        assertTrue(japan.containsAll(List.of("culturesarmory:steel_katana", "culturesarmory:yumi")), japan.toString());
    }

    @Test
    void withoutItTheyCarryEpicKnightsWeaponsAndOtherCulturesNeverTheArmorys() throws IOException {
        EquipmentProfiles p = shipped();
        List<String> seljuk = ConvoyLoot.weaponPool(p, "millenaire:seljuk", MilitaryTier.GUARD_POST, id -> !id.startsWith("culturesarmory:"));
        assertFalse(seljuk.isEmpty());
        assertTrue(seljuk.stream().allMatch(id -> id.startsWith("magistuarmory:")), seljuk.toString());
        List<String> norman = ConvoyLoot.weaponPool(p, "millenaire:norman", MilitaryTier.GARRISON, id -> true);
        assertFalse(norman.isEmpty());
        assertTrue(norman.stream().noneMatch(id -> id.startsWith("culturesarmory:")), norman.toString());
    }
}

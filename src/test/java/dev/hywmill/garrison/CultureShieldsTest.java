package dev.hywmill.garrison;

import com.google.gson.JsonParser;
import dev.hywmill.garrison.equip.EquipmentProfiles;
import dev.hywmill.garrison.equip.SpearShield;
import dev.hywmill.military.MilitaryTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Post-M5 Cultures Armory 1.4: each culture's own shields. Only Normans carry heater shields; the Inuit and samurai carry
 * none (an empty off hand), Byzantines and Seljuks the armory's shields with Epic Knights ones beside them.
 */
class CultureShieldsTest {
    static EquipmentProfiles shipped() throws IOException {
        Path eq = Path.of("src/main/resources/data/hywmill/hywmill_equipment");
        List<String> problems = new ArrayList<>();
        EquipmentProfiles p = EquipmentProfiles.fromJson(List.of(
                JsonParser.parseString(Files.readString(eq.resolve("profiles.json"))).getAsJsonObject(),
                JsonParser.parseString(Files.readString(eq.resolve("squad_looks.json"))).getAsJsonObject()), problems);
        assertEquals(List.of(), problems);
        return p;
    }

    /** The off hand list a unit takes: the first candidate list (a duty role's, then its class role's). */
    static List<String> first(EquipmentProfiles p, String culture, MilitaryTier tier, String duty, String classRole) {
        List<List<String>> c = p.candidates(culture, tier, duty, classRole, "offhand");
        return c.isEmpty() ? List.of() : c.get(0);
    }

    @Test
    void onlyNormansCarryHeaterShields() throws IOException {
        EquipmentProfiles p = shipped();
        for (String c : List.of("millenaire:byzantines", "millenaire:seljuk", "millenaire:indian", "millenaire:japanese", "millenaire:mayan",
                "millenaire:inuits")) {
            for (MilitaryTier t : List.of(MilitaryTier.WATCH, MilitaryTier.GUARD_POST, MilitaryTier.GARRISON, MilitaryTier.STRONGHOLD)) {
                for (String duty : List.of("sentry", "levy", "patrol", "garrison", "scout")) {
                    for (String cls : List.of("line", "scout", "militia", "ranged")) {
                        List<String> l = first(p, c, t, duty, cls);
                        assertFalse(l.isEmpty(), c + " " + t + " " + duty + " " + cls + " has its own off hand list");
                        assertTrue(l.stream().noneMatch(i -> i.contains("heatershield")), c + " " + t + " " + duty + " " + cls + ": " + l);
                    }
                }
            }
        }
        for (var l : p.looks().entrySet()) {
            // Latin squads and companies carry Latin shields: the Normans (their king too), the Latinikon, the Brabançon routiers
            if (!l.getKey().startsWith("norman.") && !l.getKey().equals("ruler.norman") && !l.getKey().equals("byz.latinikon")
                    && !l.getKey().equals("merc.routiers")) {
                List<String> off = l.getValue().items().getOrDefault("offhand", List.of());
                assertTrue(off.stream().noneMatch(i -> i.contains("heatershield")), l.getKey() + ": " + off);
            }
        }
    }

    @Test
    void theInuitAndSamuraiCarryNoShield() throws IOException {
        EquipmentProfiles p = shipped();
        for (MilitaryTier t : List.of(MilitaryTier.WATCH, MilitaryTier.STRONGHOLD)) {
            for (String duty : List.of("sentry", "levy", "patrol")) {
                for (String cls : List.of("line", "scout")) {
                    assertEquals(List.of(EquipmentProfiles.NONE), first(p, "millenaire:inuits", t, duty, cls));
                }
                assertEquals(List.of(EquipmentProfiles.NONE), first(p, "millenaire:japanese", t, "patrol", "scout"), "mounted samurai");
            }
            // ashigaru shieldmen: the tedate (none without the armory)
            assertEquals(List.of("culturesarmory:tedate", EquipmentProfiles.NONE), first(p, "millenaire:japanese", t, "patrol", "line"));
        }
        for (String look : List.of("inuit.ice_wall", "inuit.winter_warband", "jp.mounted_samurai", "jp.daimyo_guard")) {
            assertEquals(List.of(EquipmentProfiles.NONE), p.looks().get(look).items().get("offhand"), look);
        }
    }

    @Test
    void byzantinesAndSeljuksCarryTheirOwnShieldsWithEpicKnightsBeside() throws IOException {
        EquipmentProfiles p = shipped();
        List<String> b = first(p, "millenaire:byzantines", MilitaryTier.GARRISON, "patrol", "line");
        assertTrue(b.containsAll(List.of("culturesarmory:skoutarion", "culturesarmory:aspis")), b.toString());
        assertTrue(b.stream().anyMatch(i -> i.startsWith("magistuarmory:")), b.toString());
        List<String> s = first(p, "millenaire:seljuk", MilitaryTier.GARRISON, "sentry", "line");
        assertTrue(s.containsAll(List.of("culturesarmory:kalkan", "culturesarmory:sipar")), s.toString());
        assertEquals("culturesarmory:varangian_shield", p.looks().get("byz.varangians").items().get("offhand").get(0));
        // the shields count as the shapes HYW gives
        assertEquals("kiteshield", EquipmentProfiles.family("culturesarmory:skoutarion"));
        assertEquals("roundshield", EquipmentProfiles.family("culturesarmory:aspis"));
        assertEquals("roundshield", EquipmentProfiles.family("culturesarmory:varangian_shield"));
        assertEquals("roundshield", EquipmentProfiles.family("culturesarmory:kalkan"));
        assertEquals("roundshield", EquipmentProfiles.family("culturesarmory:sipar"));
        assertEquals("kiteshield", EquipmentProfiles.family("culturesarmory:tedate"));
    }

    @Test
    void spearmenTakeTheirCulturesOwnShield() {
        int skoutarion = 0, aspis = 0, kalkan = 0, sipar = 0;
        for (int i = 0; i < 600; i++) {
            UUID id = UUID.nameUUIDFromBytes(("sp" + i).getBytes());
            String b = SpearShield.armory("millenaire:byzantines", id);
            skoutarion += "culturesarmory:skoutarion".equals(b) ? 1 : 0;
            aspis += "culturesarmory:aspis".equals(b) ? 1 : 0;
            String s = SpearShield.armory("millenaire:seljuk", id);
            kalkan += "culturesarmory:kalkan".equals(s) ? 1 : 0;
            sipar += "culturesarmory:sipar".equals(s) ? 1 : 0;
            assertNull(SpearShield.armory("millenaire:norman", id));
            assertNull(SpearShield.armory("millenaire:inuits", id));
        }
        assertEquals(600, skoutarion + aspis);
        assertTrue(skoutarion > aspis && aspis > 100, skoutarion + " / " + aspis);
        assertEquals(600, kalkan + sipar);
        assertTrue(kalkan > 200 && sipar > 200, kalkan + " / " + sipar);
    }
}

package dev.hywmill.garrison;

import dev.hywmill.garrison.equip.EquipmentProfiles;
import dev.hywmill.garrison.equip.SpearShield;
import dev.hywmill.military.MilitaryTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Post-M5 Cultures Armory 1.5: every culture dresses and arms each HYW unit as its own army did, at every tier
 * ("unit:spear_man" lists between the duty role and the class role), with Epic Knights kits beside the armory's.
 */
class UnitKitsTest {
    static final List<String> CULTURES = List.of("millenaire:norman", "millenaire:byzantines", "millenaire:seljuk", "millenaire:indian",
            "millenaire:japanese", "millenaire:mayan", "millenaire:inuits");
    static final List<MilitaryTier> TIERS = List.of(MilitaryTier.WATCH, MilitaryTier.GUARD_POST, MilitaryTier.GARRISON, MilitaryTier.STRONGHOLD);
    static final Map<String, String> UNITS = Map.ofEntries(Map.entry("militia", "militia"), Map.entry("spear_man", "line"),
            Map.entry("shieldman", "line"), Map.entry("warrior", "line"), Map.entry("archer", "ranged"), Map.entry("crossbowman", "ranged"),
            Map.entry("light_lancer_rider", "scout"), Map.entry("archer_rider", "scout"), Map.entry("lancer_rider", "scout"));
    /** The Epic Knights families HYW itself gives each unit's main hand (its equipment_epic_knights.json). */
    static final Map<String, Set<String>> MAIN = Map.of(
            "militia", Set.of("barbedclub", "blacksmith_hammer", "club", "katzbalger", "messer_sword", "noble_sword", "pitchfork", "shortsword"),
            "spear_man", Set.of("ahlspiess", "pike"), "shieldman", Set.of("katzbalger", "shortsword"),
            "warrior", Set.of("bastardsword", "claymore", "heavymace", "lochaberaxe"), "archer", Set.of("longbow"),
            "crossbowman", Set.of("heavy_crossbow"));

    static boolean armoryKit(EquipmentProfiles.Kit k) {
        return EquipmentProfiles.ARMOUR.stream().anyMatch(s -> k.piece(s).startsWith("culturesarmory:"));
    }

    @Test
    void everyCultureDressesEveryUnitAtEveryTier() throws IOException {
        EquipmentProfiles p = CultureShieldsTest.shipped();
        Set<String> chests = new HashSet<>();
        for (String c : CULTURES) {
            for (MilitaryTier t : TIERS) {
                for (var u : UNITS.entrySet()) {
                    List<List<EquipmentProfiles.Kit>> withUnit = p.kitCandidates(c, t, "", u.getKey(), u.getValue());
                    List<EquipmentProfiles.Kit> own = p.rawKits().get(c).get(t.name()).get(EquipmentProfiles.unitRole(u.getKey()));
                    assertNotNull(own, c + " " + t + " " + u.getKey());
                    assertSame(own, withUnit.get(0), "the unit's own kits come first for " + c + " " + t + " " + u.getKey());
                    // it still dresses without the armory, and without Epic Knights' add-ons
                    assertTrue(own.stream().anyMatch(k -> !armoryKit(k)), "Epic Knights kit for " + c + " " + t + " " + u.getKey());
                    assertTrue(own.stream().anyMatch(k -> EquipmentProfiles.ARMOUR.stream()
                            .allMatch(s -> k.piece(s).startsWith("magistuarmory:") || k.piece(s).equals(EquipmentProfiles.NONE))),
                            "a kit of Epic Knights' own pieces for " + c + " " + t + " " + u.getKey());
                    if (!c.equals("millenaire:norman")) {
                        assertTrue(armoryKit(own.get(0)), "the culture's own armour first for " + c + " " + t + " " + u.getKey());
                    }
                    own.stream().filter(UnitKitsTest::armoryKit).forEach(k -> chests.add(k.piece("chest")));
                }
            }
        }
        assertTrue(chests.size() >= 50, "the armies wear " + chests.size() + " different coats");
    }

    @Test
    void theUnitsWeaponsAreTheFamiliesHywGivesThem() throws IOException {
        EquipmentProfiles p = CultureShieldsTest.shipped();
        for (String c : CULTURES) {
            for (MilitaryTier t : TIERS) {
                for (var u : MAIN.entrySet()) {
                    List<String> main = p.raw().get(c).get(t.name()).getOrDefault(EquipmentProfiles.unitRole(u.getKey()), Map.of()).get("mainhand");
                    if (main == null) {
                        continue;
                    }
                    for (String i : main) {
                        assertTrue(u.getValue().contains(EquipmentProfiles.family(i)), c + " " + t + " " + u.getKey() + ": " + i + " is "
                                + EquipmentProfiles.family(i));
                    }
                    assertTrue(main.stream().anyMatch(i -> i.startsWith("magistuarmory:")), "Epic Knights weapon beside " + main);
                }
            }
        }
        // the cultures' signature arms, where their soldiers were known for them
        assertEquals("culturesarmory:steel_barchha", unitList(p, "millenaire:indian", MilitaryTier.GARRISON, "spear_man", "mainhand").get(0));
        assertEquals("culturesarmory:dhal", unitList(p, "millenaire:indian", MilitaryTier.GARRISON, "shieldman", "offhand").get(0));
        assertEquals("culturesarmory:macuahuitl", unitList(p, "millenaire:mayan", MilitaryTier.WATCH, "shieldman", "mainhand").get(0));
        assertEquals("culturesarmory:pakal", unitList(p, "millenaire:mayan", MilitaryTier.WATCH, "shieldman", "offhand").get(0));
        assertEquals("culturesarmory:steel_yari", unitList(p, "millenaire:japanese", MilitaryTier.STRONGHOLD, "spear_man", "mainhand").get(0));
        assertEquals(List.of(EquipmentProfiles.NONE), unitList(p, "millenaire:inuits", MilitaryTier.STRONGHOLD, "shieldman", "offhand"));
        assertEquals("culturesarmory:harpoon", unitList(p, "millenaire:inuits", MilitaryTier.WATCH, "spear_man", "mainhand").get(0));
        // the new weapons count as the families HYW gives
        assertEquals("pike", EquipmentProfiles.family("culturesarmory:steel_barchha"));
        assertEquals("katzbalger", EquipmentProfiles.family("culturesarmory:steel_talwar"));
        assertEquals("bastardsword", EquipmentProfiles.family("culturesarmory:steel_khanda"));
        assertEquals("heavymace", EquipmentProfiles.family("culturesarmory:steel_gada"));
        assertEquals("longbow", EquipmentProfiles.family("culturesarmory:dhanush"));
        assertEquals("katzbalger", EquipmentProfiles.family("culturesarmory:macuahuitl"));
        assertEquals("pike", EquipmentProfiles.family("culturesarmory:flint_spear"));
        assertEquals("roundshield", EquipmentProfiles.family("culturesarmory:pakal"));
        assertEquals("kiteshield", EquipmentProfiles.family("culturesarmory:flexible_shield"));
        assertEquals("lochaberaxe", EquipmentProfiles.family("culturesarmory:steel_nagamaki"));
    }

    static List<String> unitList(EquipmentProfiles p, String c, MilitaryTier t, String unit, String slot) {
        return p.candidates(c, t, "", unit, UNITS.get(unit), slot).get(0);
    }

    @Test
    void dutyRolesComeFirstAndAUnitWithoutListsFallsToItsClass() throws IOException {
        EquipmentProfiles p = CultureShieldsTest.shipped();
        // mobilized levies wear the levy kits, whatever the unit
        List<EquipmentProfiles.Kit> levy = p.rawKits().get("millenaire:byzantines").get("GARRISON").get("levy");
        assertSame(levy, p.kitCandidates("millenaire:byzantines", MilitaryTier.GARRISON, "levy", "spear_man", "line").get(0));
        // a unit HYW adds later (no lists of its own) takes its class's
        assertEquals(p.kitCandidates("millenaire:japanese", MilitaryTier.GARRISON, "", "line"),
                p.kitCandidates("millenaire:japanese", MilitaryTier.GARRISON, "", "halberdier", "line"));
        assertEquals(p.candidates("millenaire:seljuk", MilitaryTier.STRONGHOLD, "", "line", "mainhand"),
                p.candidates("millenaire:seljuk", MilitaryTier.STRONGHOLD, "", "halberdier", "line", "mainhand"));
        // the gunpowder units (disabled by default): matchlock ashigaru in Japan, toradar men in India, the class elsewhere
        assertTrue(armoryKit(p.kitCandidates("millenaire:japanese", MilitaryTier.GARRISON, "", "matchlock_man", "ranged").get(0).get(0)));
        assertTrue(armoryKit(p.kitCandidates("millenaire:indian", MilitaryTier.GARRISON, "", "handgonne_man", "ranged").get(0).get(0)));
        assertNull(p.rawKits().get("millenaire:byzantines").get("GARRISON").get("unit:handgonne_man"));
        assertEquals("", EquipmentProfiles.unitRole(""));
        assertEquals("unit:archer", EquipmentProfiles.unitRole("archer"));
    }

    @Test
    void indianAndMayaSpearmenCarryTheirOwnShields() {
        int pakal = 0, flexible = 0;
        for (int i = 0; i < 300; i++) {
            UUID id = UUID.nameUUIDFromBytes(("u" + i).getBytes());
            assertEquals("culturesarmory:dhal", SpearShield.armory("millenaire:indian", id));
            String m = SpearShield.armory("millenaire:mayan", id);
            pakal += "culturesarmory:pakal".equals(m) ? 1 : 0;
            flexible += "culturesarmory:flexible_shield".equals(m) ? 1 : 0;
        }
        assertEquals(300, pakal + flexible);
        assertTrue(pakal > flexible && flexible > 50, pakal + " / " + flexible);
    }

    @Test
    void indianAndMayaSquadsWearTheirOwnArmour() throws IOException {
        EquipmentProfiles p = CultureShieldsTest.shipped();
        for (var l : p.looks().entrySet()) {
            if (l.getKey().startsWith("in.") || l.getKey().startsWith("maya.")) {
                assertTrue(armoryKit(l.getValue().kits().get(0)), l.getKey());
                assertTrue(l.getValue().kits().stream().anyMatch(k -> !armoryKit(k)), l.getKey() + " keeps its Epic Knights kits");
            }
        }
        assertEquals("culturesarmory:jaguar_pelt_armour", p.looks().get("maya.jaguar").kits().get(0).piece("chest"));
        assertEquals("culturesarmory:dhal", p.looks().get("in.dhal").items().get("offhand").get(0));
        assertEquals(EquipmentProfiles.NONE, p.looks().get("in.nayar").kits().get(0).piece("chest"), "the Nayar fought unarmoured");
    }
}

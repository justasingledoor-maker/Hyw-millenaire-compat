package dev.hywmill.garrison;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 period horse armour: leather for the low and light, mail, then plate; never gold or diamond. */
class HorseArmourTest {
    static String choose(int level, boolean light, long seed, boolean ek, boolean addon) {
        return dev.hywmill.garrison.equip.HorseArmour.choose(level, light, seed, ek, addon);
    }

    @Test
    void ladderFitsTheSetting() {
        assertEquals("minecraft:leather_horse_armor", choose(1, false, 0, true, false));
        assertEquals("magistuarmory:chainmail_horse_armor", choose(2, false, 0, true, false));
        assertEquals("minecraft:leather_horse_armor", choose(2, true, 0, true, false), "light horse stays in leather");
        assertEquals("magistuarmory:barding", choose(3, false, 1, true, false));
        assertEquals("magistuarmory:chainmail_horse_armor", choose(3, true, 1, true, false));
        assertEquals("magistuarmoryaddon:dark_barding", choose(3, false, 3, true, true), "now and then the Addon's dark barding");
        for (int level = 0; level <= 3; level++) {
            for (boolean light : new boolean[]{false, true}) {
                for (boolean ek : new boolean[]{false, true}) {
                    String a = choose(level, light, level * 7L, ek, ek);
                    assertFalse(a.contains("diamond") || a.contains("golden"), a);
                }
            }
        }
    }

    @Test
    void byzantineRidersRideByzantineHorses() {
        String kat = "culturesarmory:kataphract_armour", kli = "culturesarmory:klibanion";
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.CATAPHRACT, dev.hywmill.garrison.equip.HorseArmour.choose(kat, 3, false, 0, true, false));
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.HALF, dev.hywmill.garrison.equip.HorseArmour.choose(kli, 3, false, 0, true, false),
                "a klibanion rider's heavy horse: the scale peytral");
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.HALF, dev.hywmill.garrison.equip.HorseArmour.choose(kat, 2, false, 0, true, false));
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.FELT, dev.hywmill.garrison.equip.HorseArmour.choose(kli, 2, true, 0, true, false),
                "light horse: quilted felt");
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.FELT, dev.hywmill.garrison.equip.HorseArmour.choose(kat, 1, false, 0, true, false));
        assertTrue(dev.hywmill.garrison.equip.HorseArmour.dyed(dev.hywmill.garrison.equip.HorseArmour.FELT));
        assertEquals("magistuarmory:barding", dev.hywmill.garrison.equip.HorseArmour.choose("magistuarmory:crusader_chestplate", 3, false, 1, true, false),
                "any other rider: the ladder as before");
        assertEquals(choose(0, false, 0, true, false), dev.hywmill.garrison.equip.HorseArmour.choose(kat, 0, false, 0, true, false),
                "level 0: as before");
    }

    @Test
    void seljukAndIndianRidersRideTheirOwnHorses() {
        String jawshan = "culturesarmory:jawshan", kaftan = "culturesarmory:kaftan", char_aina = "culturesarmory:char_aina",
                chilta = "culturesarmory:chilta_hazar_masha";
        // the ghulams' heavy horse in lamellar, horse archers and the Turkmen in the felt cloth
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.TIJFAF, dev.hywmill.garrison.equip.HorseArmour.choose(jawshan, 3, false, 0, true, false));
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.TIJFAF, dev.hywmill.garrison.equip.HorseArmour.choose(jawshan, 2, false, 0, true, false));
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.JUL, dev.hywmill.garrison.equip.HorseArmour.choose(jawshan, 3, true, 0, true, false));
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.JUL, dev.hywmill.garrison.equip.HorseArmour.choose(kaftan, 3, false, 0, true, false));
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.JUL, dev.hywmill.garrison.equip.HorseArmour.choose(jawshan, 1, false, 0, true, false));
        // the mansabdars' heavy cavalry in the bargustawan, the Rajput horse in quilted chilta
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.BARGUSTAWAN, dev.hywmill.garrison.equip.HorseArmour.choose(char_aina, 3, false, 0, true, false));
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.CHILTA, dev.hywmill.garrison.equip.HorseArmour.choose(char_aina, 3, true, 0, true, false));
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.CHILTA, dev.hywmill.garrison.equip.HorseArmour.choose(chilta, 3, false, 0, true, false));
        assertTrue(dev.hywmill.garrison.equip.HorseArmour.dyed(dev.hywmill.garrison.equip.HorseArmour.JUL));
        assertTrue(dev.hywmill.garrison.equip.HorseArmour.dyed(dev.hywmill.garrison.equip.HorseArmour.CHILTA));
        assertFalse(dev.hywmill.garrison.equip.HorseArmour.dyed(dev.hywmill.garrison.equip.HorseArmour.BARGUSTAWAN));
        // 1.5's Byzantine coats bring the Byzantine horse
        assertEquals(dev.hywmill.garrison.equip.HorseArmour.FELT, dev.hywmill.garrison.equip.HorseArmour.choose("culturesarmory:akrites_lamellar", 2, true, 0, true, false));
    }
}

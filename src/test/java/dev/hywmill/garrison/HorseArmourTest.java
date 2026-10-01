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
}

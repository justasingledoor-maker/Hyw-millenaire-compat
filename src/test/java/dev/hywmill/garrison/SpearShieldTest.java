package dev.hywmill.garrison;

import dev.hywmill.garrison.equip.SpearShield;
import dev.hywmill.military.MilitaryTier;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 spearmen's shields: most carry one of their culture's shape and their tier's material; Japanese and Inuit none. */
class SpearShieldTest {
    static UUID id(int i) {
        return UUID.nameUUIDFromBytes(("s" + i).getBytes());
    }

    @Test
    void cultureShapesAndTierMaterials() {
        int carried = 0;
        for (int i = 0; i < 2000; i++) {
            String s = SpearShield.choose("millenaire:norman", MilitaryTier.WATCH, id(i));
            if (s != null) {
                carried++;
                assertEquals("magistuarmory:wood_kiteshield", s, "Norman levies: wooden kite shields");
            }
            String b = SpearShield.choose("millenaire:byzantines", MilitaryTier.STRONGHOLD, id(i));
            assertTrue(b == null || b.equals("magistuarmory:steel_ellipticalshield") || b.equals("magistuarmory:steel_roundshield"), b);
            String m = SpearShield.choose("millenaire:mayan", MilitaryTier.STRONGHOLD, id(i));
            assertTrue(m == null || m.equals("magistuarmory:wood_roundshield"), "Maya: wood: " + m);
            assertNull(SpearShield.choose("millenaire:japanese", MilitaryTier.GARRISON, id(i)), "yari ashigaru use both hands");
            assertNull(SpearShield.choose("millenaire:inuits", MilitaryTier.GARRISON, id(i)));
        }
        assertTrue(carried > 1350 && carried < 1650, "about 75%: " + carried);
        assertEquals(1, SpearShield.armour("magistuarmory:wood_kiteshield"));
        assertEquals(2, SpearShield.armour("magistuarmory:iron_roundshield"));
        assertEquals(3, SpearShield.armour("magistuarmory:steel_heatershield"));
        assertTrue(SpearShield.spearman("hundred_years_war:spear_man"));
    }
}

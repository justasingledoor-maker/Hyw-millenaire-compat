package dev.hywmill.garrison;

import dev.hywmill.garrison.equip.Pavise;
import dev.hywmill.military.MilitaryTier;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 crossbowmen's pavises: most carry one, of their tier; carried, it gives a little armour. */
class PaviseTest {
    @Test
    void mostCrossbowmenCarryOneOfTheirTier() {
        int carried = 0;
        for (int i = 0; i < 2000; i++) {
            UUID id = UUID.nameUUIDFromBytes(("x" + i).getBytes());
            String p = Pavise.choose(MilitaryTier.STRONGHOLD, id);
            if (p != null) {
                carried++;
                assertEquals("magistuarmory:steel_pavese", p);
                assertEquals(p, Pavise.choose(MilitaryTier.STRONGHOLD, id), "stable per soldier");
            }
        }
        assertTrue(carried > 1050 && carried < 1350, "about 60%: " + carried);
        UUID any = UUID.nameUUIDFromBytes("x1".getBytes());
        for (int i = 0; Pavise.choose(MilitaryTier.WATCH, any) == null; i++) {
            any = UUID.nameUUIDFromBytes(("y" + i).getBytes());
        }
        assertEquals("magistuarmory:wood_pavese", Pavise.choose(MilitaryTier.WATCH, any));
        assertEquals(2, Pavise.armour("magistuarmory:wood_pavese"));
        assertEquals(3, Pavise.armour("magistuarmory:iron_pavese"));
        assertEquals(4, Pavise.armour("magistuarmory:steel_pavese"));
        assertTrue(Pavise.crossbowman("hundred_years_war:crossbowman"));
        assertFalse(Pavise.crossbowman("hundred_years_war:archer"));
    }
}

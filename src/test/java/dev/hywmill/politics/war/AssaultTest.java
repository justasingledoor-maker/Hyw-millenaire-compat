package dev.hywmill.politics.war;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssaultTest {
    @Test
    void theScreensChoicesPackIntoOneNumber() {
        for (int side = 0; side < Assault.SIDES.length; side++) {
            for (int m = 0; m < Assault.MINUTES.length; m++) {
                for (boolean help : new boolean[]{true, false}) {
                    int packed = Assault.pack(side, m, help);
                    assertEquals(side, Assault.side(packed));
                    assertEquals(Assault.MINUTES[m], Assault.minutes(packed));
                    assertEquals(help, Assault.help(packed));
                }
            }
        }
        // out-of-range choices wrap rather than spill into the other fields
        assertEquals(1, Assault.side(Assault.pack(9, 0, false)));
        assertFalse(Assault.help(Assault.pack(7, 4, false)));
    }

    @Test
    void rallyPointsLieRoundTheCompass() {
        double[] east = Assault.direction(0), south = Assault.direction(2), west = Assault.direction(4), north = Assault.direction(6);
        assertEquals(1, east[0], 1e-9);
        assertEquals(0, east[1], 1e-9);
        assertEquals(1, south[1], 1e-9); // south is +z
        assertEquals(-1, west[0], 1e-9);
        assertEquals(-1, north[1], 1e-9);
        double[] se = Assault.direction(1);
        assertTrue(se[0] > 0.7 && se[1] > 0.7);
        assertEquals("south-east", Assault.SIDE_NAMES[1]);
    }

    @Test
    void anAssaultIsActiveUntilItEnds() {
        Assault a = new Assault(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 3, true, 100, 100 + 5 * 1200);
        assertTrue(a.active());
        assertEquals("south-west", a.sideName());
        a.phase = Assault.Phase.DONE;
        assertFalse(a.active());
    }

    @Test
    void aBandThrownBackRegroupsBeforeItGivesUp() {
        // 20 men at regular resolve: one assault breaks after a fifth fall, the band gives up only at its campaign's worth
        double resolve = Morale.Grade.REGULAR.resolve;
        int once = Morale.breakAt(20, resolve), campaign = Morale.breakAt(20, Morale.campaign(resolve));
        assertTrue(once >= 2 && once <= 6, "one assault breaks at " + once);
        assertTrue(campaign > once, "the band holds on through more than one repulse");
        assertTrue(campaign < 20, "but never to the last man");
    }
}

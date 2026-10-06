package dev.hywmill.politics.war;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 siege mercenaries and group deployment: the pure rules. */
class SiegeExtrasTest {
    @Test
    void mercenariesAreRareAndTenToTwenty() {
        int hired = 0;
        for (long seed = 0; seed < 2000; seed++) {
            Mercenaries.Hire h = Mercenaries.roll(seed * 7919, Mercenaries.CHANCE);
            if (h != null) {
                hired++;
                assertTrue(h.units().size() >= Mercenaries.MIN && h.units().size() <= Mercenaries.MAX);
                assertTrue(h.company().units().containsAll(h.units()));
            }
        }
        assertTrue(hired > 800 && hired < 1000, "about 45% of sieges: " + hired);
        assertNull(Mercenaries.roll(1, 0));
        assertNotNull(Mercenaries.hire(1), "an admin can always force one");
        assertEquals(Mercenaries.hire(42), Mercenaries.hire(42), "stable for a seed");
    }

    @Test
    void hostSplitsIntoGroupsOfFourToEight() {
        for (int n = 1; n <= 80; n++) {
            int[] sizes = SiegeGroups.sizes(n);
            assertEquals(n, Arrays.stream(sizes).sum());
            for (int s : sizes) {
                assertTrue(s <= SiegeGroups.MAX_GROUP, n + " -> " + Arrays.toString(sizes));
                assertTrue(sizes.length == 1 || s >= SiegeGroups.MIN_GROUP, n + " -> " + Arrays.toString(sizes));
            }
            assertEquals(sizes.length - 1, SiegeGroups.groupOf(n - 1, sizes));
        }
        assertEquals(0, SiegeGroups.sizes(0).length);
        assertEquals(8, SiegeGroups.sizes(50).length, "50 soldiers land as about eight groups");
    }

    @Test
    void groupsLandApartOnTheNearHalf() {
        double[] b = SiegeGroups.bearings(8, 99);
        assertEquals(0, b[0]);
        for (int i = 1; i < b.length; i++) {
            assertTrue(Math.abs(b[i]) <= SiegeGroups.SPREAD, "within the spread: " + b[i]);
            for (int j = 0; j < i; j++) {
                assertTrue(Math.abs(b[i] - b[j]) > 5, "groups " + i + " and " + j + " land together: " + Arrays.toString(b));
            }
        }
    }
}

package dev.hywmill.garrison;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpawnSpotsTest {
    @Test
    void boundedDeterministicAndStartsAtTheAnchor() {
        UUID id = UUID.fromString("0000aaaa-0000-4000-8000-000000000001");
        List<int[]> c = SpawnSpots.candidates(id);
        assertEquals(SpawnSpots.MAX_CANDIDATES, c.size());
        assertArrayEquals(new int[]{0, 0}, c.get(0));
        for (int[] o : c) {
            assertTrue(Math.abs(o[0]) <= SpawnSpots.MAX_RADIUS && Math.abs(o[1]) <= SpawnSpots.MAX_RADIUS);
        }
        List<int[]> again = SpawnSpots.candidates(id);
        for (int i = 0; i < c.size(); i++) {
            assertArrayEquals(c.get(i), again.get(i));
        }
    }

    @Test
    void differentSlotsSpreadOut() {
        List<int[]> a = SpawnSpots.candidates(GarrisonRoster.rosterId(GarrisonLifecycleTest.VILLAGE, 1));
        List<int[]> b = SpawnSpots.candidates(GarrisonRoster.rosterId(GarrisonLifecycleTest.VILLAGE, 2));
        boolean differs = false;
        for (int i = 1; i < a.size(); i++) {
            differs |= a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1];
        }
        assertTrue(differs);
    }
}

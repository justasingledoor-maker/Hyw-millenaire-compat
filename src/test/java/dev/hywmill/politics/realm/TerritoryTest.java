package dev.hywmill.politics.realm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The map's borders (1.10): the land each state holds. */
class TerritoryTest {
    static long at(int x, int z) {
        return Territory.chunk(x >> 4, z >> 4);
    }

    @Test
    void aVillageHoldsTheLandRoundItAndAStateItsVillagesTogether() {
        Map<Integer, Set<Long>> land = Territory.land(List.of(new Territory.Site(0, 0, 90, 0), new Territory.Site(300, 0, 90, 0)));
        assertEquals(1, land.size(), "one state");
        Set<Long> ours = land.get(0);
        assertTrue(ours.contains(at(0, 0)) && ours.contains(at(150, 0)) && ours.contains(at(300, 0)), "one land between its two villages");
        assertTrue(ours.contains(at(0, 200)), "within its walls and the reach beyond");
        assertFalse(ours.contains(at(0, 240)), "not further");
    }

    @Test
    void whereTwoStatesMeetTheGreaterHoldsMore() {
        Map<Integer, Set<Long>> even = Territory.land(List.of(new Territory.Site(0, 0, 90, 0), new Territory.Site(320, 0, 90, 1)));
        assertTrue(even.get(0).contains(at(140, 0)) && even.get(1).contains(at(180, 0)), "the border halfway");
        Map<Integer, Set<Long>> great = Territory.land(List.of(new Territory.Site(0, 0, 150, 0), new Territory.Site(320, 0, 90, 1)));
        assertTrue(great.get(0).contains(at(180, 0)), "the stronghold's land reaches further");
        assertTrue(great.get(1).contains(at(240, 0)));
        for (long c : great.get(0)) {
            assertFalse(great.get(1).contains(c), "a chunk is one state's only");
        }
        assertEquals(90, Territory.radius(0));
        assertEquals(150, Territory.radius(4));
    }
}

package dev.villageroads.plan;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGraphTest {
    @Test
    void straightStretchesAreFoldedButTheEndsAndCornersKept() {
        int[] way = {0, 0, 10, 0, 20, 0, 30, 0, 30, 10, 30, 20};
        assertArrayEquals(new int[]{0, 0, 30, 0, 30, 20}, Ways.simplify(way, Ways.TOLERANCE));
        assertEquals(50, Ways.length(new int[]{0, 0, 30, 0, 30, 20}), 1e-9);
    }

    @Test
    void aJourneyFollowsTheRoadsThroughTheVillagesTheyLink() {
        // A (0,0) to B (300,0) and B to C (300,300): a road bends round by B; C is reached through B
        int[] ab = {0, 0, 150, 40, 300, 0}, bc = {300, 0, 340, 150, 300, 300};
        RoadGraph g = new RoadGraph(List.of(ab, bc));
        int[] r = g.route(0, 0, 300, 300);
        assertNotNull(r);
        assertEquals(0, r[0]);
        assertEquals(300, r[r.length - 1]);
        boolean byB = false;
        for (int i = 0; i + 1 < r.length; i += 2) {
            byB |= r[i] == 300 && r[i + 1] == 0;
        }
        assertTrue(byB, "through B");
        assertTrue(Ways.length(r) > Math.hypot(300, 300), "longer than straight: it keeps to the roads");
    }

    @Test
    void roadsThatCrossMeetEvenMidStretch() {
        int[] ew = {0, 100, 400, 100}, ns = {200, -100, 200, 300};
        RoadGraph g = new RoadGraph(List.of(ew, ns));
        int[] r = g.route(0, 100, 200, 300);
        assertNotNull(r, "east-west road to the north-south one at their crossing");
        assertTrue(Ways.length(r) < 420);
    }

    @Test
    void noRoadNearOrAFarDetourMeansAcrossCountry() {
        RoadGraph g = new RoadGraph(List.of(new int[]{0, 0, 100, 0}));
        assertNull(g.route(1000, 1000, 2000, 1000), "no road within reach");
        // a road that runs far away round: A (0,0) .. (0,900) .. (100,900) .. B (100,0)
        RoadGraph u = new RoadGraph(List.of(new int[]{0, 0, 0, 900, 100, 900, 100, 0}));
        assertNull(u.route(0, 0, 100, 0), "the roads would make it far longer than going straight");
    }

    @Test
    void aRoadKnownOnlyByItsColumnsIsTracedFromVillageToVillage() {
        Set<Long> road = new HashSet<>();
        for (int x = 40; x <= 260; x++) {
            int z = x < 150 ? 0 : x - 150; // a road that turns
            for (int w = -1; w <= 1; w++) {
                road.add(Ways.cell(x, z + w));
            }
        }
        int[] xz = Ways.trace(road::contains, 0, 0, 50, 300, 110, 50, -50, -50, 350, 200, 1_000_000);
        assertNotNull(xz);
        assertEquals(0, xz[0]);
        assertEquals(110, xz[xz.length - 1]);
        int[] folded = Ways.simplify(xz, Ways.TOLERANCE);
        assertTrue(folded.length < xz.length / 4, "folded to a few points: " + folded.length / 2);
        assertNull(Ways.trace(road::contains, 0, 0, 20, 300, 110, 20, -50, -50, 350, 200, 1_000_000), "no road column near the first village");
    }
}

package dev.hywmill.politics.war;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTest {
    @Test
    void aStraightJourneyIsTheLineBetweenItsEnds() {
        Route r = Route.straight(0, 0, 100, 0);
        assertFalse(r.roads());
        assertEquals(100, r.length(), 1e-9);
        assertEquals(100, r.time(), 1e-9);
        assertArrayEquals(new double[]{50, 0}, r.at(0.5), 1e-9);
        assertEquals(0, r.saved().length, "a straight journey is not saved: it is rebuilt from its ends");
        assertNull(r.roadPoint(0.5));
    }

    @Test
    void onTheRoadTravellersGoFasterSoAShortDetourCostsNoTime() {
        // start (0,0) -> road (10,0) .. (10,100) .. (110,100) -> end (120,100): legs of 10 across country, 200 on the road
        int[] pts = {0, 0, 10, 0, 10, 100, 110, 100, 120, 100};
        Route r = new Route(pts, true);
        assertTrue(r.roads());
        assertEquals(220, r.length(), 1e-9);
        assertEquals(20 + 200 / Route.ROAD_PACE, r.time(), 1e-9);
        assertFalse(r.onRoad(0.01), "making for the road");
        assertTrue(r.onRoad(0.5), "on the road");
        assertFalse(r.onRoad(0.999), "leaving it");
        assertArrayEquals(pts, r.saved());
    }

    @Test
    void positionsAndHeadingsFollowTheRoadByTime() {
        Route r = new Route(new int[]{0, 0, 0, 0, 0, 100, 100, 100, 100, 100}, true);
        double[] mid = r.at(0.5);
        assertEquals(0, mid[0], 1e-6, "half the time: at the bend");
        assertEquals(100, mid[1], 1e-6);
        assertArrayEquals(new double[]{0, 1}, r.heading(0.25), 1e-9, "going south (+z) on the first stretch");
        assertArrayEquals(new double[]{1, 0}, r.heading(0.75), 1e-9, "then east");
        assertArrayEquals(new double[]{1, 0}, r.headingNear(60, 98), 1e-9);
        double[] onIt = r.roadPoint(0.25);
        assertNotNull(onIt);
        assertEquals(0, onIt[0], 1e-6);
        assertEquals(50, onIt[1], 1e-6);
    }

    @Test
    void aRoadJourneyNeedsAtLeastThreePointsElseItIsStraight() {
        assertFalse(Route.of(null, 0, 0, 10, 10).roads());
        assertFalse(Route.of(new int[]{0, 0, 10, 10}, 0, 0, 10, 10).roads());
        assertTrue(Route.of(new int[]{0, 0, 5, 0, 10, 10}, 0, 0, 10, 10).roads());
    }
}

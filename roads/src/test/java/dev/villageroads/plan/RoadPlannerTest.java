package dev.villageroads.plan;

import dev.villageroads.plan.Footprint.Cell;
import dev.villageroads.plan.Footprint.Part;
import dev.villageroads.plan.RoadPlanner.Node;
import dev.villageroads.plan.RoadPlanner.Route;
import dev.villageroads.plan.RoadPlanner.Settings;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerTest {
    interface Map {
        /** '.' land at 64, '~' water (top at 62), '#' a building, '=' an old road, a digit: land at 64 + 2 * digit, 'T' a tree */
        char at(int x, int z);
    }

    static Grid grid(int w, int h, Map m) {
        Grid g = new Grid(0, 0, w, h);
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                char c = m.at(x, z);
                switch (c) {
                    case '~' -> g.set(x, z, Grid.WATER, 62, false);
                    case '#' -> g.set(x, z, Grid.STRUCTURE, 65, false);
                    case '=' -> g.set(x, z, Grid.ROAD, 64, false);
                    case 'T' -> g.set(x, z, Grid.LAND, 64, true);
                    default -> g.set(x, z, Grid.LAND, Character.isDigit(c) ? 64 + 2 * (c - '0') : 64, false);
                }
            }
        }
        g.finish(2);
        return g;
    }

    static Route route(Grid g, int ax, int az, int bx, int bz) {
        BitSet goal = new BitSet();
        goal.set(g.idx(bx, bz));
        return RoadPlanner.plan(g, new int[]{g.idx(ax, az)}, new double[]{0}, goal, (x, z) -> RoadPlanner.WEIGHT * Math.hypot(x - bx, z - bz), Settings.DEFAULT);
    }

    static void connected(Route r) {
        List<Node> ns = r.nodes();
        for (int i = 1; i < ns.size(); i++) {
            Node a = ns.get(i - 1), b = ns.get(i);
            assertTrue(Math.abs(a.x() - b.x()) <= 1 && Math.abs(a.z() - b.z()) <= 1, "a gap in the road at " + a + " -> " + b);
        }
    }

    @Test
    void aStraightRoadOverOpenLand() {
        Grid g = grid(60, 20, (x, z) -> '.');
        Route r = route(g, 2, 10, 57, 10);
        assertNotNull(r);
        connected(r);
        assertEquals(0, r.bridges());
        assertTrue(r.nodes().stream().allMatch(n -> n.z() == 10), "straight");
        assertEquals(56, r.nodes().size());
    }

    @Test
    void itSnakesRoundBuildingsKeepingClear() {
        // a long building across the way, open only at the far side
        Grid g = grid(60, 40, (x, z) -> x >= 28 && x <= 31 && z <= 26 ? '#' : '.');
        Route r = route(g, 2, 10, 57, 10);
        assertNotNull(r);
        connected(r);
        for (Node n : r.nodes()) {
            assertTrue(!(n.x() >= 26 && n.x() <= 33 && n.z() <= 28), "too near the building: " + n);
        }
    }

    @Test
    void aRiverIsBridgedStraightAcross() {
        Grid g = grid(60, 30, (x, z) -> x >= 27 && x <= 34 ? '~' : '.');
        Route r = route(g, 2, 15, 57, 15);
        assertNotNull(r);
        connected(r);
        assertEquals(1, r.bridges());
        List<Node> bridge = r.nodes().stream().filter(Node::bridge).toList();
        assertEquals(8, bridge.size(), "the bridge spans the water only");
        assertTrue(bridge.stream().allMatch(n -> n.z() == bridge.get(0).z()), "a straight bridge");
    }

    @Test
    void aSmallPondIsWalkedRound() {
        Grid g = grid(60, 40, (x, z) -> x >= 28 && x <= 31 && z >= 18 && z <= 21 ? '~' : '.');
        Route r = route(g, 2, 20, 57, 20);
        assertNotNull(r);
        connected(r);
        assertEquals(0, r.bridges(), "going round a small pond is shorter than a bridge");
        assertTrue(r.nodes().stream().noneMatch(Node::bridge));
    }

    @Test
    void aRoadGoingRoundAPondKeepsOffItsRim() {
        Grid g = grid(60, 40, (x, z) -> x >= 28 && x <= 33 && z >= 17 && z <= 22 ? '~' : '.');
        Route r = route(g, 2, 20, 57, 20);
        assertNotNull(r);
        for (Node n : r.nodes()) {
            int dx = Math.max(0, Math.max(28 - n.x(), n.x() - 33)), dz = Math.max(0, Math.max(17 - n.z(), n.z() - 22));
            assertTrue(Math.max(dx, dz) >= 3, "a road of width 3 would fill the pond's rim at " + n);
        }
    }

    @Test
    void aLongLakeIsBridgedWhenGoingRoundIsLonger() {
        // a lake 8 wide stretching 70 blocks; the way round is at its far end
        Grid g = grid(60, 80, (x, z) -> x >= 26 && x <= 33 && z <= 70 ? '~' : '.');
        Route r = route(g, 2, 10, 57, 10);
        assertNotNull(r);
        assertEquals(1, r.bridges());
        // the same lake but short: round it
        Grid g2 = grid(60, 80, (x, z) -> x >= 26 && x <= 33 && z >= 8 && z <= 12 ? '~' : '.');
        Route r2 = route(g2, 2, 10, 57, 10);
        assertNotNull(r2);
        assertEquals(0, r2.bridges());
    }

    @Test
    void itTakesThePassNotTheCliff() {
        // a ridge 20 blocks high (digit 9 = +18) across the map, but a gentle pass at z 30..34
        Grid g = grid(60, 50, (x, z) -> {
            if (x < 25 || x > 34) {
                return '.';
            }
            return z >= 30 && z <= 34 ? '.' : '9';
        });
        Route r = route(g, 2, 10, 57, 10);
        assertNotNull(r);
        connected(r);
        for (int i = 1; i < r.nodes().size(); i++) {
            assertTrue(Math.abs(r.nodes().get(i).y() - r.nodes().get(i - 1).y()) <= 3, "no cliff steps");
        }
        assertTrue(r.nodes().stream().anyMatch(n -> n.x() == 30 && n.z() >= 30 && n.z() <= 34), "through the pass");
    }

    @Test
    void itFollowsAnOldRoadGoingItsWay() {
        Grid g = grid(60, 30, (x, z) -> z == 13 && x >= 5 && x <= 55 ? '=' : '.');
        Route r = route(g, 2, 10, 57, 10);
        assertNotNull(r);
        long onRoad = r.nodes().stream().filter(n -> n.z() == 13).count();
        assertTrue(onRoad > 30, "joined the old road: " + onRoad);
    }

    @Test
    void noWayThroughAWallOfBuildings() {
        Grid g = grid(60, 30, (x, z) -> x == 30 ? '#' : '.');
        assertNull(route(g, 2, 10, 57, 10));
    }

    @Test
    void heightsClimbABlockAStepAndDecksClearTheWater() {
        Grid g = grid(60, 30, (x, z) -> x >= 27 && x <= 34 ? '~' : x > 40 ? (char) ('0' + Math.min(4, x - 40)) : '.');
        Route r = route(g, 2, 15, 57, 15);
        assertNotNull(r);
        int[] ys = Profile.heights(r.nodes());
        for (int i = 1; i < ys.length; i++) {
            assertTrue(Math.abs(ys[i] - ys[i - 1]) <= 1, "step at " + i);
        }
        for (int i = 0; i < ys.length; i++) {
            if (r.nodes().get(i).bridge()) {
                assertTrue(ys[i] >= 62 + Profile.CLEARANCE, "deck above water");
            }
        }
        assertEquals(64 + 8, ys[ys.length - 1], "the road ends on the high ground");
    }

    @Test
    void theFootprintHasRailsAndPillarsOnlyOnTheBridge() {
        Grid g = grid(60, 30, (x, z) -> x >= 27 && x <= 34 ? '~' : '.');
        Route r = route(g, 2, 15, 57, 15);
        int[] ys = Profile.heights(r.nodes());
        List<Cell> cells = Footprint.stamp(r.nodes(), ys, 3);
        for (Cell c : cells) {
            if (c.part() == Part.RAIL || c.part() == Part.DECK || c.part() == Part.PILLAR) {
                assertTrue(c.x() >= 26 && c.x() <= 35, "bridge parts over the river only: " + c);
            }
        }
        long pillars = cells.stream().filter(c -> c.part() == Part.PILLAR).count();
        assertEquals(6, pillars, "pillars at both ends and every five, both sides");
        assertTrue(cells.stream().anyMatch(c -> c.part() == Part.LAMP));
        assertTrue(cells.stream().filter(c -> c.part() == Part.CORE).count() >= r.nodes().size() - 8);
        for (int i = 1; i < cells.size(); i++) {
            assertTrue(cells.get(i).at() >= cells.get(i - 1).at(), "built from one end to the other");
        }
    }

    @Test
    void aStraightBridgeDeckRunsEvenlyAboveWaterAndLand() {
        Grid g = grid(40, 10, (x, z) -> x >= 10 && x <= 30 ? '~' : '.');
        Route r = RoadPlanner.straight(g, 5, 5, 35, 5);
        boolean[] water = new boolean[r.nodes().size()];
        for (int i = 0; i < water.length; i++) {
            Node n = r.nodes().get(i);
            water[i] = g.kind(g.idx(n.x(), n.z())) == Grid.WATER;
        }
        int[] ys = Profile.deck(r.nodes(), water, 64, 70);
        assertEquals(64, ys[0]);
        assertEquals(70, ys[ys.length - 1]);
        for (int i = 1; i < ys.length; i++) {
            assertTrue(Math.abs(ys[i] - ys[i - 1]) <= 1);
            assertTrue(ys[i] >= (water[i] ? 64 : 65) || i == ys.length - 1);
        }
    }
}

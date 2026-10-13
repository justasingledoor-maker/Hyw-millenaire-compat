package dev.hywmill.politics.war;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyTest {
    @Test
    void trainsThatArriveStiffenASideAndCutOnesWeakenIt() {
        assertEquals(0, Supply.resolve(0, 0), 1e-9);
        assertEquals(0.04, Supply.resolve(2, 0), 1e-9);
        assertEquals(0.04, Supply.resolve(5, 0), 1e-9, "at most two count");
        assertEquals(-0.06, Supply.resolve(0, 3), 1e-9);
        assertTrue(Supply.resolve(1, 1) < 0, "a cut train hurts more than an arrived one helps");
    }

    @Test
    void aHostWhoseTrainsAreCutCannotKeepTheFieldAsLong() {
        assertEquals(3, Supply.days(3, 1, 1));
        assertEquals(2, Supply.days(3, 0, 1));
        assertEquals(1, Supply.days(1, 0, 2), "never less than a day");
    }

    @Test
    void trainsGuardsAndBounty() {
        assertEquals(1, Supply.trains(0));
        assertEquals(3, Supply.trains(5));
        assertEquals(Supply.GUARDS_MIN, Supply.guards(0));
        assertEquals(Supply.GUARDS_MAX, Supply.guards(0.9999));
        assertEquals(2 * Supply.BOUNTY_PER_CART, Supply.bounty(2));
    }

    @Test
    void aTrainCarriesACartForEveryThreeGuards() {
        Column c = new Column(java.util.UUID.randomUUID(), Column.Kind.SUPPLY, null, java.util.UUID.randomUUID(), null, 0, 0, 100, 0, 0, 1000);
        for (int i = 0; i < 7; i++) {
            c.units.add("spear_man");
        }
        assertEquals(2, c.carts());
        c.forHost = true;
        c.survivors = 7;
        assertTrue(c.label().contains("supply train for the host"), c.label());
        c.road(new int[]{0, 0, 10, 0, 90, 0, 100, 0});
        assertTrue(c.route().roads());
        assertTrue(c.onTheRoad(500));
        assertEquals(50, c.position(500)[0], 1e-6);
    }
}

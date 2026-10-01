package dev.hywmill.politics.war;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnTest {
    private static Column column(Column.Kind kind) {
        return new Column(UUID.randomUUID(), kind, null, UUID.randomUUID(), UUID.randomUUID(), 0, 0, 1000, 0, 100, 1100);
    }

    @Test
    void movesAlongItsRoadByTime() {
        Column c = column(Column.Kind.MERCS);
        assertArrayEquals(new double[]{0, 0}, c.position(0), 1e-9);
        assertArrayEquals(new double[]{500, 0}, c.position(600), 1e-9);
        assertArrayEquals(new double[]{1000, 0}, c.position(5000), 1e-9);
    }

    @Test
    void aConvoyStaysInCamp() {
        Column c = column(Column.Kind.CONVOY);
        assertArrayEquals(new double[]{0, 0}, c.position(900), 1e-9);
    }

    @Test
    void heldWhileInTheWorldAndDelayedByAsLong() {
        Column c = column(Column.Kind.VASSAL);
        c.heldSince = 600;
        assertArrayEquals(new double[]{500, 0}, c.position(900), 1e-9);
        c.resume(900);
        assertEquals(1400, c.arrive);
        assertEquals(400, c.depart);
        assertArrayEquals(new double[]{500, 0}, c.position(900), 1e-9);
    }

    @Test
    void compassAndDistanceFromThePlayer() {
        assertEquals("north", Column.bearing(0, 0, 0, -100));
        assertEquals("east", Column.bearing(0, 0, 100, 0));
        assertEquals("south-west", Column.bearing(0, 0, -100, 100));
        assertEquals("1.2 km north-east", Column.where(0, 0, 850, -850));
        assertEquals("300 m south", Column.where(0, 0, 0, 300));
    }

    @Test
    void openOnlyWhenFoundAndUndecided() {
        Column c = column(Column.Kind.MESSENGER);
        assertFalse(c.open(0));
        c.revealedBy = UUID.randomUUID();
        assertTrue(c.open(0));
        c.takenBy = UUID.randomUUID();
        assertFalse(c.open(0));
        c.takenBy = null;
        c.state = Column.State.DESTROYED;
        assertFalse(c.open(0));
    }

    @Test
    void siegeTimeCountsSleepAndSkippedDays() {
        Siege s = new Siege(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, 1000);
        s.startDay = 5000;
        assertEquals(200, s.elapsed(1200, 5100));
        assertEquals(12000, s.elapsed(1200, 17000)); // a night slept through
    }
}

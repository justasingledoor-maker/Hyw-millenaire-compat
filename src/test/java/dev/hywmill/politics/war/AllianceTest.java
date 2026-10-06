package dev.hywmill.politics.war;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllianceTest {
    @Test
    void aFriendOfBothSidesStaysOut() {
        assertEquals(Alliance.Choice.NEUTRAL, Alliance.choose(90, 80, false, 0, 0.0));
        assertEquals(Alliance.Choice.NEUTRAL, Alliance.choose(90, 70, false, 0, 0.99));
    }

    @Test
    void closerFriendsVassalsAndEnemiesOfTheAttackerJoinMoreOften() {
        double base = Alliance.joinChance(70, 0, false, 0);
        assertEquals(0.40, base, 1e-9);
        assertTrue(Alliance.joinChance(100, 0, false, 0) > base);
        assertTrue(Alliance.joinChance(70, -50, false, 0) > base);
        assertTrue(Alliance.joinChance(70, 0, true, 0) >= base + 0.4 - 1e-9, "a vassal is sworn to it");
        assertTrue(Alliance.joinChance(100, -100, true, 0) <= 0.95);
        assertTrue(Alliance.joinChance(70, 0, false, 2) < base, "the further down the chain, the less they care");
        assertTrue(Alliance.joinChance(70, 0, false, 9) >= 0.10);
    }

    @Test
    void anAllyJoinsOrBreaks() {
        assertEquals(Alliance.Choice.JOIN, Alliance.choose(70, 0, false, 0, 0.39));
        assertEquals(Alliance.Choice.BREAK, Alliance.choose(70, 0, false, 0, 0.41));
        assertEquals(Alliance.Choice.JOIN, Alliance.choose(90, 80, true, 0, 0.5), "a vassal follows its overlord even against a friend");
    }

    @Test
    void aVillageHelpsOneSideOfAWarAtMost() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        WarRecord w = new WarRecord(a, b);
        assertTrue(w.mayHelp(c, a) && w.mayHelp(c, b));
        w.sides.put(c, b);
        assertTrue(w.mayHelp(c, b));
        assertFalse(w.mayHelp(c, a));
        w.sides.put(c, WarRecord.NEUTRAL);
        assertFalse(w.mayHelp(c, a) || w.mayHelp(c, b));
        w.declare(10);
        w.makePeace();
        assertTrue(w.sides.isEmpty(), "sides are forgotten at the peace");
    }
}

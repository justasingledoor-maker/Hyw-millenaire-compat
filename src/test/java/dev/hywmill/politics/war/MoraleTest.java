package dev.hywmill.politics.war;

import dev.hywmill.politics.war.Morale.Circumstances;
import dev.hywmill.politics.war.Morale.Grade;
import dev.hywmill.politics.war.Morale.Ranks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoraleTest {
    @Test
    void gradesFromWhatAManIs() {
        assertEquals(Grade.LEVY, Morale.grade(true, "", "", 1));
        assertEquals(Grade.LEVY, Morale.grade(false, "levy", "", 1));
        assertEquals(Grade.LEVY, Morale.grade(false, "militia", "", 2));
        assertEquals(Grade.MERCENARY, Morale.grade(false, "merc", "", 3));
        assertEquals(Grade.MERCENARY, Morale.grade(false, "", "daylamite", 3));
        assertEquals(Grade.REGULAR, Morale.grade(false, "", "", 2));
        assertEquals(Grade.VETERAN, Morale.grade(false, "", "", 4));
        assertEquals(Grade.VETERAN, Morale.grade(true, "royal", "", 1), "the guard is the guard");
    }

    @Test
    void sidesBreakAfterTenToThirtyPercent() {
        Ranks levies = new Ranks(40, 0, 0, 0), regulars = new Ranks(0, 0, 40, 0), veterans = new Ranks(0, 0, 0, 40);
        assertEquals(0.10, Morale.resolve(levies, Circumstances.OPEN), 1e-9);
        assertEquals(0.20, Morale.resolve(regulars, Circumstances.OPEN), 1e-9);
        assertEquals(0.30, Morale.resolve(veterans, Circumstances.OPEN), 1e-9);
        assertEquals(4, Morale.breakAt(40, 0.10));
        assertEquals(12, Morale.breakAt(40, 0.30));
        // walls, home and a ruler stiffen; surprise and being outnumbered shake; always within bounds
        double walled = Morale.resolve(regulars, new Circumstances(true, 40, 1, false, false));
        assertTrue(walled > 0.25 && walled <= Morale.MAX);
        double shaken = Morale.resolve(levies, new Circumstances(false, 0, 0.25, false, true));
        assertEquals(Morale.MIN, shaken, 1e-9);
        assertTrue(Morale.resolve(veterans, new Circumstances(true, 200, 4, true, false)) <= Morale.MAX);
    }

    @Test
    void aClashIsNotDecidedInItsFirstMomentsAndBigArmiesFightLonger() {
        assertFalse(Morale.broken(8, 40, 0.20, false), "lost its resolve, but too soon");
        assertTrue(Morale.broken(8, 40, 0.20, true));
        assertTrue(Morale.broken(16, 40, 0.20, false), "twice its resolve: broken at once");
        assertFalse(Morale.broken(7, 40, 0.20, true));
        assertTrue(Morale.clash(200) > Morale.clash(30));
        assertEquals(6000, Morale.clash(1000));
        assertTrue(Morale.days(120) > Morale.days(20));
    }

    @Test
    void aHostGivesUpBeforeItIsDestroyed() {
        assertEquals(0.32, Morale.campaign(0.20), 1e-9);
        assertEquals(0.45, Morale.campaign(0.35), 1e-9);
    }

    @Test
    void onPaperSmallSidesSettleInADayBigOnesTakeLonger() {
        // a small siege: the loser of the day loses about its whole resolve
        Morale.Day small = Morale.paperDay(30, 30, 1.0, 0.2, 0.25, 0.45, 7);
        assertTrue(small.attackersHeld());
        assertTrue(small.defenders().fallen() >= Morale.breakAt(30, 0.25), "the defenders break the same day");
        // a big one: only part of it, the front being what it is
        Morale.Day big = Morale.paperDay(300, 300, 1.0, 0.2, 0.25, 0.45, 7);
        assertTrue(big.defenders().fallen() < Morale.breakAt(300, 0.25), "they hold for another day");
        // the winner loses less than it inflicts; behind walls far less
        Morale.Day repulsed = Morale.paperDay(60, 60, 0.0, 0.2, 0.25, 0.45, 9);
        assertFalse(repulsed.attackersHeld());
        assertTrue(repulsed.defenders().fallen() < repulsed.host().fallen() / 2 + 1);
        for (long seed = 0; seed < 50; seed++) {
            Morale.Day d = Morale.paperDay(80, 50, 0.6, 0.2, 0.25, 0.45, seed);
            assertTrue(d.host().fallen() <= 80 && d.defenders().fallen() <= 50);
            assertTrue(d.host().fallen() < 40 && d.defenders().fallen() < 25, "nobody fights to the last man in a day");
        }
    }

    @Test
    void theBeatenAreRunDownAsTheyFlee() {
        assertTrue(Morale.pursuit(1, 1) > Morale.pursuit(0, 0));
        assertEquals(0.04, Morale.pursuit(0, 0), 1e-9);
        Morale.Field f = Morale.paperField(40, 40, 1.0, 0.2, 0.2, 0.3, 0, 3);
        assertTrue(f.oursWon());
        assertTrue(f.theirFallen() > f.ourFallen(), "the pursuit costs the beaten most");
        assertTrue(f.theirFallen() < 40 / 2, "but not the whole army");
    }

    @Test
    void headPricesAndSpoilsByMerit() {
        assertEquals(64, Morale.headPrice(Grade.LEVY));
        assertEquals(384, Morale.headPrice(Grade.VETERAN));
        // 1000 among two: one earned 300, the other nothing
        assertEquals(850, Morale.spoils(1000, 2, 300, 300));
        assertEquals(150, Morale.spoils(1000, 2, 0, 300));
        assertEquals(500, Morale.spoils(1000, 2, 0, 0), "no merit: equal shares");
    }

    @Test
    void theBesiegedRaiseLeviesToEvenTheOdds() {
        assertEquals(0, Conscription.levies(40, 50, 30), "enough men already");
        assertEquals(26, Conscription.levies(60, 25, 30));
        assertEquals(10, Conscription.levies(100, 0, 10), "as many as the land can give");
        assertEquals(4 + 30 + 10, Conscription.cap(90, 60));
        assertEquals(Conscription.MAX_LEVY, Conscription.cap(10000, 10000));
        for (long seed = 0; seed < 50; seed++) {
            int r = Conscription.rumour(100, seed);
            assertTrue(r >= 60 && r <= 140, "a rumour is off by two fifths at most: " + r);
        }
        assertTrue(Conscription.surprised(100, 60, false));
        assertFalse(Conscription.surprised(100, 60, true), "scouted: no surprise");
        assertTrue(Conscription.hopeless(30, 100));
        assertTrue(Conscription.outmatched(130, 100));
        assertTrue(Conscription.accepts("SUBJUGATE") > Conscription.accepts("RAZE"));
    }
}

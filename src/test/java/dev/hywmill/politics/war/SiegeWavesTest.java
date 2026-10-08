package dev.hywmill.politics.war;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiegeWavesTest {
    @Test
    void defendersAreWoundedMoreOftenThanAttackers() {
        assertEquals(0.45, SiegeWaves.woundChance(true));
        assertEquals(0.35, SiegeWaves.woundChance(false));
        assertTrue(SiegeWaves.wounded(true, 0.40));
        assertFalse(SiegeWaves.wounded(false, 0.40));
    }

    @Test
    void aSideDownToAFifthAliveOrWoundedLoses() {
        assertEquals(Siege.Outcome.NONE, SiegeWaves.verdict(30, 40, 21, 50, 1, false));
        assertEquals(Siege.Outcome.WON, SiegeWaves.verdict(30, 40, 10, 50, 1, false));
        assertEquals(Siege.Outcome.LOST, SiegeWaves.verdict(8, 40, 10, 50, 2, false)); // both broken: the host gives up first
        assertEquals(Siege.Outcome.NONE, SiegeWaves.verdict(30, 40, 30, 50, 2, true));
    }

    @Test
    void afterTheThirdWaveNeitherBrokenIsAStalemate() {
        assertEquals(Siege.Outcome.STALEMATE, SiegeWaves.verdict(30, 40, 30, 50, 3, true));
        assertEquals(Siege.Outcome.NONE, SiegeWaves.verdict(30, 40, 30, 50, 3, false));
        assertEquals(Siege.Outcome.WON, SiegeWaves.verdict(30, 40, 9, 50, 3, true));
    }

    @Test
    void wavesRunDawnToSundownAndNightsUntilDawn() {
        assertFalse(SiegeWaves.night(0));
        assertFalse(SiegeWaves.night(12000));
        assertTrue(SiegeWaves.night(13000));
        assertTrue(SiegeWaves.night(24000 + 18000));
        assertFalse(SiegeWaves.night(24000 + 23600));
        assertFalse(SiegeWaves.waveOver(1000, 13000)); // too short a wave: the host arrived late in the day
        assertTrue(SiegeWaves.waveOver(3000, 13000));
        assertTrue(SiegeWaves.waveOver(SiegeWaves.WAVE_MAX, 6000)); // no daylight cycle: half a day
        assertFalse(SiegeWaves.nightOver(100, 0));
        assertTrue(SiegeWaves.nightOver(800, 0));
        assertTrue(SiegeWaves.nightOver(SiegeWaves.NIGHT_MAX, 18000));
    }

    @Test
    void aWaveOnPaperHurtsTheLoserMore() {
        int heldBetter = 0;
        for (long seed = 0; seed < 200; seed++) {
            SiegeWaves.Paper p = SiegeWaves.paper(40, 40, 0.5, seed);
            assertTrue(p.host().fallen() <= 40 && p.defenders().fallen() <= 40);
            boolean ok = p.attackersHeld() ? p.host().fallen() < p.defenders().fallen() : p.defenders().fallen() < p.host().fallen();
            heldBetter += ok ? 1 : 0;
        }
        assertEquals(200, heldBetter);
        // about 45% of the defenders who fall are only wounded, about 35% of the attackers
        int dw = 0, df = 0, hw = 0, hf = 0;
        for (long seed = 0; seed < 2000; seed++) {
            SiegeWaves.Paper p = SiegeWaves.paper(40, 40, 0.5, seed);
            dw += p.defenders().wounded();
            df += p.defenders().fallen();
            hw += p.host().wounded();
            hf += p.host().fallen();
        }
        assertEquals(0.45, dw / (double) df, 0.02);
        assertEquals(0.35, hw / (double) hf, 0.02);
    }

    @Test
    void aTenthOfTheWoundedDieInTheNight() {
        int died = 0;
        for (long seed = 0; seed < 1000; seed++) {
            died += SiegeWaves.succumb(20, seed);
        }
        assertEquals(0.1, died / 20000.0, 0.01);
        assertEquals("second", SiegeWaves.ordinal(2));
    }

    @Test
    void wallsAndTowersSaveMoreDefenders() {
        assertEquals(0.45, SiegeWaves.defenderWoundChance(0), 1e-9);
        assertEquals(0.50, SiegeWaves.defenderWoundChance(20), 1e-9);
        assertEquals(0.60, SiegeWaves.defenderWoundChance(60), 1e-9);
        assertEquals(0.60, SiegeWaves.defenderWoundChance(500), 1e-9); // at most +15%
    }

    @Test
    void surgeonsWhereThereAreSoldiersQuartersOrATown() {
        assertTrue(SiegeWaves.surgeons(true, true, false), "barracks keep surgeons");
        assertTrue(SiegeWaves.surgeons(true, false, true), "a town has a barber-surgeon");
        assertFalse(SiegeWaves.surgeons(true, false, false), "a hamlet has none");
        assertTrue(SiegeWaves.surgeons(false, false, true), "a town's host brings a camp surgeon");
        assertFalse(SiegeWaves.surgeons(false, true, false), "barracks at home do not march with the host");
        assertEquals(0.04, SiegeWaves.succumbChance(true));
        assertEquals(0.1, SiegeWaves.succumbChance(false));
        int with = 0, without = 0;
        for (long seed = 0; seed < 1000; seed++) {
            with += SiegeWaves.succumb(20, SiegeWaves.succumbChance(true), seed);
            without += SiegeWaves.succumb(20, SiegeWaves.succumbChance(false), seed);
        }
        assertTrue(with < without / 2, with + " vs " + without);
    }

    @Test
    void reservesAreHeldBackAndFedInAsTheFrontThins() {
        assertEquals(0, SiegeWaves.reserve(7, SiegeWaves.HOST_RESERVE), "a small side keeps no reserve");
        assertEquals(29, SiegeWaves.reserve(65, SiegeWaves.HOST_RESERVE));
        assertEquals(49, SiegeWaves.reserve(141, SiegeWaves.DEFENDER_RESERVE));
        // the front holds: the reserve waits
        assertEquals(0, SiegeWaves.feed(29, 30, 36, 1000));
        // the front is down to 60% of what it was: a few go in each step
        assertEquals(SiegeWaves.FEED, SiegeWaves.feed(29, 21, 36, 1000));
        assertEquals(3, SiegeWaves.feed(3, 10, 36, 1000));
        // from noon all of it goes in, however the front stands
        assertEquals(SiegeWaves.FEED, SiegeWaves.feed(29, 36, 36, SiegeWaves.NOON));
        assertEquals(0, SiegeWaves.feed(0, 0, 36, SiegeWaves.NOON));
    }
}

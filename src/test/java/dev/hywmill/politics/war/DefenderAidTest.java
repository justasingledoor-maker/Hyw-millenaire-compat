package dev.hywmill.politics.war;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 help for the besieged: militia, mercenaries and the lord's household, each by chance. */
class DefenderAidTest {
    @Test
    void militiaScalesWithPopulationFiveToFifteen() {
        assertEquals(5, DefenderAid.militiaSize(0, 0.5));
        assertEquals(10, DefenderAid.militiaSize(30, 0.5));
        assertEquals(15, DefenderAid.militiaSize(200, 1));
        assertEquals(5, DefenderAid.militiaSize(0, 0), "never below five");
    }

    @Test
    void eachKindComesByItsOwnChance() {
        int militia = 0, mercs = 0, household = 0, nothing = 0;
        for (long seed = 0; seed < 4000; seed++) {
            DefenderAid.Aid a = DefenderAid.roll(seed * 7919, 40, true, false);
            militia += a.militia() > 0 ? 1 : 0;
            mercs += a.mercs() != null ? 1 : 0;
            household += a.household() > 0 ? 1 : 0;
            nothing += a.any() ? 0 : 1;
            if (a.household() > 0) {
                assertTrue(a.household() >= DefenderAid.HOUSEHOLD_MIN && a.household() <= DefenderAid.HOUSEHOLD_MAX);
            }
        }
        assertTrue(militia > 1700 && militia < 2300, "about half: " + militia);
        assertTrue(mercs > 600 && mercs < 1000, "about a fifth: " + mercs);
        assertTrue(household > 1000 && household < 1400, "about 30%: " + household);
        assertTrue(nothing > 0, "sometimes no help comes");
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(0, DefenderAid.roll(seed, 40, false, false).household(), "a lord's household only in a garrison or stronghold");
        }
        DefenderAid.Aid all = DefenderAid.roll(5, 40, true, true);
        assertTrue(all.militia() > 0 && all.mercs() != null && all.household() > 0, "forced: everything comes");
    }

    @Test
    void drawIsStableAndFromThePool() {
        List<String> d = DefenderAid.draw(List.of("spear_man", "shieldman", "shieldman"), 12, 3);
        assertEquals(12, d.size());
        assertEquals(d, DefenderAid.draw(List.of("spear_man", "shieldman", "shieldman"), 12, 3));
        assertTrue(List.of("spear_man", "shieldman").containsAll(d));
        assertTrue(DefenderAid.draw(List.of(), 5, 1).isEmpty());
    }
}

package dev.hywmill.politics.war;

import dev.hywmill.politics.war.FieldTactics.Doctrine;
import dev.hywmill.politics.war.FieldTactics.Move;
import dev.hywmill.politics.war.FieldTactics.Role;
import dev.hywmill.politics.war.FieldTactics.Situation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldTacticsTest {
    @Test
    void rolesFromUnitTypes() {
        assertEquals(Role.SHIELD, FieldTactics.role("hundred_years_war:shieldman", false));
        assertEquals(Role.SHIELD, FieldTactics.role("hundred_years_war:spear_man", false));
        assertEquals(Role.MELEE, FieldTactics.role("hundred_years_war:warrior", false));
        assertEquals(Role.MELEE, FieldTactics.role("hundred_years_war:militia", false));
        assertEquals(Role.RANGED, FieldTactics.role("hundred_years_war:crossbowman", false));
        assertEquals(Role.CAVALRY, FieldTactics.role("hundred_years_war:mounted_archer_rider", false));
        assertEquals(Role.ENGINE, FieldTactics.role("hundred_years_war:mangonel", true));
    }

    @Test
    void doctrinesBeatEachOtherInACircle() {
        for (Doctrine a : Doctrine.values()) {
            assertEquals(0, FieldTactics.edge(a, a), 1e-9);
            for (Doctrine b : Doctrine.values()) {
                assertEquals(-FieldTactics.edge(b, a), FieldTactics.edge(a, b), 1e-9, a + " vs " + b);
            }
        }
        assertTrue(FieldTactics.edge(Doctrine.SHIELD_WALL, Doctrine.HAMMER) > 0, "a charge breaks on a wall");
        assertTrue(FieldTactics.edge(Doctrine.SKIRMISH, Doctrine.SHIELD_WALL) > 0, "archers whittle a wall");
        assertTrue(FieldTactics.edge(Doctrine.HAMMER, Doctrine.SKIRMISH) > 0, "a charge catches skirmishers");
        assertTrue(FieldTactics.edge(Doctrine.FLANK, Doctrine.SHIELD_WALL) > 0, "a pincer turns a wall");
    }

    @Test
    void aMarshalChoosesByHisMenAndTheirs() {
        FieldTactics.Mix shields = new FieldTactics.Mix(20, 4, 4, 0, 0), archers = new FieldTactics.Mix(4, 4, 20, 0, 0);
        FieldTactics.Mix warriors = new FieldTactics.Mix(4, 20, 4, 2, 0), riders = new FieldTactics.Mix(4, 8, 2, 14, 0);
        // against a host of shields, a host of archers skirmishes; warriors against archers charge
        assertEquals(Doctrine.SKIRMISH, FieldTactics.choose(archers, shields, 1, 0, 1));
        assertEquals(Doctrine.HAMMER, FieldTactics.choose(warriors, archers, 1, 0, 1));
        assertEquals(Doctrine.FLANK, FieldTactics.choose(riders, shields, 1, 0, 1));
        // a weak, cautious side of shields stands in a wall against warriors
        assertEquals(Doctrine.SHIELD_WALL, FieldTactics.choose(shields, warriors, 0.6, -1, 1));
    }

    @Test
    void theHammerSendsTheWarriorsFirstAndTheShieldsAfter() {
        Situation start = new Situation(0, false, 1, 1, 100, 100), later = new Situation(700, false, 1, 1, 100, 100);
        assertEquals(Move.CHARGE, FieldTactics.order(Doctrine.HAMMER, Role.MELEE, start).move());
        assertEquals(Move.FOLLOW, FieldTactics.order(Doctrine.HAMMER, Role.RANGED, start).move(), "archers behind the warriors");
        assertEquals(Move.HOLD, FieldTactics.order(Doctrine.HAMMER, Role.SHIELD, start).move());
        assertEquals(Move.CHARGE, FieldTactics.order(Doctrine.HAMMER, Role.SHIELD, later).move(), "then the shields close in");
        assertEquals(Move.BOMBARD, FieldTactics.order(Doctrine.HAMMER, Role.ENGINE, start).move());
    }

    @Test
    void skirmishersGiveGroundAndWarriorsCounterCharge() {
        Situation far = new Situation(200, false, 1, 1, 40, 30), close = new Situation(400, true, 1, 1, 9, 12);
        assertEquals(Move.ADVANCE, FieldTactics.order(Doctrine.SKIRMISH, Role.RANGED, far).move());
        assertEquals(Move.FALL_BACK, FieldTactics.order(Doctrine.SKIRMISH, Role.RANGED, close).move());
        assertEquals(Move.HOLD, FieldTactics.order(Doctrine.SKIRMISH, Role.MELEE, far).move());
        assertEquals(Move.CHARGE, FieldTactics.order(Doctrine.SKIRMISH, Role.MELEE, close).move());
        assertEquals(Move.SCREEN, FieldTactics.order(Doctrine.SKIRMISH, Role.SHIELD, far).move());
    }

    @Test
    void aBreakingFoeIsPursuedAndAWaveringSideRallies() {
        Situation winning = new Situation(800, true, 0.9, 0.5, 30, 30);
        for (Doctrine d : Doctrine.values()) {
            assertEquals(Move.CHARGE, FieldTactics.order(d, Role.SHIELD, winning).move(), d + ": pursue");
        }
        assertTrue(FieldTactics.rallies(Doctrine.HAMMER, 0.6, false));
        assertFalse(FieldTactics.rallies(Doctrine.HAMMER, 0.6, true), "once");
        assertFalse(FieldTactics.rallies(Doctrine.SHIELD_WALL, 0.5, false), "a wall already");
        assertTrue(FieldTactics.routs(16, 40));
        assertFalse(FieldTactics.routs(17, 40));
    }

    @Test
    void onPaperTheBetterPlanWeighs() {
        double even = FieldTactics.winChance(100, 100, Doctrine.HAMMER, Doctrine.HAMMER);
        assertEquals(0.5, even, 1e-9);
        assertTrue(FieldTactics.winChance(100, 100, Doctrine.SHIELD_WALL, Doctrine.HAMMER) > 0.6);
        assertTrue(FieldTactics.winChance(100, 100, Doctrine.HAMMER, Doctrine.SHIELD_WALL) < 0.4);
        assertTrue(FieldTactics.winChance(300, 100, Doctrine.HAMMER, Doctrine.SHIELD_WALL) > 0.5, "numbers still tell");
    }
}

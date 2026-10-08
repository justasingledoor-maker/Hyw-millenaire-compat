package dev.hywmill.politics.war;

import dev.hywmill.politics.PoliticsTables;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 war arsenal: engines per tier, types, and their weight against walls. */
class ArsenalPlanTest {
    // through the tables, as the mod does: touching ArsenalRule.DEFAULT first would start a static-init cycle with PoliticsTables
    static final PoliticsTables.ArsenalRule R = PoliticsTables.DEFAULTS.arsenal();

    @Test
    void oneToFourEnginesByTierCatapultsAndTrebuchetsOnly() {
        assertEquals(0, ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.NONE, R, 1).size());
        assertEquals(1, ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.WATCH, R, 1).size());
        assertEquals(2, ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.GUARD_POST, R, 1).size());
        List<String> g = ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.GARRISON, R, 1);
        assertEquals(3, g.size());
        assertTrue(List.of("trebuchets", "mangonels", "springald").containsAll(g), g.toString());
    }

    @Test
    void aStrongholdFieldsANestOfBees() {
        for (long seed = 0; seed < 20; seed++) {
            List<String> s = ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.STRONGHOLD, R, seed);
            assertEquals(4, s.size());
            assertEquals(1, s.stream().filter("nest_of_bees"::equals).count());
            assertTrue(s.stream().noneMatch(k -> k.contains("cannon") || k.contains("bombard") || k.contains("culverin")));
        }
    }

    @Test
    void drawIsStablePerSeed() {
        assertEquals(ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.GARRISON, R, 42), ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.GARRISON, R, 42));
    }

    @Test
    void disabledMeansNoEngines() {
        PoliticsTables.ArsenalRule off = new PoliticsTables.ArsenalRule(false, R.engines(), R.types(), R.strongholdTypes(), 8, 0.25);
        assertTrue(ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.STRONGHOLD, off, 1).isEmpty());
    }

    @Test
    void enginesCancelTheWallsBonus() {
        assertEquals(1.5, ArsenalPlan.fortificationLeft(1.5, 0, R), 1e-9);
        assertEquals(1.25, ArsenalPlan.fortificationLeft(1.5, 2, R), 1e-9);
        assertEquals(1.0, ArsenalPlan.fortificationLeft(1.5, 4, R), 1e-9);
        assertEquals(1.0, ArsenalPlan.fortificationLeft(1.5, 9, R), 1e-9);
    }

    @Test
    void theEngineerIsNotAnEngine() {
        assertFalse(ArsenalPlan.isEngine(ArsenalPlan.ENGINEER));
        assertTrue(ArsenalPlan.isEngine("trebuchets"));
    }

    @Test
    void trebuchetsAreCommonAtHomeButRarelyMarch() {
        int trebuchets = 0, all = 0;
        for (long seed = 0; seed < 200; seed++) {
            for (String k : ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.GARRISON, R, seed * 0x9E3779B97F4A7C15L)) { // spread seeds, as the hashed ones in play
                all++;
                trebuchets += k.equals("trebuchets") ? 1 : 0;
            }
        }
        assertTrue(trebuchets > all * 0.4 && trebuchets < all * 0.6, trebuchets + " of " + all);
        assertTrue(ArsenalPlan.marches("trebuchets", 0.1));
        assertFalse(ArsenalPlan.marches("trebuchets", 0.5));
        assertTrue(ArsenalPlan.marches("mangonels", 0.99));
        assertEquals(170, ArsenalPlan.reach("trebuchets"));
        assertEquals(100, ArsenalPlan.reach("mangonels"));
    }
}

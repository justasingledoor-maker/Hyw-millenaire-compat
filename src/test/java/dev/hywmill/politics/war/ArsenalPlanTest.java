package dev.hywmill.politics.war;

import dev.hywmill.politics.PoliticsTables;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 war arsenal: engines per tier, types, and their weight against walls. */
class ArsenalPlanTest {
    static final PoliticsTables.ArsenalRule R = PoliticsTables.ArsenalRule.DEFAULT;

    @Test
    void oneToFourEnginesByTierCatapultsAndTrebuchetsOnly() {
        assertEquals(0, ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.NONE, R, 1).size());
        assertEquals(1, ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.WATCH, R, 1).size());
        assertEquals(2, ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.GUARD_POST, R, 1).size());
        List<String> g = ArsenalPlan.engines(PoliticsTables.MilitaryTierKey.GARRISON, R, 1);
        assertEquals(3, g.size());
        assertTrue(List.of("mangonels", "springald").containsAll(g), g.toString());
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
}

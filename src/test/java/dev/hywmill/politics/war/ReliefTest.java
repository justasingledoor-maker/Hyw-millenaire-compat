package dev.hywmill.politics.war;

import dev.hywmill.politics.PoliticsTables;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 relief forces: the pure rules. */
class ReliefTest {
    static final PoliticsTables.ReliefRule R = PoliticsTables.ReliefRule.DEFAULT;

    @Test
    void onlyGreatFriendsAtPeaceWithTheBesiegedSendRelief() {
        assertTrue(Relief.eligible(85, false, false, false, 10, R));
        assertTrue(Relief.eligible(72, false, false, false, 10, R), "post-M5: good friends (70+) send help too");
        assertFalse(Relief.eligible(60, false, false, false, 10, R), "fair terms are not enough");
        assertFalse(Relief.eligible(95, true, false, false, 10, R), "not while at war with the besieged");
        assertFalse(Relief.eligible(95, false, true, false, 10, R), "the attacker and the target are parties");
        assertFalse(Relief.eligible(95, false, false, true, 10, R), "bandits send nobody");
        assertFalse(Relief.eligible(95, false, false, false, 3, R), "too small a garrison");
    }

    @Test
    void forceIsTwentyToSixtyPercentAtLeastTwelveAndArrivesInOneToTwoMinutes() {
        // post-M5: allies send two to three times as much as before
        assertEquals(12, Relief.size(40, 0, R), "20% of 40 is 8: at least twelve");
        assertEquals(24, Relief.size(40, 1, R), "60% of 40");
        assertEquals(36, Relief.size(60, 1, R), "60% of 60");
        assertEquals(3, Relief.size(6, 0, R), "a small garrison sends half, not all");
        assertEquals(1, Relief.size(1, 0, R), "at least one soldier");
        assertEquals(0, Relief.size(0, 0.5, R));
        assertEquals(1200, Relief.travel(0, R));
        assertEquals(2400, Relief.travel(1, R));
    }

    @Test
    void journeysSplitIntoCleanAmbushRoutAndLost() {
        Map<Relief.Fate, Integer> seen = new EnumMap<>(Relief.Fate.class);
        for (long seed = 0; seed < 4000; seed++) {
            Relief.Journey j = Relief.journey(10, seed, R);
            seen.merge(j.fate(), 1, Integer::sum);
            switch (j.fate()) {
                case CLEAN -> assertEquals(10, j.arrive(10));
                case AMBUSHED -> {
                    assertTrue(j.killed() >= 2 && j.killed() <= 6, "20-60% killed: " + j.killed());
                    assertEquals(0, j.strays());
                }
                case ROUTED -> {
                    assertEquals(0, j.arrive(10), "a routed force never arrives");
                    assertTrue(j.killed() > 0);
                }
                case STRAGGLED -> {
                    assertEquals(0, j.killed(), "getting lost costs no lives");
                    assertTrue(j.arrive(10) > 0 && j.strays() >= 3);
                }
                case LOST -> assertEquals(10, j.strays());
                default -> fail("unexpected fate " + j.fate());
            }
        }
        double clean = seen.getOrDefault(Relief.Fate.CLEAN, 0) / 4000.0;
        assertTrue(clean > 0.78 && clean < 0.86, "about 82% arrive cleanly: " + clean);
        assertTrue(seen.containsKey(Relief.Fate.AMBUSHED) && seen.containsKey(Relief.Fate.ROUTED) && seen.containsKey(Relief.Fate.STRAGGLED));
    }

    @Test
    void postsSpreadRoundTheVillage() {
        int[] p0 = Relief.post(0, 4, 40, 0), p2 = Relief.post(2, 4, 40, 0);
        assertEquals(20, Math.hypot(p0[0], p0[1]), 1.0);
        assertEquals(-p0[0], p2[0], 1);
        assertEquals(-p0[1], p2[1], 1);
    }
}

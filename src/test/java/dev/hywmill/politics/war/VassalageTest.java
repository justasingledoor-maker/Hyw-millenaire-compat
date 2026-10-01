package dev.hywmill.politics.war;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 vassalage and battle reports: the pure rules. */
class VassalageTest {
    @Test
    void sworn21DaysThenFree() {
        Vassalage v = Vassalage.sworn(new UUID(1, 1), new UUID(2, 2), 1000);
        assertEquals(1000 + 21 * 24000L, v.until);
        assertFalse(v.over(1000 + 20 * 24000L));
        assertEquals(1, v.daysLeft(1000 + 20 * 24000L));
        assertTrue(v.over(v.until));
    }

    @Test
    void sendsTenToFifteenMixedThreeTimesInFour() {
        int helped = 0, regulars = 0, all = 0;
        for (long seed = 0; seed < 4000; seed++) {
            List<Boolean> l = Vassalage.levy(seed * 7919);
            if (!l.isEmpty()) {
                helped++;
                assertTrue(l.size() >= 10 && l.size() <= 15, "10-15: " + l.size());
                regulars += (int) l.stream().filter(b -> b).count();
                all += l.size();
            }
        }
        assertTrue(helped > 2800 && helped < 3200, "about 75%: " + helped);
        assertTrue(regulars > all * 0.4 && regulars < all * 0.6, "a mix of levies and regulars: " + regulars + "/" + all);
    }

    @Test
    void reportReadsAsAHistoryEntry() {
        BattleReport r = new BattleReport(24000 * 4 + 5, new UUID(1, 1), new UUID(2, 2), "Lato", "Isigny", "WON", 20, 6, 30, 22, true);
        r.notes.add("the Genoese crossbowmen hired by Lato (12)");
        r.tribute = "8 or a day for 4 days";
        List<String> l = r.lines();
        assertEquals("Day 5: Lato besieged Isigny - Isigny fell", l.get(0));
        assertTrue(l.get(1).contains("Attackers 20 (6 fell), defenders 30 (22 fell); fought in sight"));
        assertTrue(l.contains("  - the Genoese crossbowmen hired by Lato (12)"));
        assertEquals("  Tribute: 8 or a day for 4 days", l.get(l.size() - 1));
        assertTrue(r.involves(new UUID(2, 2)) && !r.involves(new UUID(3, 3)));
    }
}

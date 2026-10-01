package dev.hywmill.politics.war;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 tribute: the full amount every day for 3-5 days. */
class TributeTest {
    @Test
    void paidThreeToFiveDays() {
        boolean[] seen = new boolean[6];
        for (long seed = 0; seed < 300; seed++) {
            int d = Tribute.days(seed * 7919);
            assertTrue(d >= Tribute.MIN_DAYS && d <= Tribute.MAX_DAYS, "days " + d);
            seen[d] = true;
        }
        assertTrue(seen[3] && seen[4] && seen[5], "every length occurs");
    }

    @Test
    void theFullAmountEveryDay() {
        Tribute t = new Tribute(new UUID(1, 1), new UUID(2, 2), 10 * 4096, 5.0, 4096, 4, 0);
        assertEquals(10 * 4096, t.installment(), "the siege's tribute, not a quarter of it");
        assertEquals(4096, t.helperInstallment());
        assertEquals(40L * 4096, Tribute.whole(t.total, t.days));
        t.paid = 3;
        assertFalse(t.done());
        t.paid = 4;
        assertTrue(t.done());
    }
}

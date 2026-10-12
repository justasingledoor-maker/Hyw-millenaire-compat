package dev.hywmill.politics.realm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The national army (docs/realm-design.md §9): who raises one, and what each member pledges. */
class NationalArmyTest {
    @Test
    void aCapitalWithTwoSubjectsRaisesAnArmy() {
        assertFalse(NationalArmy.raised(1));
        assertTrue(NationalArmy.raised(2));
        assertTrue(NationalArmy.raised(7));
    }

    @Test
    void eachMemberPledgesItsShare() {
        assertEquals(0.6, NationalArmy.share(true, false, 0));
        assertEquals(0.4, NationalArmy.share(false, true, 10), "a province's share does not hang on its loyalty");
        assertEquals(0.25, NationalArmy.share(false, false, 50), 1e-9);
        assertEquals(0.2, NationalArmy.vassalShare(40), 1e-9, "a new vassal (loyalty 40)");
        assertEquals(0.4, NationalArmy.vassalShare(100), 1e-9, "at most 40%");
        assertEquals(0.1, NationalArmy.vassalShare(5), 1e-9, "at least 10%");
        assertEquals(12, NationalArmy.pledge(20, 0.6));
        assertEquals(8, NationalArmy.pledge(20, 0.4));
        assertEquals(1, NationalArmy.pledge(2, 0.1), "a member with men free sends at least one");
        assertEquals(0, NationalArmy.pledge(0, 0.6));
    }
}

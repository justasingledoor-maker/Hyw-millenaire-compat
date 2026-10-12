package dev.hywmill.politics.realm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Realm unity (docs/realm-design.md §8): the call to war, the council, shared hatred, scutage. */
class RealmCallTest {
    @Test
    void aVassalMarchesUnlessItBreaksAway() {
        assertEquals(RealmCall.Answer.JOIN, RealmCall.call(50, false, 0, 10, 30));
        assertEquals(RealmCall.Answer.JOIN, RealmCall.call(21, false, -100, 10, 30), "loyal enough, it marches");
        assertEquals(RealmCall.Answer.BREAK, RealmCall.call(20, false, -100, 10, 30), "a disloyal vassal uses the war to break away");
        assertEquals(RealmCall.Answer.BREAK, RealmCall.call(39, false, 75, 10, 30), "it will not fight its friends when its loyalty is shaky");
        assertEquals(RealmCall.Answer.JOIN, RealmCall.call(40, false, 100, 10, 30), "a loyal vassal fights even its friends");
    }

    @Test
    void aVassalThatHatesAComradeGoesToTheCouncil() {
        assertEquals(RealmCall.Answer.RECONCILE, RealmCall.call(35, true, 0, 10, 10), "the feud ended by decree");
        assertEquals(RealmCall.Answer.EXCUSE, RealmCall.call(34, true, 0, 10, 30), "not loyal enough to obey: excused for scutage");
        assertEquals(RealmCall.Answer.EXCUSE, RealmCall.call(80, true, 0, 40, 30), "too strong to be ordered: excused for scutage");
        assertEquals(RealmCall.Answer.CAST_OUT, RealmCall.call(19, true, 0, 10, 30), "a disloyal feuder is cast out");
        // in peacetime there is no war to be excused from
        assertEquals(RealmCall.Answer.RECONCILE, RealmCall.council(60, 10, 30, false));
        assertEquals(RealmCall.Answer.CAST_OUT, RealmCall.council(30, 10, 30, false));
        assertEquals(RealmCall.Answer.CAST_OUT, RealmCall.council(80, 40, 30, false));
        assertTrue(RealmCall.blameFirst(30, 50), "of two feuding vassals, the less loyal answers for it");
        assertFalse(RealmCall.blameFirst(50, 30));
    }

    @Test
    void hatredIsSharedButNotFondness() {
        assertEquals(-60, RealmCall.shared(20, -60, 5));
        assertEquals(-95, RealmCall.shared(-95, -50));
        assertEquals(-50, RealmCall.shared(-50, 90));
        assertNull(RealmCall.shared(-49, 0, 100), "dislike short of hatred stays each village's own");
        assertNull(RealmCall.shared());
    }

    @Test
    void scutageIsPaidPerSoldierKeptHome() {
        assertEquals(64 * 12, RealmCall.scutage(12));
        assertEquals(64, RealmCall.scutage(0), "a vassal with no soldiers still pays");
    }
}

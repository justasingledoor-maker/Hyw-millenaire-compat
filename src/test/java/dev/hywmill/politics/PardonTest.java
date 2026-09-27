package dev.hywmill.politics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M5-3: outlawry, the ordinary pardon (hysteresis) and the formal pardon (weregild in reputation). */
class PardonTest {
    static final PoliticsTables T = PoliticsTables.DEFAULTS;

    static PoliticsRecord outlawByPeacetimeKill(long tick, int rep) {
        PoliticsRecord r = new PoliticsRecord();
        r.grievances.add(new GrievanceEvent(GrievanceKind.KILL_RESIDENT, tick, true, true, false, 0, 64, 0), T.grievance());
        assertTrue(r.refresh(tick, rep, T));
        assertEquals(Standing.OUTLAW, r.status);
        return r;
    }

    @Test
    void notOutlawCannotBuyAPardon() {
        Pardon.Quote q = Pardon.quote(new PoliticsRecord(), 0, 0, T);
        assertEquals(Pardon.Outcome.NOT_OUTLAW, q.outcome());
        assertEquals(0, q.price());
    }

    @Test
    void priceIsGrievanceAboveTheLinePlusTheKillFee() {
        PoliticsRecord r = outlawByPeacetimeKill(1000, 20000);
        // 100 x 1.5 inside = 150; residual 19 -> 131 points x 32 + 1024
        Pardon.Quote q = Pardon.quote(r, 1000, 20000, T);
        assertTrue(q.ok());
        assertEquals(131 * 32 + 1024, q.price());
        assertEquals(20000 - q.price(), q.repAfter());
    }

    @Test
    void tooPoorWhenReputationWouldFallToTheBoycottLine() {
        PoliticsRecord r = outlawByPeacetimeKill(0, 0);
        Pardon.Quote q = Pardon.quote(r, 0, 0, T);
        assertEquals(Pardon.Outcome.TOO_POOR, q.outcome());
        int enough = q.price() + T.standing().boycott() + 1;
        assertTrue(Pardon.quote(r, 0, enough, T).ok());
        assertFalse(Pardon.quote(r, 0, enough - 1, T).ok(), "reputation must stay above the boycott line after paying");
    }

    @Test
    void payingPardonsAtOnceAndClearsThePeacetimeKilling() {
        PoliticsRecord r = outlawByPeacetimeKill(0, 10000);
        Pardon.Quote q = Pardon.quote(r, 100, 10000, T);
        Standing after = Pardon.apply(r, 100, q.repAfter(), T);
        assertNotEquals(Standing.OUTLAW, after);
        assertFalse(r.grievances.peacetimeKillPending());
        assertTrue(r.grievances.decayed(100, T.grievance()) < T.grievance().pardon());
    }

    @Test
    void ordinaryPardonNeedsDecayAndReputation() {
        PoliticsRecord r = new PoliticsRecord();
        // outside, not peacetime-immediate: 100 grievance, reputation at the boycott line -> outlaw by the normal rule
        r.grievances.add(new GrievanceEvent(GrievanceKind.KILL_RESIDENT, 0, false, true, false, 0, 0, 0), T.grievance());
        r.refresh(0, -2000, T);
        assertEquals(Standing.OUTLAW, r.status);
        long threeHalfLives = 3 * T.grievance().halfLifeTicks();
        assertFalse(r.refresh(threeHalfLives, -2000, T), "decayed (12.5) but reputation still below the line");
        assertEquals(Standing.OUTLAW, r.status);
        assertTrue(r.refresh(threeHalfLives, 0, T), "decayed and reputation restored (donations): pardoned");
        assertEquals(Standing.STRANGER, r.status);
    }

    @Test
    void disabledByData() {
        PoliticsTables t = new PoliticsTables(T.standing(), T.grievance(), T.favor(), new PoliticsTables.PardonRule(false, 32, 1024));
        PoliticsRecord r = outlawByPeacetimeKill(0, 50000);
        assertEquals(Pardon.Outcome.DISABLED, Pardon.quote(r, 0, 50000, t).outcome());
    }
}

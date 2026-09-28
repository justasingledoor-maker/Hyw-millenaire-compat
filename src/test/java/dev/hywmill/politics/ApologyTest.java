package dev.hywmill.politics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5: the apology (Millénaire money settles the grievance of a player who is not an outlaw). */
class ApologyTest {
    static final PoliticsTables T = PoliticsTables.DEFAULTS;

    /** Three hits on the garrison inside the village (3 x 6 x 1.5 = 27): Unwelcome with Trusted reputation. */
    static PoliticsRecord unwelcome(long tick, int rep) {
        PoliticsRecord r = new PoliticsRecord();
        for (int i = 0; i < 3; i++) {
            r.grievances.add(new GrievanceEvent(GrievanceKind.ASSAULT_GARRISON, tick, true, true, false, 0, 64, 0), T.grievance());
        }
        r.refresh(tick, rep, T);
        assertEquals(Standing.UNWELCOME, r.status);
        return r;
    }

    @Test
    void priceIsDeniersPerGrievancePoint() {
        PoliticsRecord r = unwelcome(0, 5000);
        Apology.Quote q = Apology.quote(r, 0, 100000, T);
        assertTrue(q.ok());
        assertEquals(27 * 64, q.price());
    }

    @Test
    void payingClearsTheGrievanceAndRestoresTheStanding() {
        PoliticsRecord r = unwelcome(0, 5000);
        assertEquals(Standing.TRUSTED, Apology.apply(r, 10, 5000, T));
        assertEquals(0, r.grievances.decayed(10, T.grievance()));
        assertEquals(Apology.Outcome.NOTHING_TO_SETTLE, Apology.quote(r, 10, 100000, T).outcome());
    }

    @Test
    void tooPoorKeepsNothingChanged() {
        PoliticsRecord r = unwelcome(0, 5000);
        assertEquals(Apology.Outcome.TOO_POOR, Apology.quote(r, 0, 27 * 64 - 1, T).outcome());
        assertEquals(Standing.UNWELCOME, r.status);
    }

    @Test
    void outlawsMustSeekAPardonInstead() {
        PoliticsRecord r = PardonTest.outlawByPeacetimeKill(0, 20000);
        assertEquals(Apology.Outcome.OUTLAW, Apology.quote(r, 0, Integer.MAX_VALUE, T).outcome());
    }

    @Test
    void zeroPriceDisablesApologies() {
        PoliticsTables t = new PoliticsTables(T.standing(), T.grievance(), T.favor(), new PoliticsTables.PardonRule(true, 32, 1024, 0));
        assertEquals(Apology.Outcome.DISABLED, Apology.quote(unwelcome(0, 5000), 0, 100000, t).outcome());
    }
}

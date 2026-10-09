package dev.hywmill.politics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 amnesty: a village that lost a siege forgives the winners' helpers and trusts them. */
class AmnestyTest {
    static final PoliticsTables T = PoliticsTables.DEFAULTS;

    @Test
    void anOutlawOfTheWarIsPardonedAndTrusted() {
        PoliticsRecord r = new PoliticsRecord();
        r.status = Standing.OUTLAW;
        r.grievances.add(new GrievanceEvent(GrievanceKind.KILL_RESIDENT, 100, true, true, false, 0, 64, 0), T.grievance()); // inside, peacetime
        int rep = -6000;
        int raise = Amnesty.raise(rep, T);
        assertEquals(T.standing().trusted() - rep, raise);
        assertEquals(Standing.TRUSTED, Amnesty.apply(r, 200, rep + raise, T));
        assertTrue(r.grievances.isEmpty(), "every grievance (the peacetime killing too) is forgiven");
    }

    @Test
    void aBetterStandingIsKept() {
        PoliticsRecord r = new PoliticsRecord();
        r.status = Standing.PATRON;
        assertEquals(0, Amnesty.raise(T.standing().patron() + 100, T), "already above the Trusted line: nothing to raise");
        assertNotEquals(Standing.OUTLAW, Amnesty.apply(r, 200, T.standing().patron() + 100, T));
    }
}

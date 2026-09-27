package dev.hywmill.politics.service;

import dev.hywmill.politics.FavorSource;
import dev.hywmill.politics.GrievanceEvent;
import dev.hywmill.politics.GrievanceKind;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.Standing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M5-5: long good standing earns one Favor a month at Trusted or better with no grievance. */
class TrickleTest {
    static final PoliticsTables T = PoliticsTables.DEFAULTS;

    @Test
    void monthlyAtTrustedWithoutGrievance() {
        PoliticsRecord r = new PoliticsRecord();
        r.status = Standing.TRUSTED;
        assertTrue(PoliticsService.trickle(r, 1000, T));
        assertEquals(0, r.favor.points(), "the month starts");
        assertFalse(PoliticsService.trickle(r, 1000 + PoliticsService.TRICKLE_PERIOD - 1, T));
        assertTrue(PoliticsService.trickle(r, 1000 + PoliticsService.TRICKLE_PERIOD, T));
        assertEquals(T.favor().amount(FavorSource.LONG_STANDING), r.favor.points());
    }

    @Test
    void strangersAndGrievancesEarnNothingAndRestartTheMonth() {
        PoliticsRecord r = new PoliticsRecord();
        assertFalse(PoliticsService.trickle(r, 0, T));
        r.status = Standing.PATRON;
        PoliticsService.trickle(r, 0, T);
        r.grievances.add(new GrievanceEvent(GrievanceKind.ASSAULT_RESIDENT, 10, false, true, false, 0, 0, 0), T.grievance());
        assertTrue(PoliticsService.trickle(r, 20, T));
        assertEquals(-1, r.lastTrickle);
        assertFalse(PoliticsService.trickle(r, PoliticsService.TRICKLE_PERIOD + 5, T));
        assertEquals(0, r.favor.points());
    }
}

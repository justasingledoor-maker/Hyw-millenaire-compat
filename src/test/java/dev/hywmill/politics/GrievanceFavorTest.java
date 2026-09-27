package dev.hywmill.politics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M5-1: grievance severity/decay/context and Favor sources and caps. */
class GrievanceFavorTest {
    static final PoliticsTables T = PoliticsTables.DEFAULTS;
    static final long DAY = PoliticsTables.DAY;

    static GrievanceEvent ev(GrievanceKind k, long tick, boolean inside, boolean peace, boolean self) {
        return new GrievanceEvent(k, tick, inside, peace, self, 1, 2, 3);
    }

    @Test
    void severityDependsOnContext() {
        Grievances a = new Grievances();
        assertEquals(100, a.add(ev(GrievanceKind.KILL_RESIDENT, 0, false, true, false), T.grievance()), 1e-9);
        Grievances b = new Grievances();
        assertEquals(150, b.add(ev(GrievanceKind.KILL_RESIDENT, 0, true, true, false), T.grievance()), 1e-9);
        Grievances c = new Grievances();
        assertEquals(25, c.add(ev(GrievanceKind.KILL_RESIDENT, 0, false, false, true), T.grievance()), 1e-9, "self-defence");
    }

    @Test
    void onlyAPeacetimeKillingInsideSetsTheImmediateFlag() {
        Grievances g = new Grievances();
        g.add(ev(GrievanceKind.KILL_GARRISON, 0, false, true, false), T.grievance());
        assertFalse(g.peacetimeKillPending(), "outside the village");
        g.add(ev(GrievanceKind.KILL_RESIDENT, 1, true, false, false), T.grievance());
        assertFalse(g.peacetimeKillPending(), "in wartime");
        g.add(ev(GrievanceKind.KILL_RESIDENT, 2, true, true, true), T.grievance());
        assertFalse(g.peacetimeKillPending(), "self-defence");
        g.add(ev(GrievanceKind.ASSAULT_RESIDENT, 3, true, true, false), T.grievance());
        assertFalse(g.peacetimeKillPending(), "an assault is not a killing");
        g.add(ev(GrievanceKind.KILL_RESIDENT, 4, true, true, false), T.grievance());
        assertTrue(g.peacetimeKillPending());
        g.clearPeacetimeKill();
        assertFalse(g.peacetimeKillPending());
    }

    @Test
    void decaysWithTheHalfLife() {
        Grievances g = new Grievances();
        g.add(ev(GrievanceKind.KILL_RESIDENT, 0, false, true, false), T.grievance());
        assertEquals(100, g.decayed(0, T.grievance()), 1e-9);
        assertEquals(50, g.decayed(21 * DAY, T.grievance()), 1e-6);
        assertEquals(25, g.decayed(42 * DAY, T.grievance()), 1e-6);
        g.settle(21 * DAY, T.grievance());
        assertEquals(50, g.value(), 1e-6);
        assertEquals(25, g.decayed(42 * DAY, T.grievance()), 1e-6, "settling does not change the curve");
    }

    @Test
    void anIsolatedDistantIncidentDoesNotMakeAnOutlaw() {
        // one killing away from the village, player otherwise neutral: unwelcome, then it fades
        PoliticsRecord r = new PoliticsRecord();
        r.grievances.add(ev(GrievanceKind.KILL_RESIDENT, 0, false, true, false), T.grievance());
        r.refresh(0, 0, T);
        assertEquals(Standing.UNWELCOME, r.status);
        r.refresh(60 * DAY, 0, T);
        assertEquals(Standing.STRANGER, r.status);
    }

    @Test
    void outlawIsPardonedAndTheFlagCleared() {
        PoliticsRecord r = new PoliticsRecord();
        r.grievances.add(ev(GrievanceKind.KILL_RESIDENT, 0, true, true, false), T.grievance());
        assertTrue(r.refresh(0, 5000, T));
        assertEquals(Standing.OUTLAW, r.status, "immediate, whatever the reputation");
        r.refresh(40 * DAY, 5000, T);
        assertEquals(Standing.OUTLAW, r.status, "grievance still above pardon (150 -> ~40)");
        r.refresh(80 * DAY, 5000, T);
        assertEquals(Standing.TRUSTED, r.status);
        assertFalse(r.grievances.peacetimeKillPending());
        assertEquals(80 * DAY, r.statusSince);
    }

    @Test
    void favorHasNoTradeSourceAndRespectsTheCap() {
        assertTrue(Arrays.stream(FavorSource.values()).noneMatch(s -> s.name().contains("TRADE")));
        Favor f = new Favor();
        for (int i = 0; i < 40; i++) {
            f.earn(FavorSource.DEFENSE, T.favor());
        }
        assertEquals(100, f.points());
        assertEquals(100, f.earnedTotal());
        assertTrue(f.spend(30));
        assertFalse(f.spend(71));
        assertEquals(70, f.points());
        assertEquals(20, f.lose(20));
        assertEquals(100, f.earnedTotal(), "losses do not reduce the total ever earned");
    }

    @Test
    void defaultRecordsAndExpiredTrucesArePruned() {
        VillagePolitics p = new VillagePolitics();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        p.get(a);
        p.get(b).favor.earn(FavorSource.LONG_STANDING, T.favor());
        p.setTruce(UUID.randomUUID(), 100);
        UUID keep = UUID.randomUUID();
        p.setTruce(keep, 1000);
        assertEquals(2, p.prune(500));
        assertEquals(1, p.players().size());
        assertTrue(p.truceWith(keep, 500));
        assertFalse(p.truceWith(keep, 1000));
    }
}

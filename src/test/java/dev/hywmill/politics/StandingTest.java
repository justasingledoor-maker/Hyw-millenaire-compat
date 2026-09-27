package dev.hywmill.politics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M5-1: standing rules, including the approved outlaw rules (§18.3 Q1) and hysteresis. */
class StandingTest {
    static final PoliticsTables T = PoliticsTables.DEFAULTS;

    static Standing eval(Standing prev, int rep, double g) {
        return Standing.evaluate(prev, rep, g, false, 0, 0, T).standing();
    }

    @Test
    void reputationBands() {
        assertEquals(Standing.STRANGER, eval(Standing.STRANGER, 0, 0));
        assertEquals(Standing.UNWELCOME, eval(Standing.STRANGER, -1024, 0));
        assertEquals(Standing.TRUSTED, eval(Standing.STRANGER, 4096, 0));
        assertEquals(Standing.TRUSTED, eval(Standing.STRANGER, 8192, 0), "patron needs favor");
        assertEquals(Standing.PATRON, Standing.evaluate(Standing.STRANGER, 8192, 0, false, 20, 20, T).standing());
        assertEquals(Standing.SWORN, Standing.evaluate(Standing.STRANGER, 32768, 0, false, 0, 60, T).standing());
        assertEquals(Standing.PATRON, Standing.evaluate(Standing.STRANGER, 32768, 0, false, 20, 59, T).standing(),
                "sworn needs favor ever earned");
    }

    @Test
    void outlawNormalRuleNeedsSeriousGrievanceAndBoycottReputation() {
        assertEquals(Standing.UNWELCOME, eval(Standing.STRANGER, 0, 80), "serious grievance alone: unwelcome, not outlaw");
        assertEquals(Standing.UNWELCOME, eval(Standing.STRANGER, -1024, 79), "boycott reputation with a minor grievance");
        assertEquals(Standing.OUTLAW, eval(Standing.STRANGER, -1024, 80));
        assertEquals(Standing.OUTLAW, eval(Standing.TRUSTED, -2000, 120));
    }

    @Test
    void dropsTheReputationAloneRule() {
        // the earlier draft made reputation <= -4096 alone enough; the approved rule does not
        assertEquals(Standing.UNWELCOME, eval(Standing.STRANGER, -10000, 0));
        assertEquals(Standing.UNWELCOME, eval(Standing.STRANGER, -10000, 79));
    }

    @Test
    void peacetimeKillingInsideTheVillageIsImmediate() {
        Standing.Result r = Standing.evaluate(Standing.SWORN, 40000, 0, true, 100, 500, T);
        assertEquals(Standing.OUTLAW, r.standing(), "regardless of reputation");
    }

    @Test
    void outlawryHasHysteresisAndPardon() {
        // still outlaw while the grievance is at/above the pardon threshold, even with good reputation
        assertEquals(Standing.OUTLAW, eval(Standing.OUTLAW, 5000, 20));
        // still outlaw while reputation is at/below the boycott line, even with no grievance
        assertEquals(Standing.OUTLAW, eval(Standing.OUTLAW, -1024, 0));
        Standing.Result r = Standing.evaluate(Standing.OUTLAW, -1023, 19.9, true, 0, 0, T);
        assertTrue(r.pardoned());
        assertEquals(Standing.STRANGER, r.standing());
        // the pending peacetime killing does not keep a pardoned player outlawed
        Standing.Result p = Standing.evaluate(Standing.OUTLAW, 100, 0, true, 0, 0, T);
        assertTrue(p.pardoned());
        assertFalse(p.standing() == Standing.OUTLAW);
    }

    @Test
    void statusIsSlowerToLoseThanToGain() {
        assertEquals(Standing.STRANGER, eval(Standing.STRANGER, 4000, 0), "gaining needs the full threshold");
        assertEquals(Standing.TRUSTED, eval(Standing.TRUSTED, 3700, 0), "kept down to 90%");
        assertEquals(Standing.STRANGER, eval(Standing.TRUSTED, 3600, 0));
        assertEquals(Standing.PATRON, Standing.evaluate(Standing.PATRON, 7400, 0, false, 18, 18, T).standing());
        assertEquals(Standing.TRUSTED, Standing.evaluate(Standing.PATRON, 7300, 0, false, 18, 18, T).standing());
        // any grievance >= warning ends it at once
        assertEquals(Standing.UNWELCOME, eval(Standing.SWORN, 40000, 20));
    }
}

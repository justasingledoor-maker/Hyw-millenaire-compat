package dev.hywmill.politics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M5-4: envoy requirements, the logistic odds, the seeded roll and the relation change. */
class DiplomacyOddsTest {
    static final PoliticsTables.DiplomacyRule R = PoliticsTables.DEFAULTS.diplomacy();

    static DiplomacyOdds.Context ctx(EnvoyKind k, Standing a, int relation) {
        return new DiplomacyOdds.Context(k, a, Standing.STRANGER, relation, false, true, 300, 50, 50, 0);
    }

    @Test
    void requirementsByKind() {
        assertEquals(DiplomacyOdds.Refusal.STANDING_TOO_LOW, DiplomacyOdds.check(EnvoyKind.RECONCILE, Standing.STRANGER, Standing.STRANGER, 0, -50, false, R));
        assertEquals(DiplomacyOdds.Refusal.OK, DiplomacyOdds.check(EnvoyKind.RECONCILE, Standing.TRUSTED, Standing.STRANGER, 0, -50, false, R));
        assertEquals(DiplomacyOdds.Refusal.UNKNOWN_TO_OTHER, DiplomacyOdds.check(EnvoyKind.RECONCILE, Standing.TRUSTED, Standing.STRANGER, -1, -50, false, R));
        assertEquals(DiplomacyOdds.Refusal.UNKNOWN_TO_OTHER, DiplomacyOdds.check(EnvoyKind.RECONCILE, Standing.TRUSTED, Standing.UNWELCOME, 100, -50, false, R));
        assertEquals(DiplomacyOdds.Refusal.STANDING_TOO_LOW, DiplomacyOdds.check(EnvoyKind.TRUCE, Standing.TRUSTED, Standing.STRANGER, 0, -95, false, R));
        assertEquals(DiplomacyOdds.Refusal.NOT_IN_CONFLICT, DiplomacyOdds.check(EnvoyKind.TRUCE, Standing.PATRON, Standing.STRANGER, 0, -60, false, R));
        assertEquals(DiplomacyOdds.Refusal.OK, DiplomacyOdds.check(EnvoyKind.TRUCE, Standing.PATRON, Standing.STRANGER, 0, -60, true, R), "a raid under way");
        assertEquals(DiplomacyOdds.Refusal.OK, DiplomacyOdds.check(EnvoyKind.TRUCE, Standing.PATRON, Standing.STRANGER, 0, -90, false, R));
        assertEquals(DiplomacyOdds.Refusal.STANDING_TOO_LOW, DiplomacyOdds.check(EnvoyKind.SOW_DISCORD, Standing.PATRON, Standing.STRANGER, 0, 20, false, R));
        assertEquals(DiplomacyOdds.Refusal.TRUSTED_BY_TARGET, DiplomacyOdds.check(EnvoyKind.SOW_DISCORD, Standing.SWORN, Standing.TRUSTED, 0, 20, false, R));
        assertEquals(DiplomacyOdds.Refusal.OK, DiplomacyOdds.check(EnvoyKind.SOW_DISCORD, Standing.SWORN, Standing.STRANGER, 0, 20, false, R));
        assertEquals(DiplomacyOdds.Refusal.OK, DiplomacyOdds.check(EnvoyKind.ENCOURAGE, Standing.TRUSTED, Standing.STRANGER, -5000, 20, false, R));
    }

    @Test
    void chanceRisesWithStandingAndFallsWithDistanceAndAttempts() {
        double trusted = DiplomacyOdds.chance(ctx(EnvoyKind.RECONCILE, Standing.TRUSTED, -40), R);
        double sworn = DiplomacyOdds.chance(ctx(EnvoyKind.RECONCILE, Standing.SWORN, -40), R);
        assertTrue(sworn > trusted);
        var far = new DiplomacyOdds.Context(EnvoyKind.RECONCILE, Standing.TRUSTED, Standing.STRANGER, -40, false, true, 3000, 50, 50, 0);
        var again = new DiplomacyOdds.Context(EnvoyKind.RECONCILE, Standing.TRUSTED, Standing.STRANGER, -40, false, true, 300, 50, 50, 1);
        assertTrue(DiplomacyOdds.chance(far, R) < trusted);
        assertTrue(DiplomacyOdds.chance(again, R) < trusted);
        for (Standing s : Standing.values()) {
            double p = DiplomacyOdds.chance(ctx(EnvoyKind.TRUCE, s, -95), R);
            assertTrue(p > 0 && p < 1);
        }
    }

    @Test
    void weakerSideIsKeenerOnPeaceAndConflictHurts() {
        var weakA = new DiplomacyOdds.Context(EnvoyKind.TRUCE, Standing.PATRON, Standing.STRANGER, -95, false, true, 300, 10, 90, 0);
        var strongA = new DiplomacyOdds.Context(EnvoyKind.TRUCE, Standing.PATRON, Standing.STRANGER, -95, false, true, 300, 90, 10, 0);
        assertTrue(DiplomacyOdds.chance(weakA, R) > DiplomacyOdds.chance(strongA, R));
        var calm = ctx(EnvoyKind.RECONCILE, Standing.TRUSTED, -40);
        var raid = new DiplomacyOdds.Context(EnvoyKind.RECONCILE, Standing.TRUSTED, Standing.STRANGER, -40, true, true, 300, 50, 50, 0);
        assertTrue(DiplomacyOdds.chance(raid, R) < DiplomacyOdds.chance(calm, R));
    }

    @Test
    void implausibleProposalsAreUnlikelyAndSmall() {
        var friends = ctx(EnvoyKind.RECONCILE, Standing.TRUSTED, 80);
        var enemies = ctx(EnvoyKind.RECONCILE, Standing.TRUSTED, 20);
        assertFalse(DiplomacyOdds.plausible(friends));
        assertTrue(DiplomacyOdds.chance(friends, R) < DiplomacyOdds.chance(enemies, R));
        assertTrue(DiplomacyOdds.delta(friends, new DiplomacyOdds.Draws(0, 1, 0), R) <= 2);
    }

    @Test
    void rollIsDeterministicAndBackfireIsTheWorstShareOfFailures() {
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(DiplomacyOdds.Draws.of(seed), DiplomacyOdds.Draws.of(seed));
        }
        assertEquals(DiplomacyOdds.Outcome.SUCCESS, DiplomacyOdds.roll(0.5, new DiplomacyOdds.Draws(0.49, 0, 0), R));
        assertEquals(DiplomacyOdds.Outcome.FAILURE, DiplomacyOdds.roll(0.5, new DiplomacyOdds.Draws(0.6, 0, 0), R));
        assertEquals(DiplomacyOdds.Outcome.BACKFIRE, DiplomacyOdds.roll(0.5, new DiplomacyOdds.Draws(0.9, 0, 0), R));
        // over many seeds the observed success rate follows the chance
        int ok = 0;
        for (long seed = 0; seed < 4000; seed++) {
            if (DiplomacyOdds.roll(0.3, DiplomacyOdds.Draws.of(seed * 7919), R) == DiplomacyOdds.Outcome.SUCCESS) {
                ok++;
            }
        }
        assertEquals(0.3, ok / 4000.0, 0.03);
    }

    @Test
    void deltaFollowsMillenaireScaleWithJitter() {
        var c = ctx(EnvoyKind.ENCOURAGE, Standing.TRUSTED, 30);
        assertEquals(4, DiplomacyOdds.delta(c, new DiplomacyOdds.Draws(0, 0, 0), R)); // 5 x 0.8
        assertEquals(6, DiplomacyOdds.delta(c, new DiplomacyOdds.Draws(0, 1, 0), R)); // 5 x 1.2
        // reconcile helps more when relations are very bad
        assertTrue(DiplomacyOdds.delta(ctx(EnvoyKind.RECONCILE, Standing.TRUSTED, -80), new DiplomacyOdds.Draws(0, 0.5, 0), R)
                > DiplomacyOdds.delta(ctx(EnvoyKind.RECONCILE, Standing.TRUSTED, -10), new DiplomacyOdds.Draws(0, 0.5, 0), R));
    }

    @Test
    void travelAndSowDiscordLimits() {
        assertEquals(1000, DiplomacyOdds.travelTicks(50, R), "at least one in-game hour");
        assertEquals(5000, DiplomacyOdds.travelTicks(1000, R), "an hour per 200 blocks");
        assertEquals(10, DiplomacyOdds.sowFavorCost(0, R));
        assertEquals(20, DiplomacyOdds.sowFavorCost(2, R), "cost rises with each recent attempt");
        assertFalse(DiplomacyOdds.exposed(0, new DiplomacyOdds.Draws(0, 0, 0.25), R));
        assertTrue(DiplomacyOdds.exposed(1, new DiplomacyOdds.Draws(0, 0, 0.25), R), "exposure grows with repeated attempts");
        PoliticsRecord r = new PoliticsRecord();
        r.lastSowDiscord = 100;
        r.sowAttempts = 3;
        assertEquals(3, r.recentSowAttempts(100 + R.sowRecent(), R.sowRecent()));
        assertEquals(0, r.recentSowAttempts(101 + R.sowRecent(), R.sowRecent()));
    }
}

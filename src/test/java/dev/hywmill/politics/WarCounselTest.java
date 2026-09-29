package dev.hywmill.politics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 war and peace counsel: the pure rules. */
class WarCounselTest {
    static final PoliticsTables.WarCounselRule R = PoliticsTables.WarCounselRule.DEFAULT;

    static WarCounsel.Facts facts(WarCounsel.Kind k, boolean war, boolean truce, Standing s, long last, int points) {
        return new WarCounsel.Facts(k, war, truce, false, s, 100_000, last, points);
    }

    @Test
    void checksFollowTheKindAndTheCosts() {
        assertEquals(WarCounsel.Refusal.OK, WarCounsel.check(facts(WarCounsel.Kind.WAR, false, false, Standing.PATRON, -1, 2), R));
        assertEquals(WarCounsel.Refusal.ALREADY_AT_WAR, WarCounsel.check(facts(WarCounsel.Kind.WAR, true, false, Standing.PATRON, -1, 2), R));
        assertEquals(WarCounsel.Refusal.NOT_AT_WAR, WarCounsel.check(facts(WarCounsel.Kind.PEACE, false, false, Standing.PATRON, -1, 2), R));
        assertEquals(WarCounsel.Refusal.TRUCE, WarCounsel.check(facts(WarCounsel.Kind.WAR, false, true, Standing.SWORN, -1, 2), R));
        assertEquals(WarCounsel.Refusal.STANDING_TOO_LOW, WarCounsel.check(facts(WarCounsel.Kind.WAR, false, false, Standing.TRUSTED, -1, 2), R));
        assertEquals(WarCounsel.Refusal.COOLDOWN, WarCounsel.check(facts(WarCounsel.Kind.PEACE, true, false, Standing.SWORN, 90_000, 2), R));
        assertEquals(WarCounsel.Refusal.NO_DIPLOMACY_POINT, WarCounsel.check(facts(WarCounsel.Kind.WAR, false, false, Standing.SWORN, -1, 1), R));
        assertEquals(WarCounsel.Refusal.OK, WarCounsel.check(facts(WarCounsel.Kind.PEACE, true, false, Standing.SWORN, -1, 1), R), "peace costs 1");
        assertEquals(WarCounsel.Refusal.LONE_BUILDING, WarCounsel.check(
                new WarCounsel.Facts(WarCounsel.Kind.WAR, false, false, true, Standing.SWORN, 0, -1, 5), R));
    }

    @Test
    void councilWeighsBadBloodAndStrength() {
        double even = WarCounsel.councilChance(WarCounsel.Kind.WAR, Standing.PATRON, 0, 10, 10, R);
        assertEquals(0.4, even, 1e-9, "even armies, neutral relation: the standing's chance");
        assertTrue(WarCounsel.councilChance(WarCounsel.Kind.WAR, Standing.PATRON, -80, 10, 10, R) > even, "bad blood helps");
        assertTrue(WarCounsel.councilChance(WarCounsel.Kind.WAR, Standing.PATRON, 80, 10, 10, R) < even, "friends are reluctant");
        assertTrue(WarCounsel.councilChance(WarCounsel.Kind.WAR, Standing.PATRON, 0, 5, 20, R) < 0.1, "a much stronger enemy deters");
        assertTrue(WarCounsel.councilChance(WarCounsel.Kind.WAR, Standing.SWORN, 0, 10, 10, R) > even);
        double losing = WarCounsel.councilChance(WarCounsel.Kind.PEACE, Standing.PATRON, -100, 5, 20, R);
        double winning = WarCounsel.councilChance(WarCounsel.Kind.PEACE, Standing.PATRON, -100, 20, 5, R);
        assertTrue(losing > winning, "a village that is losing wants peace more");
        assertEquals(R.minChance(), WarCounsel.councilChance(WarCounsel.Kind.WAR, Standing.TRUSTED, 0, 10, 10, R), 1e-9);
    }

    @Test
    void enemyAcceptsPeaceByTheBalanceOfArms() {
        assertEquals(0.5, WarCounsel.enemyAccepts(10, 10, R), 1e-9);
        assertEquals(0.8, WarCounsel.enemyAccepts(20, 10, R), 1e-9);
        assertEquals(R.enemyMax(), WarCounsel.enemyAccepts(100, 1, R), 1e-9);
        assertEquals(R.enemyMin(), WarCounsel.enemyAccepts(1, 100, R), 1e-9);
        assertEquals(0.5, WarCounsel.share(0, 0, 2), 1e-9);
    }
}

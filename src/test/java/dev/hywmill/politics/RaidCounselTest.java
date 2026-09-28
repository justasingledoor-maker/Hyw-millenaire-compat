package dev.hywmill.politics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5: raid counsel (a player on campaign suggests that the village raid the enemy). */
class RaidCounselTest {
    static final PoliticsTables.RaidCounselRule R = PoliticsTables.RaidCounselRule.DEFAULT;

    static RaidCounsel.Facts ok() {
        return new RaidCounsel.Facts(true, true, false, false, 40, 30, 100_000, -1, 5);
    }

    @Test
    void allowedWhenAtWarOnCampaignAndIdle() {
        assertEquals(RaidCounsel.Refusal.OK, RaidCounsel.check(ok(), R));
    }

    @Test
    void refusalsInOrder() {
        assertEquals(RaidCounsel.Refusal.NOT_AT_WAR, RaidCounsel.check(new RaidCounsel.Facts(false, true, false, false, 40, 30, 100_000, -1, 5), R));
        assertEquals(RaidCounsel.Refusal.NOT_ON_CAMPAIGN, RaidCounsel.check(new RaidCounsel.Facts(true, false, false, false, 40, 30, 100_000, -1, 5), R));
        assertEquals(RaidCounsel.Refusal.ALREADY_RAIDING, RaidCounsel.check(new RaidCounsel.Facts(true, true, true, false, 40, 30, 100_000, -1, 5), R));
        assertEquals(RaidCounsel.Refusal.TARGET_UNDER_ATTACK, RaidCounsel.check(new RaidCounsel.Facts(true, true, false, true, 40, 30, 100_000, -1, 5), R));
        assertEquals(RaidCounsel.Refusal.NO_RAIDERS, RaidCounsel.check(new RaidCounsel.Facts(true, true, false, false, 0, 30, 100_000, -1, 5), R));
        assertEquals(RaidCounsel.Refusal.NO_DIPLOMACY_POINT, RaidCounsel.check(new RaidCounsel.Facts(true, true, false, false, 40, 30, 100_000, -1, 0), R));
    }

    @Test
    void cooldownOfOneDayAfterAnyCounsel() {
        assertEquals(RaidCounsel.Refusal.COOLDOWN, RaidCounsel.check(new RaidCounsel.Facts(true, true, false, false, 40, 30, 100_000, 100_000 - 23_999, 5), R));
        assertEquals(RaidCounsel.Refusal.OK, RaidCounsel.check(new RaidCounsel.Facts(true, true, false, false, 40, 30, 100_000, 100_000 - 24_000, 5), R));
    }

    @Test
    void unknownPointsDoNotBlock() {
        assertEquals(RaidCounsel.Refusal.OK, RaidCounsel.check(new RaidCounsel.Facts(true, true, false, false, 40, 30, 100_000, -1, -1), R));
    }

    @Test
    void chanceRisesWithStanding() {
        assertEquals(0.0, RaidCounsel.chance(Standing.STRANGER, 40, 30, R));
        assertEquals(0.35, RaidCounsel.chance(Standing.TRUSTED, 40, 30, R), 1e-9);
        assertEquals(0.55, RaidCounsel.chance(Standing.PATRON, 40, 30, R), 1e-9);
        assertEquals(0.75, RaidCounsel.chance(Standing.SWORN, 40, 30, R), 1e-9);
    }

    @Test
    void tooStrongTargetCutsTheChanceByMillenairesOwnRule() {
        assertFalse(RaidCounsel.tooStrong(40, 79));
        assertTrue(RaidCounsel.tooStrong(40, 80));
        assertEquals(0.75 * 0.3, RaidCounsel.chance(Standing.SWORN, 40, 80, R), 1e-9);
        assertEquals(R.minChance(), RaidCounsel.chance(Standing.TRUSTED, 1, 1000, new PoliticsTables.RaidCounselRule(true, R.chance(), 0.01, 0.05, 0.9, 1, 24000)), 1e-9);
    }

    @Test
    void drawIsDeterministicPerSeedAndInRange() {
        double d = RaidCounsel.draw(42);
        assertEquals(d, RaidCounsel.draw(42));
        assertTrue(d >= 0 && d < 1);
        assertEquals("likely", RaidCounsel.band(0.75));
        assertEquals("uncertain", RaidCounsel.band(0.35));
        assertEquals("unlikely", RaidCounsel.band(0.2));
    }

    @Test
    void disabledRefusesEverything() {
        PoliticsTables.RaidCounselRule off = new PoliticsTables.RaidCounselRule(false, R.chance(), 0.3, 0.05, 0.9, 1, 24000);
        assertEquals(RaidCounsel.Refusal.DISABLED, RaidCounsel.check(ok(), off));
    }
}

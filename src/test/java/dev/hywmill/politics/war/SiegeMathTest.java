package dev.hywmill.politics.war;

import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.Standing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 sieges: the pure rules. */
class SiegeMathTest {
    static final PoliticsTables.SiegeRule R = PoliticsTables.SiegeRule.DEFAULT;

    @Test
    void strengthGrowsWithCostAndEquipment() {
        assertEquals(2.0, SiegeMath.unitStrength(2, 0), 1e-9);
        assertEquals(3.0, SiegeMath.unitStrength(2, 2), 1e-9);
        assertEquals(1.0, SiegeMath.unitStrength(0, 0), 1e-9);
    }

    @Test
    void defenseWeighsMillenaireAndCapsFortification() {
        assertEquals((100 + 0.3 * 90) * 1.2, SiegeMath.defense(100, 90, 20, R), 1e-9);
        assertEquals(100 * 1.5, SiegeMath.defense(100, 0, 400, R), 1e-9);
    }

    @Test
    void winChanceIsEvenAtParityAndFavoursTheStronger() {
        assertEquals(0.5, SiegeMath.winChance(100, 100, R), 1e-9);
        assertTrue(SiegeMath.winChance(200, 100, R) > 0.75);
        assertEquals(0, SiegeMath.winChance(0, 100, R));
        assertEquals(1, SiegeMath.winChance(10, 0, R));
    }

    @Test
    void theLoserLosesMoreThanTheWinner() {
        double[] l = SiegeMath.losses(true, 200, 100, R);
        assertEquals(0.175, l[0], 1e-9);
        assertEquals(0.5, l[1], 1e-9);
        double[] even = SiegeMath.losses(false, 100, 100, R);
        assertEquals(0.5, even[0], 1e-9);
        assertEquals(0.35, even[1], 1e-9);
        assertEquals(R.winnerLossMax(), SiegeMath.losses(true, 10, 1000, R)[0], 1e-9);
    }

    @Test
    void casualtiesAreSeededAndRounded() {
        List<UUID> slots = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            slots.add(new UUID(0, i));
        }
        List<UUID> a = SiegeMath.casualties(slots, 0.35, 7);
        assertEquals(4, a.size());
        assertEquals(a, SiegeMath.casualties(new ArrayList<>(slots.reversed()), 0.35, 7));
        assertTrue(slots.containsAll(a));
        assertEquals(0, SiegeMath.casualties(slots, 0, 7).size());
    }

    @Test
    void watchedBattleIsFoughtToTheLastSoldierOrTheDeadline() {
        assertEquals(15 * 60 * 20, R.battleTicks());
        assertEquals(Siege.Outcome.NONE, SiegeMath.battle(10, 12, 8, 10, false, R));
        assertEquals(Siege.Outcome.NONE, SiegeMath.battle(10, 12, 1, 10, false, R), "one defender left: still fighting");
        assertEquals(Siege.Outcome.NONE, SiegeMath.battle(1, 12, 8, 10, false, R), "one attacker left: still fighting");
        assertEquals(Siege.Outcome.WON, SiegeMath.battle(3, 12, 0, 10, false, R));
        assertEquals(Siege.Outcome.LOST, SiegeMath.battle(0, 12, 8, 10, false, R));
        PoliticsTables.SiegeRule early = new PoliticsTables.SiegeRule(true, 0.5, 6, 64, 0.4, 1, 1, 10, 1200, 1200, 1200, 12000, 1200, 6000, 0.2, 0.3,
                0.3, 2.0, 0.5, 0.35, 0.5, R.counselChance(), 0.4, 2, 48000, true, 1200, 0.25, 0.9, 72000, R.tribute(), 2.0, 0.4, 512);
        assertEquals(Siege.Outcome.WON, SiegeMath.battle(10, 12, 2, 10, false, early), "break/rout lines still work when a server sets them");
        assertEquals(Siege.Outcome.LOST, SiegeMath.battle(3, 12, 8, 10, false, early));
        assertEquals(Siege.Outcome.WON, SiegeMath.battle(9, 12, 6, 10, true, R));
        assertEquals(Siege.Outcome.LOST, SiegeMath.battle(6, 12, 6, 10, true, R));
        assertEquals(Siege.Outcome.WON, SiegeMath.battle(5, 12, 0, 0, false, R));
    }

    @Test
    void marchTimeScalesWithDistanceWithinBounds() {
        assertEquals(R.minMarch(), SiegeMath.marchTicks(20, R));
        assertEquals(6000, SiegeMath.marchTicks(500, R));
        assertEquals(R.maxMarch(), SiegeMath.marchTicks(100_000, R));
    }

    @Test
    void tributeLevyAndHelperPay() {
        assertEquals(12288, R.tribute(PoliticsTables.MilitaryTierKey.GARRISON));
        assertEquals(6.0, SiegeMath.levy(12288, R), 1e-9);
        assertEquals(2457, SiegeMath.helperPay(12288, 2, R));
        assertEquals(0, SiegeMath.helperPay(12288, 0, R));
    }

    static SiegeMath.Facts facts(boolean war, boolean camp, Standing s, boolean besieging, boolean besieged, int host, long last, int points) {
        return new SiegeMath.Facts(war, camp, s, besieging, besieged, host, 100_000, last, points);
    }

    @Test
    void counselRefusalsInOrder() {
        assertEquals(SiegeMath.Refusal.OK, SiegeMath.check(facts(true, true, Standing.PATRON, false, false, 10, -1, 5), R));
        assertEquals(SiegeMath.Refusal.NOT_AT_WAR, SiegeMath.check(facts(false, true, Standing.PATRON, false, false, 10, -1, 5), R));
        assertEquals(SiegeMath.Refusal.NOT_ON_CAMPAIGN, SiegeMath.check(facts(true, false, Standing.PATRON, false, false, 10, -1, 5), R));
        assertEquals(SiegeMath.Refusal.STANDING_TOO_LOW, SiegeMath.check(facts(true, true, Standing.TRUSTED, false, false, 10, -1, 5), R));
        assertEquals(SiegeMath.Refusal.ALREADY_BESIEGING, SiegeMath.check(facts(true, true, Standing.SWORN, true, false, 10, -1, 5), R));
        assertEquals(SiegeMath.Refusal.TARGET_BESIEGED, SiegeMath.check(facts(true, true, Standing.SWORN, false, true, 10, -1, 5), R));
        assertEquals(SiegeMath.Refusal.HOST_TOO_SMALL, SiegeMath.check(facts(true, true, Standing.SWORN, false, false, 5, -1, 5), R));
        assertEquals(SiegeMath.Refusal.COOLDOWN, SiegeMath.check(facts(true, true, Standing.SWORN, false, false, 10, 100_000 - 1000, 5), R));
        assertEquals(SiegeMath.Refusal.NO_DIPLOMACY_POINT, SiegeMath.check(facts(true, true, Standing.SWORN, false, false, 10, -1, 1), R));
    }

    @Test
    void counselChanceByStandingCutWhenTooStrong() {
        assertEquals(0.5, SiegeMath.counselChance(Standing.PATRON, 100, 100, R), 1e-9);
        assertEquals(0.75 * 0.4, SiegeMath.counselChance(Standing.SWORN, 100, 151, R), 1e-9);
        assertEquals(0, SiegeMath.counselChance(Standing.TRUSTED, 100, 10, R));
    }

    @Test
    void villagesLaunchOnlyWhenStrongEnoughAtTheDailyRate() {
        assertEquals(0, SiegeMath.aiCheckChance(80, 100, R));
        double c = SiegeMath.aiCheckChance(100, 100, R);
        assertTrue(c > 0 && c < 0.25);
        assertEquals(0.25, 1 - Math.pow(1 - c, 24000.0 / R.aiInterval()), 1e-9);
    }
}

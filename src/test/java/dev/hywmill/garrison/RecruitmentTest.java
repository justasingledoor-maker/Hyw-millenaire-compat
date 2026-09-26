package dev.hywmill.garrison;

import dev.hywmill.garrison.tables.GarrisonTable;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecruitmentTest {
    static final UUID VILLAGE = GarrisonLifecycleTest.VILLAGE;
    static GarrisonTables tables;
    static GarrisonTable norman;
    static GarrisonTable dflt;

    @BeforeAll
    static void load() throws IOException {
        tables = GarrisonTablesTest.shipped(new ArrayList<>());
        norman = tables.forCulture("millenaire:norman");
        dflt = tables.defaults();
    }

    // ---- target ----

    @Test
    void targetScalesWithCapacityWithinTierBounds() {
        assertEquals(6, Recruitment.target(6, MilitaryTier.GARRISON, false, dflt));
        assertEquals(3, Recruitment.target(1, MilitaryTier.GARRISON, false, dflt)); // minTarget
        assertEquals(1, Recruitment.target(0, MilitaryTier.WATCH, false, dflt));
        assertEquals(2, Recruitment.target(2, MilitaryTier.GUARD_POST, false, dflt));
    }

    @Test
    void tierMaxIsACeilingNotAnAllocation() {
        assertEquals(8, Recruitment.target(500, MilitaryTier.WATCH, false, dflt));
        assertEquals(16, Recruitment.target(500, MilitaryTier.GUARD_POST, false, dflt));
        assertEquals(32, Recruitment.target(500, MilitaryTier.GARRISON, false, dflt));
        assertEquals(64, Recruitment.target(500, MilitaryTier.STRONGHOLD, false, dflt));
        assertEquals(5, Recruitment.target(5, MilitaryTier.STRONGHOLD, false, dflt)); // a small stronghold stays small
        assertEquals(40, Recruitment.target(40, MilitaryTier.STRONGHOLD, false, dflt));
    }

    @Test
    void noneTierAndLoneBuildingsHaveNoGarrison() {
        assertEquals(0, Recruitment.target(30, MilitaryTier.NONE, false, dflt));
        assertEquals(0, Recruitment.target(30, MilitaryTier.STRONGHOLD, true, dflt));
    }

    // ---- levy ----

    @Test
    void accrualIsPerActiveDayAndCapped() {
        GarrisonRoster r = new GarrisonRoster(0);
        double rate = Recruitment.dailyRate(8, MilitaryTier.GARRISON, dflt); // 2.0 + 0.25 * 8
        assertEquals(4.0, rate, 1e-9);
        for (long t = 200; t <= 24000; t += 200) {
            Recruitment.accrue(r, t, 400, rate, 10);
        }
        assertEquals(4.0, r.levyPoints, 1e-6);
        for (long t = 24200; t <= 24000 * 5; t += 200) {
            Recruitment.accrue(r, t, 400, rate, 10);
        }
        assertEquals(10.0, r.levyPoints, 1e-9);
    }

    @Test
    void noOfflineCatchUp() {
        GarrisonRoster r = new GarrisonRoster(0);
        Recruitment.accrue(r, 24000 * 10, 400, 4.0, 100); // ten days unloaded: only one step counts
        assertEquals(4.0 * 400 / 24000, r.levyPoints, 1e-9);
        assertEquals(24000 * 10, r.lastAccrualTick);
    }

    @Test
    void lowerCapClampsExistingPoints() {
        GarrisonRoster r = new GarrisonRoster(0);
        r.levyPoints = 50;
        Recruitment.accrue(r, 200, 400, 1, 6);
        assertEquals(6, r.levyPoints, 1e-9);
    }

    // ---- unit choice ----

    @Test
    void watchRecruitsOnlyLevyAndRanged() {
        List<UnitSpec> u = Recruitment.eligibleUnits(MilitaryTier.WATCH, norman, tables.units());
        assertFalse(u.isEmpty());
        for (UnitSpec s : u) {
            assertTrue(s.unitClass() == UnitClass.LEVY || s.unitClass() == UnitClass.RANGED, s.key());
        }
        assertTrue(u.stream().noneMatch(s -> s.key().equals("shieldman")));
    }

    @Test
    void shieldmenNeedGarrisonTier() {
        assertTrue(Recruitment.eligibleUnits(MilitaryTier.GUARD_POST, norman, tables.units()).stream().noneMatch(s -> s.key().equals("shieldman")));
        assertTrue(Recruitment.eligibleUnits(MilitaryTier.GUARD_POST, norman, tables.units()).stream().anyMatch(s -> s.key().equals("spear_man")));
        assertTrue(Recruitment.eligibleUnits(MilitaryTier.GARRISON, norman, tables.units()).stream().anyMatch(s -> s.key().equals("shieldman")));
        assertTrue(Recruitment.eligibleUnits(MilitaryTier.NONE, norman, tables.units()).isEmpty());
    }

    @Test
    void allowedAtTierAppliesClassMinTierAndEnabled() {
        UnitSpec shield = tables.units().get("shieldman");
        UnitSpec spear = tables.units().get("spear_man");
        UnitSpec archer = tables.units().get("archer");
        UnitSpec gun = tables.units().get("matchlock_man");
        assertFalse(Recruitment.allowedAtTier(shield, MilitaryTier.WATCH, norman));
        assertFalse(Recruitment.allowedAtTier(shield, MilitaryTier.GUARD_POST, norman));
        assertTrue(Recruitment.allowedAtTier(shield, MilitaryTier.GARRISON, norman));
        assertFalse(Recruitment.allowedAtTier(spear, MilitaryTier.WATCH, norman)); // LINE needs GUARD_POST+
        assertTrue(Recruitment.allowedAtTier(archer, MilitaryTier.WATCH, norman));
        assertFalse(Recruitment.allowedAtTier(archer, MilitaryTier.NONE, norman));
        assertFalse(Recruitment.allowedAtTier(gun, MilitaryTier.STRONGHOLD, norman)); // disabled by default
    }

    @Test
    void gunpowderNeverChosenByDefault() {
        GarrisonTable withGuns = new GarrisonTable(dflt.tiers(), 0, 0.5, 2, Map.of("handgonne_man", 10, "matchlock_man", 10, "archer", 1), "hyw");
        List<UnitSpec> u = Recruitment.eligibleUnits(MilitaryTier.STRONGHOLD, withGuns, tables.units());
        assertEquals(List.of("archer"), u.stream().map(UnitSpec::key).toList());
    }

    @Test
    void choiceIsDeterministic() {
        List<UnitSpec> u = Recruitment.eligibleUnits(MilitaryTier.GARRISON, norman, tables.units());
        for (int seq = 0; seq < 50; seq++) {
            assertEquals(Recruitment.chooseUnit(VILLAGE, seq, u, norman.composition(), Map.of()),
                    Recruitment.chooseUnit(VILLAGE, seq, u, norman.composition(), Map.of()));
        }
    }

    @Test
    void compositionConvergesToWeights() {
        List<UnitSpec> u = Recruitment.eligibleUnits(MilitaryTier.GARRISON, norman, tables.units());
        Map<String, Integer> counts = new HashMap<>();
        for (int seq = 0; seq < 100; seq++) {
            UnitSpec s = Recruitment.chooseUnit(VILLAGE, seq, u, norman.composition(), counts);
            counts.merge(s.key(), 1, Integer::sum);
        }
        // weights 1:3:2:2:1 (+1 light rider, M4) over 10 -> 10:30:20:20:10:10 of 100
        assertEquals(Map.of("militia", 10, "spear_man", 30, "shieldman", 20, "crossbowman", 20, "archer", 10, "light_lancer_rider", 10), counts);
    }

    @Test
    void underrepresentedClassIsPreferred() {
        List<UnitSpec> u = Recruitment.eligibleUnits(MilitaryTier.GARRISON, dflt, tables.units());
        UnitSpec next = Recruitment.chooseUnit(VILLAGE, 7, u, dflt.composition(), Map.of("spear_man", 5, "militia", 2));
        assertEquals("archer", next.key());
    }

    // ---- starting grant ----

    @Test
    void startingGrantIsHalfTheTargetRoundedUpAndOnce() {
        assertEquals(3, Recruitment.startingGrant(5, 0.5));
        assertEquals(1, Recruitment.startingGrant(1, 0.5));
        assertEquals(32, Recruitment.startingGrant(64, 0.5));
        assertEquals(0, Recruitment.startingGrant(0, 0.5));
        GarrisonRoster r = new GarrisonRoster(0);
        List<RosterEntry> g = Recruitment.grantStarting(r, VILLAGE, 7, MilitaryTier.GARRISON, norman, tables.units(), 2, 10);
        assertEquals(4, g.size());
        assertTrue(r.startingGranted);
        assertTrue(g.stream().noneMatch(e -> e.paid));
        assertEquals(0, r.levyPoints);
        assertTrue(Recruitment.grantStarting(r, VILLAGE, 20, MilitaryTier.STRONGHOLD, norman, tables.units(), 3, 20).isEmpty());
        assertEquals(4, r.live());
    }

    @Test
    void noGrantWithoutTarget() {
        GarrisonRoster r = new GarrisonRoster(0);
        assertTrue(Recruitment.grantStarting(r, VILLAGE, 0, MilitaryTier.NONE, norman, tables.units(), 0, 10).isEmpty());
        assertFalse(r.startingGranted);
    }

    // ---- gating ----

    @Test
    void blockersInOrder() {
        UnitSpec archer = tables.units().get("archer");
        GarrisonRoster r = new GarrisonRoster(0);
        r.levyPoints = 5;
        assertEquals(Recruitment.Blocker.DISABLED, Recruitment.blocker(r, false, true, 4, 8, 10000, 2400, archer));
        r.paused = true;
        assertEquals(Recruitment.Blocker.PAUSED, Recruitment.blocker(r, true, true, 4, 8, 10000, 2400, archer));
        r.paused = false;
        assertEquals(Recruitment.Blocker.NOT_CALM, Recruitment.blocker(r, true, false, 4, 8, 10000, 2400, archer));
        assertEquals(Recruitment.Blocker.TARGET_REACHED, Recruitment.blocker(r, true, true, 0, 8, 10000, 2400, archer));
        assertEquals(Recruitment.Blocker.NONE, Recruitment.blocker(r, true, true, 4, 8, 10000, 2400, archer));
        Recruitment.recruitPaid(r, VILLAGE, archer, 0, 10000);
        assertEquals(3, r.levyPoints, 1e-9);
        assertEquals(Recruitment.Blocker.INTERVAL, Recruitment.blocker(r, true, true, 4, 8, 12399, 2400, archer));
        r.levyPoints = 1;
        assertEquals(Recruitment.Blocker.POINTS, Recruitment.blocker(r, true, true, 4, 8, 12400, 2400, archer));
        assertEquals(Recruitment.Blocker.NO_UNIT, Recruitment.blocker(r, true, true, 4, 8, 12400, 2400, null));
        assertEquals(Recruitment.Blocker.TIER_CAP, Recruitment.blocker(r, true, true, 4, 1, 12400, 2400, archer));
    }

    @Test
    void deathAndWipeoutCooldowns() {
        GarrisonRoster r = new GarrisonRoster(0);
        r.lastRecruitTick = 0;
        Recruitment.cooldown(r, 5000, 1200, 2400);
        assertEquals(5000 + 1200, r.lastRecruitTick + 2400);
        Recruitment.cooldown(r, 5000, 24000, 2400);
        assertEquals(5000 + 24000, r.lastRecruitTick + 2400);
        Recruitment.cooldown(r, 5100, 1200, 2400); // a shorter later cooldown never shortens a longer one
        assertEquals(5000 + 24000, r.lastRecruitTick + 2400);
        assertTrue(Recruitment.wipedOut(3, 4));
        assertFalse(Recruitment.wipedOut(2, 4));
        assertTrue(Recruitment.wipedOut(48, 64));
        assertFalse(Recruitment.wipedOut(5, 0));
    }
}

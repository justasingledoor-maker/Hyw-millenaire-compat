package dev.hywmill.garrison;

import dev.hywmill.garrison.tables.GarrisonTable;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.classify.BuildingRole;
import dev.hywmill.garrison.tables.TierRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Set;
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
    /** The M3 rules as frozen (hywmill_garrison/defaults.json before M5-G), every M5-G term neutral. */
    static GarrisonTable m3;

    @BeforeAll
    static void load() throws IOException {
        tables = GarrisonTablesTest.shipped(new ArrayList<>());
        norman = tables.forCulture("millenaire:norman");
        dflt = tables.defaults();
        Map<MilitaryTier, TierRule> t = new EnumMap<>(MilitaryTier.class);
        t.put(MilitaryTier.NONE, TierRule.NONE);
        t.put(MilitaryTier.WATCH, new TierRule(1.0, 1, 8, 8, 1.0, 4, 0, Set.of(UnitClass.LEVY, UnitClass.RANGED)));
        t.put(MilitaryTier.GUARD_POST, new TierRule(1.0, 2, 16, 16, 1.5, 6, 1, Set.of(UnitClass.LEVY, UnitClass.LINE, UnitClass.RANGED)));
        t.put(MilitaryTier.GARRISON, new TierRule(1.0, 3, 32, 32, 2.0, 10, 2, GarrisonTables.ALL_CLASSES));
        t.put(MilitaryTier.STRONGHOLD, new TierRule(1.0, 4, 64, 64, 3.0, 16, 3, GarrisonTables.ALL_CLASSES));
        m3 = new GarrisonTable(t, 0.25, 0.5, 2, dflt.composition(), "hyw_profiles");
    }

    // ---- target ----

    @Test
    void m3TargetScalesWithCapacityWithinTierBounds() {
        assertEquals(6, Recruitment.target(6, MilitaryTier.GARRISON, false, m3));
        assertEquals(3, Recruitment.target(1, MilitaryTier.GARRISON, false, m3)); // minTarget
        assertEquals(1, Recruitment.target(0, MilitaryTier.WATCH, false, m3));
        assertEquals(2, Recruitment.target(2, MilitaryTier.GUARD_POST, false, m3));
    }

    @Test
    void m3TierMaxIsACeilingNotAnAllocation() {
        assertEquals(8, Recruitment.target(500, MilitaryTier.WATCH, false, m3));
        assertEquals(16, Recruitment.target(500, MilitaryTier.GUARD_POST, false, m3));
        assertEquals(32, Recruitment.target(500, MilitaryTier.GARRISON, false, m3));
        assertEquals(64, Recruitment.target(500, MilitaryTier.STRONGHOLD, false, m3));
        assertEquals(5, Recruitment.target(5, MilitaryTier.STRONGHOLD, false, m3)); // a small stronghold stays small
        assertEquals(40, Recruitment.target(40, MilitaryTier.STRONGHOLD, false, m3));
    }

    @Test
    void noneTierAndLoneBuildingsHaveNoGarrison() {
        for (GarrisonTable t : List.of(m3, dflt)) {
            assertEquals(0, Recruitment.target(30, MilitaryTier.NONE, false, t));
            assertEquals(0, Recruitment.target(30, MilitaryTier.STRONGHOLD, true, t));
            assertEquals(0, Recruitment.target(new Recruitment.TargetInputs(30, 50, 50, Map.of(BuildingRole.BARRACKS, 3), 90, "x"),
                    MilitaryTier.STRONGHOLD, true, t));
        }
    }

    // ---- M5-G ----

    /** With every M5-G term neutral the new formula is exactly M3's, whatever the other inputs are. */
    @Test
    void neutralM5TermsReproduceM3Exactly() {
        Map<BuildingRole, Integer> b = Map.of(BuildingRole.BARRACKS, 2, BuildingRole.TOWER, 5, BuildingRole.WALL, 30);
        for (MilitaryTier tier : MilitaryTier.values()) {
            for (int cap = 0; cap <= 80; cap++) {
                int expected = Recruitment.target(cap, tier, false, m3);
                assertEquals(expected, Recruitment.target(new Recruitment.TargetInputs(cap, 40, 55, b, 90, "militaire"), tier, false, m3),
                        tier + " capacity " + cap);
                assertEquals(Recruitment.dailyRate(cap, tier, m3), Recruitment.dailyRate(cap, expected, tier, m3), 1e-12);
                assertEquals(m3.tier(tier).poolCap(), Recruitment.poolCap(expected, tier, m3), 1e-12);
            }
        }
    }

    static Recruitment.TargetInputs in(int capacity, int adults, int fort, Map<BuildingRole, Integer> b) {
        return new Recruitment.TargetInputs(capacity, adults, adults, b, fort, "");
    }

    /** C3 on the inputs of the six real harness villages (docs/m5-test-evidence/sg-model-real-inputs.txt). */
    @Test
    void c3OnTheRealHarnessVillages() {
        Map<BuildingRole, Integer> militaire = Map.of(BuildingRole.WALL, 26, BuildingRole.TOWER, 4, BuildingRole.BORDER_MARKER, 7,
                BuildingRole.GUARDHOUSE, 2, BuildingRole.WATCHTOWER, 2, BuildingRole.ARMOURY, 1, BuildingRole.FORT_TOWNHALL, 1);
        Map<BuildingRole, Integer> barbery = Map.of(BuildingRole.WALL, 49, BuildingRole.TOWER, 8, BuildingRole.BORDER_MARKER, 14,
                BuildingRole.GUARDHOUSE, 2, BuildingRole.WATCHTOWER, 2, BuildingRole.ARMOURY, 1, BuildingRole.FORT_TOWNHALL, 1);
        assertEquals(90, Recruitment.target(in(16, 36, 3, Map.of(BuildingRole.BORDER_MARKER, 16, BuildingRole.GUARDHOUSE, 1)),
                MilitaryTier.GUARD_POST, false, dflt)); // Sainte-Marguerite
        assertEquals(29, Recruitment.target(in(6, 15, 0, Map.of(BuildingRole.BORDER_MARKER, 13)), MilitaryTier.WATCH, false, dflt)); // Isigny
        assertEquals(90, Recruitment.target(in(11, 22, 55, militaire), MilitaryTier.STRONGHOLD, false, norman)); // Crèvecoeur
        assertEquals(96, Recruitment.target(in(14, 32, 9, Map.of(BuildingRole.BORDER_MARKER, 9, BuildingRole.BARRACKS, 1,
                BuildingRole.ARMOURY, 1, BuildingRole.FORT_TOWNHALL, 1)), MilitaryTier.GUARD_POST, false, tables.forCulture("millenaire:byzantines"))); // Phaistos
        assertEquals(48, Recruitment.target(in(8, 16, 44, Map.of(BuildingRole.WALL, 29, BuildingRole.TOWER, 5, BuildingRole.BORDER_MARKER, 3)),
                MilitaryTier.WATCH, false, norman)); // Grainville
        assertEquals(110, Recruitment.target(in(11, 22, 90, barbery), MilitaryTier.STRONGHOLD, false, norman)); // Barbery
    }

    @Test
    void c3NeverExceedsTheLockedCaps() {
        Map<BuildingRole, Integer> huge = Map.of(BuildingRole.BARRACKS, 10, BuildingRole.FORT_TOWNHALL, 3, BuildingRole.TOWER, 40);
        assertEquals(48, Recruitment.target(in(500, 500, 1000, huge), MilitaryTier.WATCH, false, dflt));
        assertEquals(96, Recruitment.target(in(500, 500, 1000, huge), MilitaryTier.GUARD_POST, false, dflt));
        assertEquals(115, Recruitment.target(in(500, 500, 1000, huge), MilitaryTier.GARRISON, false, dflt));
        assertEquals(128, Recruitment.target(in(500, 500, 1000, huge), MilitaryTier.STRONGHOLD, false, dflt));
        // fortification adds at most fortCap (30): 4 slots * 3 + 30
        assertEquals(42, Recruitment.target(in(4, 0, 100000, Map.of()), MilitaryTier.STRONGHOLD, false, dflt));
        // an empty village of a tier still gets the tier's floor
        assertEquals(4, Recruitment.target(in(0, 0, 0, Map.of()), MilitaryTier.STRONGHOLD, false, dflt));
    }

    @Test
    void populationIsNoCeilingUnlessSupportRatioIsSet() {
        Map<MilitaryTier, TierRule> t = new EnumMap<>(dflt.tiers());
        TierRule s = dflt.tier(MilitaryTier.STRONGHOLD);
        t.put(MilitaryTier.STRONGHOLD, new TierRule(s.perCapacity(), s.minTarget(), s.maxTarget(), s.maxUnits(), s.baseDaily(), s.poolCap(),
                s.equipmentLevel(), s.classes(), s.levyShare(), s.fortDiv(), s.fortCap(), s.perTargetDaily(), s.poolCapShare(), 2.0));
        GarrisonTable capped = new GarrisonTable(t, dflt.perCapacityDaily(), dflt.startingFraction(), dflt.commitPerThreat(), dflt.composition(),
                dflt.equipmentProvider(), dflt.infraBonus(), dflt.typeFactors());
        Recruitment.TargetInputs small = new Recruitment.TargetInputs(20, 10, 10, Map.of(BuildingRole.BARRACKS, 1), 30, "");
        int free = Recruitment.target(small, MilitaryTier.STRONGHOLD, false, dflt);
        assertTrue(free > 20, "no ceiling by default: " + free);
        assertEquals(20, Recruitment.target(small, MilitaryTier.STRONGHOLD, false, capped));
    }

    @Test
    void typeFactorScalesTheRawTarget() {
        GarrisonTable t = new GarrisonTable(dflt.tiers(), dflt.perCapacityDaily(), dflt.startingFraction(), dflt.commitPerThreat(),
                dflt.composition(), dflt.equipmentProvider(), dflt.infraBonus(), Map.of("militaire", 1.5));
        Recruitment.TargetInputs a = new Recruitment.TargetInputs(10, 0, 0, Map.of(), 0, "militaire");
        Recruitment.TargetInputs b = new Recruitment.TargetInputs(10, 0, 0, Map.of(), 0, "agricole");
        assertEquals(45, Recruitment.target(a, MilitaryTier.STRONGHOLD, false, t));
        assertEquals(30, Recruitment.target(b, MilitaryTier.STRONGHOLD, false, t));
    }

    @Test
    void levyScalesWithTheTarget() {
        // STRONGHOLD, capacity 11, target 110: 3.0 + 0.25 * 11 + 0.12 * 110
        assertEquals(3.0 + 2.75 + 13.2, Recruitment.dailyRate(11, 110, MilitaryTier.STRONGHOLD, dflt), 1e-9);
        assertEquals(27.5, Recruitment.poolCap(110, MilitaryTier.STRONGHOLD, dflt), 1e-9);
        assertEquals(16.0, Recruitment.poolCap(10, MilitaryTier.STRONGHOLD, dflt), 1e-9); // never below the M3 pool cap
    }

    // ---- scaling gate ----

    @Test
    void gateWaitsForARefreshAndStableInputsAfterActivation() {
        ScalingGate g = new ScalingGate();
        long settle = 400;
        // activated at 1000; record last refreshed before the activation (last session's state)
        assertFalse(g.observe(7, 1000, 1000, 900, true, settle));
        assertFalse(g.observe(7, 1400, 1000, 900, true, settle), "no refresh since activation");
        assertTrue(g.observe(7, 1600, 1000, 1200, true, settle));
        // an input changes (residents finished loading): wait again
        assertFalse(g.observe(8, 1800, 1000, 1800, true, settle));
        assertFalse(g.observe(8, 2000, 1000, 2000, true, settle));
        assertTrue(g.observe(8, 2200, 1000, 2200, true, settle));
        // a record awaiting its migration recompute is never authoritative
        assertFalse(g.observe(8, 2400, 1000, 2400, false, settle));
    }

    @Test
    void gateRestartsOnReactivation() {
        ScalingGate g = new ScalingGate();
        assertFalse(g.observe(5, 1000, 1000, 1000, true, 400));
        assertTrue(g.observe(5, 1400, 1000, 1200, true, 400));
        // unloaded, then active again at 50000 with the same inputs: they must settle again
        assertFalse(g.observe(5, 50000, 50000, 50000, true, 400));
        assertTrue(g.observe(5, 50400, 50000, 50200, true, 400));
    }

    @Test
    void gatedTargetNeverGrowsNorTrims() {
        assertEquals(128, ScalingGate.gated(128, true, 20));
        assertEquals(20, ScalingGate.gated(128, false, 20)); // no growth from an incomplete load
        assertEquals(48, ScalingGate.gated(48, false, 128)); // a transient lower tier: no trimming either (live stays)
        assertEquals(0, ScalingGate.gated(90, false, 0)); // nothing granted before the state is authoritative
    }

    // ---- levy ----

    @Test
    void accrualIsPerActiveDayAndCapped() {
        GarrisonRoster r = new GarrisonRoster(0);
        double rate = Recruitment.dailyRate(8, MilitaryTier.GARRISON, m3); // 2.0 + 0.25 * 8
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
    void watchRecruitsLevySpearmenAndRanged() {
        // post-M5: militia are rare, so a Watch may field spearmen (LINE from WATCH, spear_man minTier WATCH)
        List<UnitSpec> u = Recruitment.eligibleUnits(MilitaryTier.WATCH, norman, tables.units());
        assertFalse(u.isEmpty());
        for (UnitSpec s : u) {
            assertTrue(s.unitClass() == UnitClass.LEVY || s.unitClass() == UnitClass.RANGED || s.key().equals("spear_man"), s.key());
        }
        assertTrue(u.stream().anyMatch(s -> s.key().equals("spear_man")));
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
        assertTrue(Recruitment.allowedAtTier(spear, MilitaryTier.WATCH, norman)); // post-M5: spearmen from WATCH
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
        // post-M5 weights 3:2:2:1:2 (no militia; more light horse, for scouting) over 10 -> 100 soldiers split 30:20:20:10:20
        assertEquals(Map.of("spear_man", 30, "shieldman", 20, "crossbowman", 20, "archer", 10, "light_lancer_rider", 20), counts);
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

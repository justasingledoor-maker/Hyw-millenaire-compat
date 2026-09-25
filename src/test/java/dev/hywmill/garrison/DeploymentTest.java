package dev.hywmill.garrison;

import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.ThreatTracker.Reason;
import dev.hywmill.military.defense.AlertState;
import dev.hywmill.military.defense.DefenseCoordinator;
import dev.hywmill.military.doctrine.Doctrine;
import dev.hywmill.military.doctrine.DoctrineDefaults;
import dev.hywmill.military.doctrine.DoctrineField;
import dev.hywmill.military.doctrine.DoctrinePatch;
import dev.hywmill.military.doctrine.DoctrineResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentTest {
    static final UUID VILLAGE = GarrisonLifecycleTest.VILLAGE;
    static final DefenseCoordinator.Pos ANCHOR = new DefenseCoordinator.Pos(0, 64, 0);

    static Doctrine doctrine(DoctrinePatch p) {
        return DoctrineResolver.resolve(DoctrineDefaults.BUILTIN,
                new DoctrineResolver.Context("millenaire:norman", "millenaire:norman/agricole", 90, false, MilitaryTier.GUARD_POST), p).doctrine();
    }

    final GarrisonRoster roster = new GarrisonRoster(0);
    final List<Deployment.UnitView> units = new ArrayList<>();

    RosterEntry unit(double x, double z) {
        RosterEntry e = roster.recruit(VILLAGE, "spear_man", "hundred_years_war:spear_man", 1, 0, true);
        roster.beginSpawn(VILLAGE, e, 0);
        e.transition(UnitState.GARRISONED, 0);
        units.add(new Deployment.UnitView(e, new DefenseCoordinator.Pos(x, 64, z)));
        return e;
    }

    static DefenseCoordinator.ThreatView threat(int n, double x, double z, Reason... r) {
        EnumSet<Reason> s = EnumSet.noneOf(Reason.class);
        java.util.Collections.addAll(s, r);
        return new DefenseCoordinator.ThreatView(new UUID(9, n), new DefenseCoordinator.Pos(x, 64, z), s);
    }

    DefenseCoordinator.Result plan(Doctrine d, int commit, AlertState st, DefenseCoordinator.ThreatView... t) {
        return Deployment.plan(d, commit, st, units, List.of(t), ANCHOR, Map.of(), Set.of());
    }

    @Test
    void garrisonCommitPerThreatReplacesOnlyThatField() {
        Doctrine d = doctrine(DoctrinePatch.EMPTY);
        Doctrine g = Deployment.forGarrison(d, 5);
        assertEquals(5, g.commitPerThreat());
        assertEquals(d.reserve(), g.reserve());
        assertEquals(d.proactive(), g.proactive());
        assertEquals(d.defenseRadius(), g.defenseRadius());
    }

    @Test
    void nearestUnitsCommittedUpToCommitPerThreat() {
        Doctrine d = doctrine(DoctrinePatch.EMPTY.with(DoctrineField.RESERVE, 0));
        RosterEntry a = unit(10, 0), b = unit(12, 0), c = unit(40, 0);
        DefenseCoordinator.Result r = plan(d, 2, AlertState.ENGAGED, threat(1, 11, 0, Reason.RECENT_ATTACKER));
        assertEquals(Set.of(a.entityUuid, b.entityUuid), r.assignments().keySet());
        assertFalse(r.assignments().containsKey(c.entityUuid));
    }

    @Test
    void presenceAloneIsNotEngagedWhenNotProactive() {
        Doctrine d = doctrine(DoctrinePatch.EMPTY);
        assertFalse(d.proactive());
        unit(10, 0);
        assertTrue(plan(d, 2, AlertState.ALERT, threat(1, 11, 0, Reason.HYW_ENEMY)).assignments().isEmpty());
        assertEquals(1, plan(d, 2, AlertState.ALERT, threat(1, 11, 0, Reason.HYW_ENEMY, Reason.ATTACKING_RESIDENT)).assignments().size());
    }

    @Test
    void calmAssignsNothing() {
        unit(10, 0);
        assertTrue(plan(doctrine(DoctrinePatch.EMPTY), 2, AlertState.CALM, threat(1, 11, 0, Reason.RECENT_ATTACKER)).assignments().isEmpty());
    }

    @Test
    void reserveIsHeldNearTheAnchor() {
        Doctrine d = doctrine(DoctrinePatch.EMPTY.with(DoctrineField.RESERVE, 1));
        RosterEntry home = unit(1, 0);
        unit(20, 0);
        unit(21, 0);
        unit(22, 0);
        DefenseCoordinator.Result r = plan(d, 2, AlertState.ENGAGED, threat(1, 30, 0, Reason.RECENT_ATTACKER));
        assertEquals(Set.of(home.entityUuid), r.reserve());
        assertFalse(r.assignments().containsKey(home.entityUuid));
        assertEquals(2, r.assignments().size());
    }

    @Test
    void tiesBrokenByUuidDeterministically() {
        Doctrine d = doctrine(DoctrinePatch.EMPTY.with(DoctrineField.RESERVE, 0));
        unit(10, 0);
        unit(10, 0);
        unit(10, 0);
        DefenseCoordinator.Result r1 = plan(d, 1, AlertState.ENGAGED, threat(1, 0, 0, Reason.RECENT_ATTACKER));
        DefenseCoordinator.Result r2 = plan(d, 1, AlertState.ENGAGED, threat(1, 0, 0, Reason.RECENT_ATTACKER));
        assertEquals(r1.assignments(), r2.assignments());
        UUID min = units.stream().map(u -> u.entry().entityUuid).min(UUID::compareTo).orElseThrow();
        assertEquals(Set.of(min), r1.assignments().keySet());
    }

    @Test
    void applyDeploysReturnsAndRegarrisons() {
        RosterEntry a = unit(10, 0);
        UUID t = new UUID(9, 1);
        List<Deployment.Action> act = Deployment.apply(units, Map.of(a.entityUuid, t), ANCHOR, 100, 1200);
        assertEquals(UnitState.DEPLOYED, a.state());
        assertEquals(Deployment.ActionKind.ENGAGE, act.get(0).kind());
        act = Deployment.apply(units, Map.of(), ANCHOR, 200, 1200);
        assertEquals(UnitState.RETURNING, a.state());
        assertEquals(Deployment.ActionKind.DISENGAGE, act.get(0).kind());
        Deployment.apply(units, Map.of(), ANCHOR, 300, 1200); // 10 blocks from the anchor: still returning
        assertEquals(UnitState.RETURNING, a.state());
        units.set(0, new Deployment.UnitView(a, new DefenseCoordinator.Pos(3, 64, 3)));
        Deployment.apply(units, Map.of(), ANCHOR, 400, 1200);
        assertEquals(UnitState.GARRISONED, a.state());
    }

    @Test
    void returnTimesOut() {
        RosterEntry a = unit(50, 0);
        Deployment.apply(units, Map.of(a.entityUuid, new UUID(9, 1)), ANCHOR, 100, 1200);
        Deployment.apply(units, Map.of(), ANCHOR, 200, 1200);
        Deployment.apply(units, Map.of(), ANCHOR, 200 + 1200, 1200);
        assertEquals(UnitState.GARRISONED, a.state());
    }

    @Test
    void nonDeployableStatesAreIgnored() {
        RosterEntry a = unit(10, 0);
        a.transition(UnitState.MISSING, 5);
        assertTrue(plan(doctrine(DoctrinePatch.EMPTY.with(DoctrineField.RESERVE, 0)), 2, AlertState.ENGAGED,
                threat(1, 11, 0, Reason.RECENT_ATTACKER)).assignments().isEmpty());
    }
}

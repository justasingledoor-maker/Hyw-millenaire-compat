package dev.hywmill.military.defense;

import dev.hywmill.military.ThreatTracker.Reason;
import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.military.doctrine.Doctrine;
import dev.hywmill.military.doctrine.DoctrineField;
import dev.hywmill.military.doctrine.MilitiaPolicy;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.hywmill.military.defense.DefenseTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2-5 commit/reserve, M2-6 militia policy, and the proactive=false contract. */
class DefenseCoordinatorTest {
    static final DefenseCoordinator.Pos HOME = at(0, 0);

    static DefenseCoordinator.Result run(Doctrine d, AlertState s, List<DefenseCoordinator.DefenderView> defs,
                                         List<DefenseCoordinator.ThreatView> threats) {
        return run(d, s, defs, threats, Map.of(), Set.of());
    }

    static DefenseCoordinator.Result run(Doctrine d, AlertState s, List<DefenseCoordinator.DefenderView> defs,
                                         List<DefenseCoordinator.ThreatView> threats, Map<java.util.UUID, java.util.UUID> prev,
                                         Set<java.util.UUID> prevReserve) {
        return DefenseCoordinator.assign(new DefenseCoordinator.Input(d, s, defs, threats, HOME, prev, prevReserve));
    }

    static List<DefenseCoordinator.DefenderView> fiveSoldiers() {
        return List.of(def(1, VillagerRole.SOLDIER, 1, 0), def(2, VillagerRole.SOLDIER, 10, 0),
                def(3, VillagerRole.SOLDIER, 20, 0), def(4, VillagerRole.LEADER, 30, 0), def(5, VillagerRole.SOLDIER, 40, 0));
    }

    @Test
    void commitAndReserveExact() {
        // baseline: commit 3, reserve 1; 5 eligible >= 3 + 2 -> reserve applies
        DefenseCoordinator.Result r = run(baseline(), AlertState.ENGAGED, fiveSoldiers(),
                List.of(threat(1, 50, 0, Reason.RECENT_ATTACKER)));
        assertEquals(Set.of(id(1)), r.reserve(), "closest to the defending position");
        assertEquals(List.of(id(5), id(4), id(3)), r.byThreat().get(id(1001)), "3 closest to the threat");
        assertFalse(r.assignments().containsKey(id(1)), "the reserve is never committed");
        assertEquals(3, r.assignments().size());
    }

    @Test
    void reserveOnlyWithEnoughDefenders() {
        List<DefenseCoordinator.DefenderView> four = fiveSoldiers().subList(0, 4);
        DefenseCoordinator.Result r = run(baseline(), AlertState.ENGAGED, four, List.of(threat(1, 50, 0, Reason.RECENT_ATTACKER)));
        assertTrue(r.reserve().isEmpty(), "4 < commit 3 + 2");
        assertEquals(3, r.assignments().size());
    }

    @Test
    void noDoubleAssignmentAcrossThreats() {
        DefenseCoordinator.Result r = run(with(DoctrineField.RESERVE, 0), AlertState.ENGAGED, fiveSoldiers(),
                List.of(threat(1, 50, 0, Reason.RECENT_ATTACKER), threat(2, -50, 0, Reason.ATTACKING_RESIDENT)));
        assertEquals(5, r.assignments().size());
        assertEquals(5, new HashSet<>(r.assignments().keySet()).size());
        assertEquals(3, r.byThreat().get(id(1001)).size(), "first threat in order gets its full commit");
        assertEquals(2, r.byThreat().get(id(1002)).size(), "the rest go to the second threat");
    }

    @Test
    void previousAssignmentsAreKept() {
        Doctrine d = with(DoctrineField.RESERVE, 0);
        List<DefenseCoordinator.ThreatView> t = List.of(threat(1, 50, 0, Reason.RECENT_ATTACKER));
        DefenseCoordinator.Result r = run(d, AlertState.ENGAGED, fiveSoldiers(), t, Map.of(id(1), id(1001)), Set.of());
        assertTrue(r.byThreat().get(id(1001)).contains(id(1)), "the farthest defender keeps its target");
        assertEquals(3, r.byThreat().get(id(1001)).size());
    }

    @Test
    void deterministic() {
        List<DefenseCoordinator.ThreatView> t = List.of(threat(1, 50, 0, Reason.RECENT_ATTACKER), threat(2, 50, 0, Reason.RECENT_ATTACKER));
        assertEquals(run(baseline(), AlertState.ENGAGED, fiveSoldiers(), t),
                run(baseline(), AlertState.ENGAGED, fiveSoldiers().reversed(), t.reversed()));
    }

    @Test
    void proactiveFalseIgnoresPresenceOnlyThreats() {
        DefenseCoordinator.Result passive = run(baseline(), AlertState.ALERT, fiveSoldiers(), List.of(threat(1, 5, 0, Reason.HYW_ENEMY)));
        assertTrue(passive.assignments().isEmpty(), "presence is not aggression");
        DefenseCoordinator.Result proactive = run(with(DoctrineField.PROACTIVE, true), AlertState.ALERT, fiveSoldiers(),
                List.of(threat(1, 5, 0, Reason.HYW_ENEMY)));
        assertEquals(3, proactive.assignments().size());
    }

    @Test
    void calmAssignsNothing() {
        assertEquals(DefenseCoordinator.Result.EMPTY, run(baseline(), AlertState.CALM, fiveSoldiers(),
                List.of(threat(1, 5, 0, Reason.RECENT_ATTACKER))));
    }

    @Test
    void civiliansAndOutlawsNeverEligible() {
        List<DefenseCoordinator.DefenderView> d = List.of(def(1, VillagerRole.CIVILIAN, 1, 0), def(2, VillagerRole.OUTLAW, 1, 0),
                def(3, VillagerRole.MILITIA, 1, 0));
        DefenseCoordinator.Result r = run(with(DoctrineField.MILITIA_POLICY, MilitiaPolicy.ALWAYS), AlertState.ENGAGED, d,
                List.of(threat(1, 5, 0, Reason.RECENT_ATTACKER)));
        assertEquals(Set.of(id(3)), r.assignments().keySet());
    }

    // ---- M2-6 militia policy ----

    static Set<java.util.UUID> militiaCommitted(MilitiaPolicy p, AlertState s, Reason reason) {
        List<DefenseCoordinator.DefenderView> d = List.of(def(1, VillagerRole.SOLDIER, 1, 0), def(2, VillagerRole.MILITIA, 2, 0),
                def(3, VillagerRole.MILITIA, 3, 0));
        DefenseCoordinator.Result r = run(with(DoctrineField.MILITIA_POLICY, p), s, d, List.of(threat(1, 5, 0, reason)));
        Set<java.util.UUID> m = new HashSet<>(r.assignments().keySet());
        m.remove(id(1));
        return m;
    }

    @Test
    void militiaPolicies() {
        Set<java.util.UUID> both = Set.of(id(2), id(3));
        assertEquals(Set.of(), militiaCommitted(MilitiaPolicy.NEVER, AlertState.ENGAGED, Reason.RECENT_ATTACKER));

        assertEquals(both, militiaCommitted(MilitiaPolicy.WHEN_ATTACKED, AlertState.ALERT, Reason.RECENT_ATTACKER));
        assertEquals(Set.of(), militiaCommitted(MilitiaPolicy.WHEN_ATTACKED, AlertState.ENGAGED, Reason.ATTACKING_RESIDENT),
                "targeting a resident is not yet an attack on one");

        assertEquals(Set.of(), militiaCommitted(MilitiaPolicy.ON_ENGAGED, AlertState.ALERT, Reason.ATTACKING_RESIDENT));
        assertEquals(both, militiaCommitted(MilitiaPolicy.ON_ENGAGED, AlertState.ENGAGED, Reason.ATTACKING_RESIDENT));

        assertEquals(both, militiaCommitted(MilitiaPolicy.ALWAYS, AlertState.ALERT, Reason.ATTACKING_RESIDENT));
        assertEquals(Set.of(), militiaCommitted(MilitiaPolicy.ALWAYS, AlertState.RECOVERY, Reason.ATTACKING_RESIDENT));
        assertEquals(Set.of(), militiaCommitted(MilitiaPolicy.ALWAYS, AlertState.ALERT, Reason.HYW_ENEMY),
                "militia available still means proactive=false");
    }
}

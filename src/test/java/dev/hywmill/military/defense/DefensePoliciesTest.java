package dev.hywmill.military.defense;

import dev.hywmill.military.doctrine.AssistMode;
import dev.hywmill.military.doctrine.ControllerAssist;
import dev.hywmill.military.doctrine.Doctrine;
import dev.hywmill.military.doctrine.DoctrineField;
import dev.hywmill.military.doctrine.DoctrinePatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dev.hywmill.military.defense.DefenseTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2-4 radius, M2-7 shelter, M2-8 player assistance, M2-9 alert lifecycle. */
class DefensePoliciesTest {
    // ---- M2-4 ----
    @Test
    void defenseRadiusIsHorizontal() {
        assertTrue(DefenseArea.inside(0, 0, 106, 106, 0));
        assertTrue(DefenseArea.inside(0, 0, 106, 74, 75));
        assertFalse(DefenseArea.inside(0, 0, 106, 75, 75), "106.07 > 106");
        assertFalse(DefenseArea.inside(0, 0, 106, 107, 0));
    }

    // ---- M2-7 ----
    @Test
    void shelterRadius() {
        List<DefenseCoordinator.Pos> threats = List.of(at(0, 0));
        assertTrue(ShelterPolicy.shouldShelter(AlertState.ALERT, 48, at(48, 0), threats));
        assertFalse(ShelterPolicy.shouldShelter(AlertState.ALERT, 48, at(49, 0), threats));
        assertTrue(ShelterPolicy.shouldShelter(AlertState.ENGAGED, Doctrine.VILLAGE_WIDE, at(500, 0), threats));
        assertFalse(ShelterPolicy.shouldShelter(AlertState.RECOVERY, Doctrine.VILLAGE_WIDE, at(1, 0), threats),
                "civilians resume in RECOVERY");
        assertFalse(ShelterPolicy.shouldShelter(AlertState.CALM, 48, at(1, 0), threats));
    }

    // ---- M2-8 ----
    static final UUID P = new UUID(7, 7);
    static final UUID CTRL = new UUID(8, 8);

    @Test
    void reputationThreshold() {
        Doctrine d = with(DoctrineField.ASSIST_MIN_REPUTATION, 256);
        assertTrue(AssistPolicy.qualifies(d, P, null, 256, false));
        assertFalse(AssistPolicy.qualifies(d, P, null, 255, false));
        assertTrue(AssistPolicy.qualifies(baseline(), P, null, 0, false), "baseline: stranger (0) is enough");
        assertFalse(AssistPolicy.qualifies(baseline(), P, null, -1, false));
        assertFalse(AssistPolicy.qualifies(with(DoctrineField.ASSIST_PLAYERS, AssistMode.NEVER), P, null, 9999, false));
        assertTrue(AssistPolicy.qualifies(with(DoctrineField.ASSIST_PLAYERS, AssistMode.ALWAYS), P, null, -9999, false));
    }

    @Test
    void provokingPlayer() {
        assertFalse(AssistPolicy.qualifies(baseline(), P, null, 5000, true), "struck first: no help");
        assertTrue(AssistPolicy.qualifies(with(DoctrineField.ASSIST_PROVOKING_PLAYER, true), P, null, 5000, true));
    }

    @Test
    void controller() {
        assertTrue(AssistPolicy.qualifies(baseline(), CTRL, CTRL, -5000, true), "ALWAYS: reputation and first strike ignored");
        assertFalse(AssistPolicy.qualifies(baseline(), P, CTRL, -5000, false), "another player is not the controller");
        assertFalse(AssistPolicy.qualifies(with(DoctrineField.ASSIST_CONTROLLER, ControllerAssist.NEVER), CTRL, CTRL, 9999, false));
        assertFalse(AssistPolicy.qualifies(with(DoctrineField.ASSIST_CONTROLLER, ControllerAssist.AS_PLAYER), CTRL, CTRL, 9999, true));
        assertTrue(AssistPolicy.qualifies(with(DoctrineField.ASSIST_CONTROLLER, ControllerAssist.AS_PLAYER), CTRL, CTRL, 9999, false));
    }

    // ---- M2-9 ----
    @Test
    void fullLifecycleWithTimers() {
        Doctrine d = baseline(); // 100 / 200 / 600
        AlertStateMachine m = new AlertStateMachine();
        assertNull(m.update(0, false, false, d));
        assertEquals(AlertState.CALM, m.update(20, true, false, d));
        assertEquals(AlertState.ALERT, m.state());
        assertEquals(AlertState.ALERT, m.update(40, true, true, d));
        assertEquals(AlertState.ENGAGED, m.state());
        m.update(60, false, false, d);
        m.update(220, false, false, d);
        assertEquals(AlertState.ENGAGED, m.state(), "180 < 200 ticks since the last threat (tick 40)");
        assertEquals(AlertState.ENGAGED, m.update(240, false, false, d));
        assertEquals(AlertState.RECOVERY, m.state());
        m.update(820, false, false, d);
        assertEquals(AlertState.RECOVERY, m.state(), "580 < 600");
        assertEquals(AlertState.RECOVERY, m.update(840, false, false, d));
        assertEquals(AlertState.CALM, m.state());
    }

    @Test
    void sightingWithoutFightingReturnsToCalmAfterAlertTimer() {
        Doctrine d = baseline();
        AlertStateMachine m = new AlertStateMachine();
        m.update(0, true, false, d);
        assertEquals(AlertState.ALERT, m.state());
        m.update(80, false, false, d);
        assertEquals(AlertState.ALERT, m.state());
        m.update(100, false, false, d);
        assertEquals(AlertState.CALM, m.state());
    }

    @Test
    void noThrashing() {
        Doctrine d = baseline();
        AlertStateMachine m = new AlertStateMachine();
        m.update(0, true, true, d);
        m.update(20, false, false, d);
        m.update(40, true, false, d);   // threat flickers back: still ENGAGED, timer restarts
        m.update(220, false, false, d);
        assertEquals(AlertState.ENGAGED, m.state());
        m.update(240, false, false, d);
        assertEquals(AlertState.RECOVERY, m.state());
        m.update(260, true, false, d);  // a new threat during RECOVERY: ALERT, not straight to ENGAGED
        assertEquals(AlertState.ALERT, m.state());
        m.update(280, true, true, d);
        assertEquals(AlertState.ENGAGED, m.state());
    }

    @Test
    void customTimers() {
        Doctrine d = with(DoctrinePatch.EMPTY.with(DoctrineField.RECOVERY_TICKS, 1200));
        AlertStateMachine m = new AlertStateMachine();
        m.update(0, true, true, d);
        m.update(200, false, false, d);
        assertEquals(AlertState.RECOVERY, m.state());
        m.update(1300, false, false, d);
        assertEquals(AlertState.RECOVERY, m.state());
        m.update(1400, false, false, d);
        assertEquals(AlertState.CALM, m.state());
    }
}

package dev.hywmill.garrison;

import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.military.defense.AlertState;
import dev.hywmill.military.defense.DefenseCoordinator;
import dev.hywmill.military.doctrine.Doctrine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Garrison deployment during an M2 alert. M2 stays authoritative: the threats are the M2
 * coordinator's threat views, and the assignment is M2's own {@link DefenseCoordinator#assign}
 * (actionable-threat rules, deterministic distance + UUID ordering, keep-previous assignments,
 * reserve) run over the garrison units with the garrison's {@code commitPerThreat}. Pure.
 */
public final class Deployment {
    /** A RETURNING unit is back when within this distance of the anchor. */
    public static final double HOME_RADIUS = 8.0;

    private Deployment() {}

    /** {@code home}: the unit's standing-duty home (M4), also accepted as "back" when returning; null in M3. */
    public record UnitView(RosterEntry entry, DefenseCoordinator.Pos pos, @javax.annotation.Nullable DefenseCoordinator.Pos home) {
        public UnitView(RosterEntry entry, DefenseCoordinator.Pos pos) {
            this(entry, pos, null);
        }
    }

    public enum ActionKind { ENGAGE, DISENGAGE }

    public record Action(ActionKind kind, RosterEntry entry, UUID threat) {}

    /** The M2 doctrine with the garrison's commitPerThreat (all other fields, including reserve and proactive, unchanged). */
    public static Doctrine forGarrison(Doctrine d, int commitPerThreat) {
        return new Doctrine(d.radiusOffset(), d.defenseRadius(), d.proactive(), commitPerThreat, d.reserve(), d.militiaPolicy(),
                d.shelterRadius(), d.assistPlayers(), d.assistMinReputation(), d.assistProvokingPlayer(), d.assistController(),
                d.alertTicks(), d.engagedTicks(), d.recoveryTicks());
    }

    public static DefenseCoordinator.Result plan(Doctrine doctrine, int commitPerThreat, AlertState state, List<UnitView> units,
                                                 List<DefenseCoordinator.ThreatView> threats, DefenseCoordinator.Pos anchor,
                                                 Map<UUID, UUID> previous, Set<UUID> previousReserve) {
        List<DefenseCoordinator.DefenderView> views = new ArrayList<>();
        for (UnitView u : units) {
            if (u.entry().state().deployable() && u.entry().entityUuid != null) {
                views.add(new DefenseCoordinator.DefenderView(u.entry().entityUuid, VillagerRole.SOLDIER, u.pos()));
            }
        }
        return DefenseCoordinator.assign(new DefenseCoordinator.Input(forGarrison(doctrine, commitPerThreat), state, views, threats,
                anchor, previous, previousReserve));
    }

    /**
     * Applies a plan to the roster states: assigned units become DEPLOYED (ENGAGE on their threat),
     * DEPLOYED units without an assignment become RETURNING (DISENGAGE), and RETURNING units within
     * {@link #HOME_RADIUS} of the anchor (or of their duty home) or past {@code returnTimeout} become GARRISONED.
     * M4: the unit's current duty follows (DEFENSE, RETURNING, then back to its standing duty).
     */
    public static List<Action> apply(List<UnitView> units, Map<UUID, UUID> assignments, DefenseCoordinator.Pos anchor, long tick,
                                     long returnTimeout) {
        List<Action> out = new ArrayList<>();
        for (UnitView u : units) {
            RosterEntry e = u.entry();
            if (e.entityUuid == null || !e.state().deployable()) {
                continue;
            }
            UUID threat = assignments.get(e.entityUuid);
            if (threat != null) {
                if (e.state() != UnitState.DEPLOYED) {
                    e.transition(UnitState.DEPLOYED, tick);
                }
                e.duty = Duty.DEFENSE;
                out.add(new Action(ActionKind.ENGAGE, e, threat));
            } else if (e.state() == UnitState.DEPLOYED) {
                e.transition(UnitState.RETURNING, tick);
                e.duty = Duty.RETURNING;
                out.add(new Action(ActionKind.DISENGAGE, e, null));
            } else if (e.state() == UnitState.RETURNING) {
                double dx = u.pos().x() - anchor.x(), dz = u.pos().z() - anchor.z();
                double d2 = dx * dx + dz * dz;
                if (u.home() != null) {
                    double hx = u.pos().x() - u.home().x(), hz = u.pos().z() - u.home().z();
                    d2 = Math.min(d2, hx * hx + hz * hz);
                }
                if (d2 <= HOME_RADIUS * HOME_RADIUS || tick - e.stateSinceTick >= returnTimeout) {
                    e.transition(UnitState.GARRISONED, tick);
                    e.duty = e.assignedDuty;
                }
            }
        }
        return out;
    }

}

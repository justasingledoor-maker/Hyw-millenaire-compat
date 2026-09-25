package dev.hywmill.garrison;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of one roster slot. DEAD and LOST are terminal. RECOVERED is a short-lived live state:
 * a MISSING (or stale RECRUITED) slot whose entity was observed again; the next reconciliation
 * moves it back to GARRISONED.
 *
 * <p>Only the transitions in {@link #ALLOWED} are legal; {@link RosterEntry#transition} enforces them.
 */
public enum UnitState {
    RECRUITED, SPAWNED, GARRISONED, DEPLOYED, RETURNING, MISSING, RECOVERED, DEAD, LOST;

    private static final Map<UnitState, Set<UnitState>> ALLOWED = Map.of(
            RECRUITED, EnumSet.of(SPAWNED, RECOVERED, LOST),
            SPAWNED, EnumSet.of(RECRUITED, GARRISONED, MISSING, DEAD, LOST),
            GARRISONED, EnumSet.of(DEPLOYED, MISSING, DEAD, LOST),
            DEPLOYED, EnumSet.of(RETURNING, MISSING, DEAD, LOST),
            RETURNING, EnumSet.of(GARRISONED, DEPLOYED, MISSING, DEAD, LOST),
            MISSING, EnumSet.of(RECOVERED, DEAD, LOST),
            RECOVERED, EnumSet.of(GARRISONED, DEPLOYED, MISSING, DEAD, LOST),
            DEAD, EnumSet.noneOf(UnitState.class),
            LOST, EnumSet.noneOf(UnitState.class));

    public boolean terminal() {
        return this == DEAD || this == LOST;
    }

    /** A live slot that has (or had) an entity in the world: every state except RECRUITED and the terminal ones. */
    public boolean bound() {
        return !terminal() && this != RECRUITED;
    }

    /** Units at the village's disposal for deployment when loaded. */
    public boolean deployable() {
        return this == GARRISONED || this == RETURNING || this == RECOVERED || this == DEPLOYED;
    }

    public boolean canTransition(UnitState to) {
        return ALLOWED.get(this).contains(to);
    }
}

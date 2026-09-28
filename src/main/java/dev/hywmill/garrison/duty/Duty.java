package dev.hywmill.garrison.duty;

/**
 * Military duty of a living garrison unit (M4). Separate from the M3 lifecycle {@code UnitState}:
 * a unit can be GARRISONED on SENTRY duty, or DEPLOYED on DEFENSE or RAID duty.
 *
 * <p>Standing duties are assigned by the duty allocation; DEFENSE, RAID and RETURNING are
 * temporary and always return to the unit's standing duty.
 */
public enum Duty {
    GARRISON, SENTRY, PATROL, SCOUT, RESERVE, DEFENSE, RAID, RETURNING,
    /** M5-5: holding a point a player named (DEPLOYED, away from home defense like RAID). Escorts are deferred (not in M5). */
    DETACHED,
    /** Post-M5: in a siege host (mustering, marching stowed, fighting at the target, marching home). Away like RAID. */
    SIEGE;

    /** Away from the village on a temporary errand (RAID, DETACHED): excluded from home defense and standing duties. */
    public boolean away() {
        return this == RAID || this == DETACHED || this == SIEGE;
    }

    /** M5-5 errands lent to a player (detachments). */
    public boolean errand() {
        return this == DETACHED;
    }

    public boolean standing() {
        return this == GARRISON || this == SENTRY || this == PATROL || this == SCOUT || this == RESERVE;
    }

    public static Duty parse(String s, Duty dflt) {
        try {
            return s == null || s.isEmpty() ? dflt : valueOf(s);
        } catch (IllegalArgumentException e) {
            return dflt;
        }
    }
}

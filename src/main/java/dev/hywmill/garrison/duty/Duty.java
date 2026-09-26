package dev.hywmill.garrison.duty;

/**
 * Military duty of a living garrison unit (M4). Separate from the M3 lifecycle {@code UnitState}:
 * a unit can be GARRISONED on SENTRY duty, or DEPLOYED on DEFENSE or RAID duty.
 *
 * <p>Standing duties are assigned by the duty allocation; DEFENSE, RAID and RETURNING are
 * temporary and always return to the unit's standing duty.
 */
public enum Duty {
    GARRISON, SENTRY, PATROL, SCOUT, RESERVE, DEFENSE, RAID, RETURNING;

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

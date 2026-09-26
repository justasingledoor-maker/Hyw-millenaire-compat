package dev.hywmill.garrison.duty;

/**
 * Movement of duty units (M4). Units are only ever moved by setting their HYW home: one hop of at
 * most {@code maxHop} blocks at a time on loaded, standable ground; the next hop is issued on
 * arrival or after {@code hopTimeout} ticks.
 *
 * @param maxHop          longest single home move (blocks)
 * @param arriveRadius    a unit within this horizontal distance of its goal has arrived
 * @param hopTimeout      ticks after which a waypoint counts as reached even if not arrived
 * @param patrolRadius    patrol waypoints lie within this fraction of the village radius
 * @param patrolPoints    most patrol waypoints (sectors around the centre)
 * @param patrolPause     ticks a patrol unit waits at each waypoint
 * @param sentrySpacing   distance between the two sentries of a pair
 */
public record MoveRule(int maxHop, int arriveRadius, long hopTimeout, double patrolRadius, int patrolPoints, long patrolPause,
                       int sentrySpacing) {
    public static final MoveRule DEFAULT = new MoveRule(32, 4, 600, 0.75, 8, 100, 3);
}

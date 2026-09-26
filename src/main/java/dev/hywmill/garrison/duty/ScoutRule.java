package dev.hywmill.garrison.duty;

/**
 * Scouting (M4): scouts ride out to posts on a ring {@code distance} blocks outside the village
 * radius, watch for {@code dwell} ticks, ride back and rest {@code rest} ticks, and repeat. Any
 * alert that is not CALM recalls them at once.
 *
 * @param distance     distance of the scout ring beyond the village radius
 * @param posts        number of scout posts on the ring (rotated per village)
 * @param dwell        ticks spent at the post
 * @param rest         ticks spent resting in the village between patrols
 * @param phaseTimeout ticks after which a ride out or back ends where the scout is
 */
public record ScoutRule(int distance, int posts, long dwell, long rest, long phaseTimeout) {
    public static final ScoutRule DEFAULT = new ScoutRule(40, 4, 1200, 2400, 2400);
}

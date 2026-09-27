package dev.hywmill.garrison.duty;

import dev.hywmill.garrison.RosterEntry;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * M4 reliability recovery (M5, approved): a safety net for home-duty units (GARRISON, SENTRY, PATROL, SCOUT) that the
 * normal duty movement, with its own detours and bounded recovery, has not got anywhere for a long time. It is not a
 * movement system: the unit keeps being moved by the ordinary hop movement, only towards a different goal.
 *
 * <ol>
 *   <li>Progress is watched per unit: arriving at (or holding near) its goal, or getting {@link #PROGRESS} blocks
 *       closer to it. A unit with no progress for {@link Limits#stuckTicks} is STUCK; a unit that is merely slow is not.</li>
 *   <li>A stuck unit abandons its goal and walks (normal movement) to a fallback spot by the village's defending
 *       position or centre ({@link #fallbackSpot}).</li>
 *   <li>If it makes no progress towards that spot either for {@link Limits#fallbackTicks}, it is moved onto the
 *       spot, once (last resort).</li>
 *   <li>Arrived or moved: it goes on ordinary GARRISON duty ({@link #toGarrison}) and the watch starts again; the next
 *       allocation pass redistributes it.</li>
 * </ol>
 * Pure; the caller supplies positions, the goal and the ground checks. Runtime state only (nothing persisted).
 */
public final class StuckWatch {
    private StuckWatch() {}

    /** Getting this much closer to the goal is progress. */
    public static final double PROGRESS = 4;
    /** Static duties (and a resting scout) within this of their spot are at duty: M4's own "hold where reachable" radius. */
    public static final double HOLD = 24;
    /** Fallback spots lie within this horizontal distance of the defending position or centre. */
    public static final int FALLBACK_RADIUS = 16;

    /** Duties this recovery watches (home duties the duty movement drives). */
    public static boolean watched(Duty d) {
        return d == Duty.GARRISON || d == Duty.SENTRY || d == Duty.PATROL || d == Duty.SCOUT;
    }

    /** Distance at which a unit on {@code d} counts as at its goal. */
    public static double arrive(Duty d, MoveRule m) {
        return d == Duty.PATROL ? m.arriveRadius() * 2 : HOLD;
    }

    /** Distance at which a falling-back unit has reached its fallback spot (by normal movement). */
    public static double arriveFallback(MoveRule m) {
        return m.arriveRadius() * 2;
    }

    /**
     * @param stuckTicks    no progress for this long means stuck; longer than any legitimate wait of the duty movement
     *                      (a scout that cannot ride further watches where it is: up to phaseTimeout + dwell)
     * @param fallbackTicks no progress towards the fallback spot for this long allows the last-resort move (one full
     *                      detour cycle of the normal movement: 5 hop timeouts)
     * @param gapTicks      a unit not seen for longer (unloaded, deployed, away) starts a fresh watch
     */
    public record Limits(long stuckTicks, long fallbackTicks, long gapTicks) {
        public static Limits of(MoveRule m, ScoutRule s) {
            return new Limits(Math.max(6000, s.phaseTimeout() + s.dwell() + 2 * m.hopTimeout()), 5 * m.hopTimeout(), m.hopTimeout());
        }
    }

    public enum Step { MOVE, TELEPORT, RECOVERED }

    /** Per-unit watch. */
    public static final class Track {
        long goal;
        double best;
        long lastProgress;
        long lastSeen = Long.MIN_VALUE;
        @Nullable BlockPos spot;
        double spotBest;
        long spotProgress;

        /** The fallback spot while the unit is falling back, else null. */
        @Nullable
        public BlockPos spot() {
            return spot;
        }
    }

    private static boolean fresh(Track t, long tick, Limits lim) {
        boolean gap = t.lastSeen == Long.MIN_VALUE || tick - t.lastSeen > lim.gapTicks();
        t.lastSeen = tick;
        return gap;
    }

    /**
     * Normal duty movement, once per duty tick: records progress towards {@code goal} ({@code dist} away). True when the
     * unit has made no progress for {@link Limits#stuckTicks}.
     */
    public static boolean observe(Track t, BlockPos goal, double dist, double arrive, long tick, Limits lim) {
        if (fresh(t, tick, lim)) {
            t.goal = goal.asLong();
            t.best = dist;
            t.lastProgress = tick;
            return false;
        }
        if (t.goal != goal.asLong()) { // next waypoint or phase: a new baseline, the clock runs on
            t.goal = goal.asLong();
            t.best = dist;
        }
        if (dist <= arrive || dist <= t.best - PROGRESS) {
            t.best = Math.min(t.best, dist);
            t.lastProgress = tick;
        }
        return tick - t.lastProgress >= lim.stuckTicks();
    }

    /** Starts the fallback towards {@code spot} ({@code dist} away). */
    public static void fallBack(Track t, BlockPos spot, double dist, long tick) {
        t.spot = spot;
        t.spotBest = dist;
        t.spotProgress = tick;
    }

    /** No fallback spot now: try again after another full window (never every tick). */
    public static void retryLater(Track t, long tick) {
        t.lastProgress = tick;
    }

    /** While falling back, once per duty tick: MOVE (normal movement), TELEPORT (last resort) or RECOVERED (arrived). */
    public static Step fallback(Track t, double dist, double arrive, long tick, Limits lim) {
        if (dist <= arrive) {
            return Step.RECOVERED;
        }
        if (fresh(t, tick, lim) || dist <= t.spotBest - PROGRESS) {
            t.spotBest = Math.min(t.spotBest, dist);
            t.spotProgress = tick;
        }
        return tick - t.spotProgress >= lim.fallbackTicks() ? Step.TELEPORT : Step.MOVE;
    }

    /**
     * The last-resort move could not be made (the spot is no longer valid): the fallback is dropped, the unit goes back to
     * its duty, and another full window without progress is needed before a new fallback.
     */
    public static void abandonFallback(Track t, long tick) {
        t.spot = null;
        t.goal = 0;
        t.best = Double.MAX_VALUE;
        t.lastProgress = tick;
    }

    /**
     * Recovered: ordinary GARRISON duty, the failed target and progress cleared; the watch starts again from now (so no
     * second recovery can follow before another full window without progress).
     */
    public static void toGarrison(RosterEntry e, Track t, long tick) {
        e.assignedDuty = Duty.GARRISON;
        e.duty = Duty.GARRISON;
        e.dutyIndex = -1;
        e.dutyStep = 0;
        e.dutySince = tick;
        t.spot = null;
        t.goal = 0;
        t.best = Double.MAX_VALUE;
        t.lastProgress = tick;
        t.lastSeen = tick;
    }

    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final int[] RINGS = {0, 4, 8, 11};

    /**
     * The fallback spot of one unit: for each anchor in order (the defending position, then the village centre), the
     * anchor itself, then points on rings of 4, 8 and 11 blocks around it (8 directions, starting at a direction chosen by
     * the roster id so units spread); the first that {@code safeStand} resolves (same dimension, loaded, standable, safe;
     * null otherwise) and that lies within {@link #FALLBACK_RADIUS} of its anchor. Deterministic; null if none.
     */
    @Nullable
    public static BlockPos fallbackSpot(UUID rosterId, List<BlockPos> anchors, Function<BlockPos, BlockPos> safeStand) {
        int start = (int) Math.floorMod(rosterId.getLeastSignificantBits(), (long) DIRS.length);
        for (BlockPos a : anchors) {
            for (int r : RINGS) {
                for (int i = 0; i < (r == 0 ? 1 : DIRS.length); i++) {
                    int[] d = DIRS[(start + i) % DIRS.length];
                    BlockPos s = safeStand.apply(a.offset(d[0] * r, 0, d[1] * r));
                    if (s != null && DutyMotion.horizontal(s.getX() + 0.5, s.getZ() + 0.5, a) <= FALLBACK_RADIUS) {
                        return s;
                    }
                }
            }
        }
        return null;
    }
}

package dev.hywmill.garrison.duty;

import dev.hywmill.garrison.RosterEntry;
import net.minecraft.core.BlockPos;

/**
 * Per-unit duty progress (M4): which point a unit on standing duty is heading for, and when a
 * patrol waypoint or scout phase is complete. Pure; progress lives in the roster entry
 * ({@code dutyStep}, {@code dutySince}) so it survives restarts and unloads.
 *
 * <p>Patrol step: {@code waypoint * 2 + (arrived ? 1 : 0)}. Scout step: {@code phase + 4 * ride}, the phase
 * one of {@link #REST}, {@link #OUT}, {@link #DWELL}, {@link #BACK}; each ride goes to the next scout post
 * (the scout's own index plus the ride count), so scouts cover every direction over time.
 */
public final class DutyMotion {
    public static final int REST = 0, OUT = 1, DWELL = 2, BACK = 3;

    private DutyMotion() {}

    /**
     * @param calm          the village's M2 alert is CALM (any other state recalls scouts)
     * @param sentryMember  0 or 1 within the unit's sentry pair
     * @param patrolCount   units on patrol (spreads their start waypoints)
     */
    public record Ctx(MoveRule move, ScoutRule scout, BlockPos center, boolean calm, int sentryMember, int patrolCount) {}

    /** Initial progress for a unit that has just been given {@code duty}. */
    public static void start(RosterEntry e, DutyPlan plan, Ctx c, long tick) {
        e.dutySince = tick;
        e.dutyStep = switch (e.assignedDuty) {
            case PATROL -> plan.patrolStart(e.dutyIndex, c.patrolCount()) * 2;
            case SCOUT -> OUT;
            default -> 0;
        };
    }

    /** Where the unit should be heading now; advances waypoints and phases. {@code x, z}: the unit's position. */
    public static BlockPos goal(RosterEntry e, double x, double z, DutyPlan plan, Ctx c, long tick) {
        return switch (e.assignedDuty) {
            case SENTRY -> plan.sentrySpot(e.dutyIndex, c.sentryMember(), c.center(), c.move().sentrySpacing());
            case RESERVE -> plan.reserveSpot(e.rosterId);
            case PATROL -> patrolGoal(e, x, z, plan, c, tick);
            case SCOUT -> scoutGoal(e, x, z, plan, c, tick);
            default -> plan.musterFor(e.rosterId);
        };
    }

    private static BlockPos patrolGoal(RosterEntry e, double x, double z, DutyPlan plan, Ctx c, long tick) {
        int n = plan.patrol().size();
        int wp = Math.floorMod(e.dutyStep / 2, n);
        BlockPos p = plan.patrol().get(wp);
        if ((e.dutyStep & 1) == 1) {
            if (tick - e.dutySince >= c.move().patrolPause()) {
                next(e, wp, n, tick);
                return plan.patrol().get((wp + 1) % n);
            }
            return p;
        }
        double d = horizontal(x, z, p);
        if (d <= c.move().arriveRadius()) {
            e.dutyStep = wp * 2 + 1;
            e.dutySince = tick;
        } else if (tick - e.dutySince >= legTimeout(c.move(), d)) {
            next(e, wp, n, tick);
            return plan.patrol().get((wp + 1) % n);
        }
        return p;
    }

    private static void next(RosterEntry e, int wp, int n, long tick) {
        e.dutyStep = ((wp + 1) % n) * 2;
        e.dutySince = tick;
    }

    /** Time allowed for one patrol leg: one hop timeout per started {@code maxHop} of the remaining distance, at least one. */
    public static long legTimeoutFor(MoveRule m, double remaining) {
        return legTimeout(m, remaining);
    }

    static long legTimeout(MoveRule m, double remaining) {
        return m.hopTimeout() * (1 + (long) (remaining / m.maxHop()));
    }

    public static int phase(RosterEntry e) {
        return Math.floorMod(e.dutyStep, 4);
    }

    static int ride(RosterEntry e) {
        return Math.floorDiv(e.dutyStep, 4);
    }

    private static BlockPos scoutGoal(RosterEntry e, double x, double z, DutyPlan plan, Ctx c, long tick) {
        BlockPos post = plan.scoutPost(e.dutyIndex + ride(e));
        BlockPos base = plan.scoutBase();
        if (!c.calm() && (phase(e) == OUT || phase(e) == DWELL)) {
            phase(e, BACK, tick); // any alert recalls scouts at once
        }
        switch (phase(e)) {
            case OUT -> {
                if (horizontal(x, z, post) <= c.move().arriveRadius() || tick - e.dutySince >= c.scout().phaseTimeout()) {
                    phase(e, DWELL, tick);
                }
                return post;
            }
            case DWELL -> {
                if (tick - e.dutySince >= c.scout().dwell()) {
                    phase(e, BACK, tick);
                    return base;
                }
                return post;
            }
            case BACK -> {
                if (horizontal(x, z, base) <= c.move().arriveRadius() * 2 || tick - e.dutySince >= c.scout().phaseTimeout()) {
                    phase(e, REST, tick);
                }
                return base;
            }
            default -> {
                if (c.calm() && tick - e.dutySince >= c.scout().rest()) {
                    e.dutyStep = 4 * ((ride(e) + 1) % 64) + OUT; // next ride, next post
                    e.dutySince = tick;
                    return plan.scoutPost(e.dutyIndex + ride(e));
                }
                return base;
            }
        }
    }

    /**
     * The next move is impossible (the way ahead is not loaded, or there is no standable ground):
     * a scout riding out watches from where it is, a patrol unit skips to the next waypoint.
     */
    public static void blocked(RosterEntry e, DutyPlan plan, long tick) {
        if (e.assignedDuty == Duty.SCOUT && phase(e) == OUT) {
            phase(e, DWELL, tick);
        } else if (e.assignedDuty == Duty.SCOUT && phase(e) == BACK) {
            phase(e, REST, tick);
        } else if (e.assignedDuty == Duty.PATROL && (e.dutyStep & 1) == 0) {
            next(e, Math.floorMod(e.dutyStep / 2, plan.patrol().size()), plan.patrol().size(), tick);
        }
    }

    /** Whether a scout is outside on its ride (OUT or DWELL). */
    public static boolean scoutAway(RosterEntry e) {
        return e.assignedDuty == Duty.SCOUT && (phase(e) == OUT || phase(e) == DWELL);
    }

    /** Sets the scout phase, keeping the ride count. */
    private static void phase(RosterEntry e, int phase, long tick) {
        e.dutyStep = 4 * ride(e) + phase;
        e.dutySince = tick;
    }

    /**
     * One home move towards {@code goal}: the goal itself if within {@code maxHop}, else the point
     * {@code maxHop} blocks along the straight line (Y left to the caller's ground resolution).
     */
    public static BlockPos hop(double x, double y, double z, BlockPos goal, int maxHop) {
        double dx = goal.getX() + 0.5 - x, dz = goal.getZ() + 0.5 - z;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= maxHop) {
            return goal;
        }
        double f = maxHop / d;
        return BlockPos.containing(x + dx * f, y, z + dz * f);
    }

    public static double horizontal(double x, double z, BlockPos p) {
        double dx = p.getX() + 0.5 - x, dz = p.getZ() + 0.5 - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Short phase label for diagnostics. */
    public static String progress(RosterEntry e) {
        return switch (e.assignedDuty) {
            case PATROL -> "wp" + e.dutyStep / 2 + ((e.dutyStep & 1) == 1 ? "(pause)" : "");
            case SCOUT -> switch (phase(e)) {
                case OUT -> "out";
                case DWELL -> "watch";
                case BACK -> "back";
                default -> "rest";
            };
            default -> "";
        };
    }
}

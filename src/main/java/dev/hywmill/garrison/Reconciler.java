package dev.hywmill.garrison;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Per-slot reconciliation of a roster against what is loaded in the world. Pure: the world is
 * reached only through the {@code lookup} function (entity UUID to observation, null if not loaded).
 *
 * <p>Unloaded is never dead and never lost: a slot only becomes MISSING after {@code missingGrace}
 * of <em>active</em> time without being seen, and LOST after {@code lostTimeout} more. Inactive
 * time is excluded with {@link #excludeInactive}.
 */
public final class Reconciler {
    private Reconciler() {}

    /** A loaded, living, bound entity. */
    public record Observation(@Nullable UUID owner, long x, long y, long z) {}

    public enum Kind { CONFIRMED, RECOVERED, ADOPTED, MISSING, LOST_TIMEOUT, CAPTURED }

    public record Event(Kind kind, RosterEntry entry, @Nullable UUID entityUuid) {}

    /**
     * Shifts the timers of live entries by the part of the gap since the last active slot that
     * exceeds {@link GarrisonSettings#maxActiveStep()} (the village was not active meanwhile).
     * Returns the excluded ticks.
     */
    public static long excludeInactive(GarrisonRoster roster, long tick, GarrisonSettings s) {
        long gap = tick - roster.lastAccrualTick;
        long excess = gap - Math.min(gap, s.maxActiveStep());
        if (excess <= 0) {
            return 0;
        }
        for (RosterEntry e : roster.entries()) {
            if (!e.state().terminal()) {
                e.stateSinceTick += excess;
                if (e.lastSeenTick >= 0) {
                    e.lastSeenTick += excess;
                }
            }
        }
        return excess;
    }

    /**
     * @param settled the village has been active long enough (settle delay) for loaded entities to have
     *                joined; before that, an unobserved entity is never marked MISSING
     */
    public static List<Event> reconcile(GarrisonRoster roster, UUID faction, Function<UUID, Observation> lookup, long tick,
                                        boolean settled, GarrisonSettings s) {
        List<Event> out = new ArrayList<>();
        for (RosterEntry e : roster.entries()) {
            UnitState st = e.state();
            if (st == UnitState.RECRUITED) {
                // Stale roster (saved before its last spawn) while that spawn's entity is loaded: the
                // entity carries the UUID the slot's next spawn would get. Adopt it; never spawn again.
                UUID expected = dev.hywmill.garrison.tag.GarrisonTag.entityUuid(e.rosterId, e.generation + 1);
                Observation obs = lookup.apply(expected);
                if (obs != null && faction.equals(obs.owner())) {
                    e.generation++;
                    e.entityUuid = expected;
                    e.transition(UnitState.RECOVERED, tick);
                    e.seen(tick, obs.x(), obs.y(), obs.z());
                    roster.totals.recovered++;
                    out.add(new Event(Kind.ADOPTED, e, expected));
                } else if (obs != null) {
                    // that unit exists but has a new owner: the slot was captured, never respawned for free
                    e.transition(UnitState.LOST, tick, LossReason.CAPTURED);
                    roster.totals.lost++;
                    out.add(new Event(Kind.CAPTURED, e, expected));
                }
                continue;
            }
            if (st.terminal() || st == UnitState.RECRUITED || e.entityUuid == null) {
                continue;
            }
            Observation obs = lookup.apply(e.entityUuid);
            if (obs != null) {
                if (!faction.equals(obs.owner())) {
                    e.transition(UnitState.LOST, tick, LossReason.CAPTURED);
                    roster.totals.lost++;
                    out.add(new Event(Kind.CAPTURED, e, e.entityUuid));
                    continue;
                }
                e.seen(tick, obs.x(), obs.y(), obs.z());
                switch (st) {
                    case SPAWNED, RECOVERED -> {
                        e.transition(UnitState.GARRISONED, tick);
                        out.add(new Event(Kind.CONFIRMED, e, e.entityUuid));
                    }
                    case MISSING -> {
                        e.transition(UnitState.RECOVERED, tick);
                        roster.totals.recovered++;
                        out.add(new Event(Kind.RECOVERED, e, e.entityUuid));
                    }
                    default -> { }
                }
                continue;
            }
            if (!settled) {
                continue;
            }
            if (st == UnitState.MISSING) {
                if (tick - e.stateSinceTick >= s.lostTimeout()) {
                    e.transition(UnitState.LOST, tick, LossReason.MISSING_TIMEOUT);
                    roster.totals.lost++;
                    out.add(new Event(Kind.LOST_TIMEOUT, e, e.entityUuid));
                }
            } else if (tick - Math.max(e.lastSeenTick, e.stateSinceTick) >= s.missingGrace()) {
                e.transition(UnitState.MISSING, tick);
                out.add(new Event(Kind.MISSING, e, e.entityUuid));
            }
        }
        return out;
    }

    /**
     * Village disappearance (Millénaire fires no deletion event). Returns the entries that became
     * LOST(VILLAGE_GONE) now; empty while the village is present or within the grace period.
     */
    public static List<RosterEntry> villageGone(GarrisonRoster roster, boolean present, long tick, GarrisonSettings s) {
        if (present) {
            roster.goneSinceTick = -1;
            return List.of();
        }
        if (roster.goneSinceTick < 0) {
            roster.goneSinceTick = tick;
            return List.of();
        }
        if (tick - roster.goneSinceTick < s.villageGoneGrace()) {
            return List.of();
        }
        List<RosterEntry> lost = new ArrayList<>();
        for (RosterEntry e : roster.entries()) {
            if (!e.state().terminal()) {
                e.transition(UnitState.LOST, tick, LossReason.VILLAGE_GONE);
                roster.totals.lost++;
                lost.add(e);
            }
        }
        return lost;
    }
}

package dev.hywmill.garrison;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * One soldier slot of a village garrison. Identity ({@link #rosterId}) is stable and never reused;
 * {@link #generation} counts spawn attempts of the slot and is stamped into the entity tag.
 * State changes only through {@link #transition}.
 */
public final class RosterEntry {
    public final UUID rosterId;
    public final String unitKey;
    /** Frozen at recruitment: a datapack edit never changes an existing unit. */
    public final String entityType;
    public int equipmentLevel;
    private UnitState state;
    @Nullable public UUID entityUuid;
    public int generation;
    public long stateSinceTick;
    public long lastSeenTick;
    /** Diagnostics only. */
    public long lastSeenX, lastSeenY, lastSeenZ;
    public final long recruitedTick;
    @Nullable private LossReason lossReason;
    public final boolean paid;

    // ---- M4 duty (persisted as optional keys; an M3 roster loads as GARRISON) ----
    /** Standing duty assigned by the duty allocation. */
    public dev.hywmill.garrison.duty.Duty assignedDuty = dev.hywmill.garrison.duty.Duty.GARRISON;
    /** Current duty: the standing duty, or DEFENSE/RAID/RETURNING while temporarily away from it. */
    public dev.hywmill.garrison.duty.Duty duty = dev.hywmill.garrison.duty.Duty.GARRISON;
    /** Post / route / pair index of the duty (-1: none). */
    public int dutyIndex = -1;
    /** Progress within the duty (patrol waypoint, scout phase step). */
    public int dutyStep;
    public long dutySince;

    public RosterEntry(UUID rosterId, String unitKey, String entityType, int equipmentLevel, long recruitedTick, boolean paid) {
        this(rosterId, unitKey, entityType, equipmentLevel, UnitState.RECRUITED, null, 0, recruitedTick, -1, recruitedTick, null, paid);
    }

    RosterEntry(UUID rosterId, String unitKey, String entityType, int equipmentLevel, UnitState state, @Nullable UUID entityUuid,
                int generation, long stateSinceTick, long lastSeenTick, long recruitedTick, @Nullable LossReason lossReason, boolean paid) {
        this.rosterId = Objects.requireNonNull(rosterId);
        this.unitKey = unitKey;
        this.entityType = entityType;
        this.equipmentLevel = equipmentLevel;
        this.state = state;
        this.entityUuid = entityUuid;
        this.generation = generation;
        this.stateSinceTick = stateSinceTick;
        this.lastSeenTick = lastSeenTick;
        this.recruitedTick = recruitedTick;
        this.lossReason = lossReason;
        this.paid = paid;
    }

    public UnitState state() {
        return state;
    }

    @Nullable
    public LossReason lossReason() {
        return lossReason;
    }

    /** Moves to {@code to}; throws if the transition is not legal. Terminal states need a reason. */
    public void transition(UnitState to, long tick, @Nullable LossReason reason) {
        if (!state.canTransition(to)) {
            throw new IllegalStateException("illegal garrison transition " + state + " -> " + to + " for " + rosterId);
        }
        if (to.terminal() && reason == null) {
            throw new IllegalArgumentException("terminal state " + to + " needs a loss reason");
        }
        state = to;
        stateSinceTick = tick;
        if (to.terminal()) {
            lossReason = reason;
        }
    }

    public void transition(UnitState to, long tick) {
        transition(to, tick, null);
    }

    /**
     * DEV ONLY ({@code /hywmill dev rewind}): puts the slot back to how a stale save written just
     * before its last spawn would show it (RECRUITED, previous generation, unbound), without
     * touching the entity. Used to prove that a stale roster cannot duplicate a unit.
     */
    public void devRewind(long tick) {
        state = UnitState.RECRUITED;
        entityUuid = null;
        generation = Math.max(0, generation - 1);
        stateSinceTick = tick;
        lossReason = null;
    }

    public void seen(long tick, long x, long y, long z) {
        lastSeenTick = tick;
        lastSeenX = x;
        lastSeenY = y;
        lastSeenZ = z;
    }

    public String shortId() {
        return rosterId.toString().substring(0, 8);
    }

    @Override
    public String toString() {
        return shortId() + " " + unitKey + " lvl" + equipmentLevel + " " + state + (lossReason != null ? "(" + lossReason + ")" : "")
                + " gen" + generation + (entityUuid != null ? " entity=" + entityUuid.toString().substring(0, 8) : "")
                + " duty=" + duty + (duty != assignedDuty ? "/" + assignedDuty : "") + (dutyIndex >= 0 ? "#" + dutyIndex : "");
    }
}

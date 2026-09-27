package dev.hywmill.garrison;

/**
 * M5-G: the garrison target only makes scaling decisions from an authoritative village state.
 * After a village becomes active (server start, chunk load) its buildings and residents load over
 * several seconds, and the first profile refreshes can see a partial village (observed: a
 * stronghold briefly assessed as GUARD_POST). Until the target inputs are authoritative the target
 * is frozen at {@code min(computed, live)}: nothing is recruited or granted, nothing is trimmed.
 *
 * <p>Authoritative when the record has been refreshed since this activation, is not awaiting a
 * migration recompute, and its target inputs have not changed for {@code settleTicks} of active
 * time. Transient, one per village, server thread only.
 */
public final class ScalingGate {
    private int key;
    private long since = Long.MIN_VALUE;

    /**
     * Observes the current inputs at a garrison slot and returns whether they are authoritative.
     *
     * @param inputsKey      hash of every target input (tier, capacity, buildings, ...)
     * @param activeSince    tick the village became active in this activation
     * @param lastUpdateTick tick of the record's last profile refresh
     * @param recordReady    the record has been refreshed at least once and needs no migration recompute
     */
    public boolean observe(int inputsKey, long tick, long activeSince, long lastUpdateTick, boolean recordReady, long settleTicks) {
        if (since == Long.MIN_VALUE || inputsKey != key) {
            key = inputsKey;
            since = tick;
        }
        // inputs seen in an earlier activation are no evidence for this one
        since = Math.max(since, activeSince);
        return recordReady && lastUpdateTick >= activeSince && tick - since >= settleTicks;
    }

    /** The target to act on: the computed one when authoritative, else no growth and no trimming. */
    public static int gated(int computed, boolean authoritative, int live) {
        return authoritative ? computed : Math.min(computed, live);
    }
}

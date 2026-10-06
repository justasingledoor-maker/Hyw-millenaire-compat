package dev.hywmill.politics.war;

import java.util.UUID;

/**
 * The military state between two villages (M5-5b), one per pair ({@code a < b}). Pure and mutable;
 * persisted at ledger level. Millénaire's relation stays the diplomatic fact; a war is its military
 * consequence: it starts after the relation has stayed at open conflict for a minimum time (or at
 * once when a raid between them is under way), and ends at a truce, when the switch is off, or after
 * the relation has stayed above open conflict for a minimum time.
 */
public final class WarRecord {
    public final UUID a;
    public final UUID b;
    /** Tick the pair entered open conflict (-1: not in conflict). */
    public long conflictSince = -1;
    /** Tick the pair left open conflict while at war (-1: not calm). */
    public long calmSince = -1;
    /** Tick the war started (-1: not at war). */
    public long warSince = -1;
    /**
     * Post-M5: the side each third village took in this war (the village it supports; {@link #NEUTRAL}: neither). A village
     * helps one side of a war at most: relief and the like go only to the side it took.
     */
    public final java.util.Map<UUID, UUID> sides = new java.util.LinkedHashMap<>();
    public static final UUID NEUTRAL = new UUID(0, 0);

    /** Whether {@code helper} may help {@code side} in this war (it took no side yet, or that one). */
    public boolean mayHelp(UUID helper, UUID side) {
        UUID took = sides.get(helper);
        return took == null || took.equals(side);
    }

    public enum Change { NONE, STARTED, ENDED }

    public WarRecord(UUID x, UUID y) {
        boolean ordered = x.compareTo(y) < 0;
        this.a = ordered ? x : y;
        this.b = ordered ? y : x;
    }

    public static String key(UUID x, UUID y) {
        return x.compareTo(y) < 0 ? x + ">" + y : y + ">" + x;
    }

    public String key() {
        return a + ">" + b;
    }

    public boolean atWar() {
        return warSince >= 0;
    }

    public boolean involves(UUID v) {
        return a.equals(v) || b.equals(v);
    }

    public UUID other(UUID v) {
        return a.equals(v) ? b : a;
    }

    /**
     * @param openConflict Millénaire relation ≤ −90 (either direction)
     * @param raid         a raid between the two is under way
     * @param truce        a truce between them is in force
     * @param autoWar      the server switch {@code politics.autoWar}
     */
    public Change update(long now, boolean openConflict, boolean raid, boolean truce, boolean autoWar, long minConflict, long minPeace) {
        boolean hostile = openConflict || raid;
        if (!autoWar || truce) {
            conflictSince = -1;
            calmSince = -1;
            return end();
        }
        if (!atWar()) {
            if (!hostile) {
                conflictSince = -1;
                return Change.NONE;
            }
            if (conflictSince < 0) {
                conflictSince = now;
            }
            if (raid || now - conflictSince >= minConflict) {
                warSince = now;
                calmSince = -1;
                return Change.STARTED;
            }
            return Change.NONE;
        }
        if (hostile) {
            calmSince = -1;
            return Change.NONE;
        }
        if (calmSince < 0) {
            calmSince = now;
        }
        if (now - calmSince >= minPeace) {
            conflictSince = -1;
            calmSince = -1;
            return end();
        }
        return Change.NONE;
    }

    private Change end() {
        if (!atWar()) {
            return Change.NONE;
        }
        warSince = -1;
        sides.clear();
        return Change.ENDED;
    }

    /** Post-M5 war counsel: the war starts now (the relation is set to open conflict by the caller). */
    public Change declare(long now) {
        conflictSince = now;
        calmSince = -1;
        if (atWar()) {
            return Change.NONE;
        }
        warSince = now;
        return Change.STARTED;
    }

    /** Post-M5 peace (a peace counsel, a finished siege): the war ends now (the relation is raised above open conflict by the caller). */
    public Change makePeace() {
        conflictSince = -1;
        calmSince = -1;
        return end();
    }

    /** Nothing worth keeping (not at war, not counting towards one). */
    public boolean idle() {
        return !atWar() && conflictSince < 0;
    }
}

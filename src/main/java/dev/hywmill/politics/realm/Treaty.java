package dev.hywmill.politics.realm;

import java.util.UUID;

/**
 * An official treaty between two villages (post-M5 realms, docs/realm-design.md §1). Each tier is open while their Millénaire
 * relation stays at or above its threshold, and lapses {@link #LAPSE} points below it (or at war). Pure; persisted at ledger
 * level, one per pair ({@code a < b}).
 */
public final class Treaty {
    public enum Kind {
        /** Neither besieges the other. */
        PACT(25),
        /** Each must relieve the other when besieged. */
        DEFENSIVE(50),
        /** Defensive, and each joins the other's wars and sends a contingent with its sieges. */
        ALLIANCE(75);

        public final int threshold;

        Kind(int threshold) {
            this.threshold = threshold;
        }

        public String label() {
            return switch (this) {
                case PACT -> "non-aggression pact";
                case DEFENSIVE -> "defensive pact";
                case ALLIANCE -> "military alliance";
            };
        }
    }

    public static final int LAPSE = 10;
    /** Villages farther apart than this sign nothing and owe nothing in war. */
    public static final int REACH = 2500;
    /** Chance per day that a pair whose relation qualifies for a higher tier signs it. */
    public static final double SIGN_CHANCE = 0.25;

    public final UUID a;
    public final UUID b;
    public Kind kind;
    public long since;

    public Treaty(UUID x, UUID y, Kind kind, long since) {
        boolean ordered = x.compareTo(y) < 0;
        this.a = ordered ? x : y;
        this.b = ordered ? y : x;
        this.kind = kind;
        this.since = since;
    }

    public static String key(UUID x, UUID y) {
        return x.compareTo(y) < 0 ? x + ">" + y : y + ">" + x;
    }

    public String key() {
        return a + ">" + b;
    }

    public boolean involves(UUID v) {
        return a.equals(v) || b.equals(v);
    }

    public UUID other(UUID v) {
        return a.equals(v) ? b : a;
    }

    /** The highest tier this relation opens (null: none). */
    public static Kind qualifies(int relation) {
        Kind best = null;
        for (Kind k : Kind.values()) {
            if (relation >= k.threshold) {
                best = k;
            }
        }
        return best;
    }

    /** Whether a treaty of this tier lapses at this relation. */
    public static boolean lapses(Kind kind, int relation) {
        return relation < kind.threshold - LAPSE;
    }

    /** The tier a treaty falls back to at this relation (null: it ends). */
    public static Kind standing(Kind kind, int relation) {
        if (!lapses(kind, relation)) {
            return kind;
        }
        Kind lower = null;
        for (Kind k : Kind.values()) {
            if (k.ordinal() < kind.ordinal() && !lapses(k, relation)) {
                lower = k;
            }
        }
        return lower;
    }

    public boolean obligesRelief() {
        return kind != Kind.PACT;
    }

    public boolean military() {
        return kind == Kind.ALLIANCE;
    }
}

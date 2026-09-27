package dev.hywmill.politics;

import java.util.SplittableRandom;

/**
 * Envoy diplomacy (M5-4), pure and seeded: requirements, a logistic success chance from contextual
 * terms, the outcome roll and the relation change. Every number is data ({@link PoliticsTables.DiplomacyRule}).
 * The same mission seed always gives the same outcome for the same context.
 */
public final class DiplomacyOdds {
    private DiplomacyOdds() {}

    public static final int OPEN_CONFLICT = -90;

    /**
     * What the envoy finds on arrival.
     *
     * @param withA          the player's effective standing with the sponsoring village A
     * @param withB          the player's effective standing with the other village B
     * @param relation       A's relation towards B (Millénaire −100..100)
     * @param recentConflict a raid between A and B is planned or under way
     * @param strengthA      A's defending strength (Millénaire); {@code strengthB} likewise
     * @param recentAttempts earlier proposals by this player for the same pair still within the recent window
     */
    public record Context(EnvoyKind kind, Standing withA, Standing withB, int relation, boolean recentConflict, boolean sameCulture,
                          double distance, int strengthA, int strengthB, int recentAttempts) {}

    public enum Outcome { SUCCESS, FAILURE, BACKFIRE }

    /** Why a proposal is refused before an envoy leaves; {@code OK} when it may go. */
    public enum Refusal {
        OK, SAME_VILLAGE, NOT_DISCOVERED, STANDING_TOO_LOW, UNKNOWN_TO_OTHER, NOT_IN_CONFLICT, TRUSTED_BY_TARGET,
        PAIR_COOLDOWN, PLAYER_COOLDOWN, TARGET_COOLDOWN, PENDING_LIMIT, NO_FAVOR, NO_DIPLOMACY_POINT, NO_RELATION
    }

    /** Standing as a number: Stranger 0, Trusted 1, Patron 2, Sworn 3, Unwelcome −1, Outlaw −2. */
    public static int step(Standing s) {
        return s.ordinal() - Standing.STRANGER.ordinal();
    }

    /** The standing each kind needs with the sponsoring village. */
    public static Standing required(EnvoyKind k) {
        return switch (k) {
            case RECONCILE, ENCOURAGE -> Standing.TRUSTED;
            case TRUCE -> Standing.PATRON;
            case SOW_DISCORD -> Standing.SWORN;
        };
    }

    /**
     * Requirements that depend on standing and the villages' state (cooldowns, pending limits, Favor
     * and diplomacy points are checked by the service, which owns those records).
     */
    public static Refusal check(EnvoyKind k, Standing withA, Standing withB, int repWithB, int relation, boolean recentConflict,
                                PoliticsTables.DiplomacyRule r) {
        if (step(withA) < step(required(k))) {
            return Refusal.STANDING_TOO_LOW;
        }
        switch (k) {
            case RECONCILE -> {
                if (repWithB < r.minRepWithOther() || step(withB) < 0) {
                    return Refusal.UNKNOWN_TO_OTHER;
                }
            }
            case TRUCE -> {
                if (repWithB < r.minRepWithOther() || step(withB) < 0) {
                    return Refusal.UNKNOWN_TO_OTHER;
                }
                if (relation > OPEN_CONFLICT && !recentConflict) {
                    return Refusal.NOT_IN_CONFLICT;
                }
            }
            case SOW_DISCORD -> {
                if (step(withB) >= step(Standing.TRUSTED)) {
                    return Refusal.TRUSTED_BY_TARGET;
                }
            }
            case ENCOURAGE -> { }
        }
        return Refusal.OK;
    }

    /** A proposal that makes no sense gets a low chance (and a small reward). */
    public static boolean plausible(Context c) {
        return switch (c.kind()) {
            case RECONCILE -> c.relation() < 50;
            case TRUCE -> c.relation() <= OPEN_CONFLICT || c.recentConflict();
            case ENCOURAGE -> c.relation() >= 10 && c.relation() < 90;
            case SOW_DISCORD -> c.relation() > OPEN_CONFLICT;
        };
    }

    /** Logistic success chance in (0, 1). */
    public static double chance(Context c, PoliticsTables.DiplomacyRule r) {
        boolean discord = c.kind() == EnvoyKind.SOW_DISCORD;
        double z = r.bias(c.kind());
        z += r.standingWeight() * step(c.withA());
        z += r.standingBWeight() * step(c.withB()) * (discord ? -1 : 1);
        z += r.relationWeight() * (c.relation() / 100.0) * (discord ? -1 : 1);
        if (c.recentConflict()) {
            z += r.conflictWeight() * (discord ? -1 : 1);
        }
        if (c.sameCulture()) {
            z += r.cultureWeight() * (discord ? -1 : 1);
        }
        z += r.distanceWeight() * (c.distance() / 1000.0);
        int total = Math.max(1, c.strengthA() + c.strengthB());
        double weaker = (c.strengthB() - c.strengthA()) / (double) total; // > 0: A is the weaker side
        z += r.strengthWeight() * weaker * (discord ? -1 : 1);
        z += r.attemptWeight() * c.recentAttempts();
        if (!plausible(c)) {
            z += r.implausible();
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** Draws of one mission, in a fixed order, from its seed. */
    public record Draws(double outcome, double jitter, double exposure) {
        public static Draws of(long seed) {
            SplittableRandom rnd = new SplittableRandom(seed);
            return new Draws(rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble());
        }
    }

    public static Outcome roll(double chance, Draws d, PoliticsTables.DiplomacyRule r) {
        if (d.outcome() < chance) {
            return Outcome.SUCCESS;
        }
        // the worst failures backfire: the top backfireShare of the failure range
        double failPos = (d.outcome() - chance) / Math.max(1e-9, 1 - chance);
        return failPos >= 1 - r.backfireShare() ? Outcome.BACKFIRE : Outcome.FAILURE;
    }

    /**
     * Relation change on success (magnitude; the service applies the sign). Reconcile helps more when the
     * relation is very bad; an implausible proposal earns only a token change. ±jitter from the seed.
     */
    public static int delta(Context c, Draws d, PoliticsTables.DiplomacyRule r) {
        double base = r.delta(c.kind());
        if (c.kind() == EnvoyKind.RECONCILE && c.relation() < 0) {
            base *= 1 + Math.min(1.0, -c.relation() / 100.0);
        }
        if (!plausible(c)) {
            base = Math.min(base, 2);
        }
        double j = 1 + r.jitter() * (2 * d.jitter() - 1);
        return (int) Math.max(1, Math.round(base * j));
    }

    public static long travelTicks(double distance, PoliticsTables.DiplomacyRule r) {
        return Math.max(r.minTravel(), (long) Math.ceil(distance / 200.0 * r.travelPer200()));
    }

    /** Favor a sow-discord attempt costs after {@code recentAttempts} earlier recent attempts. */
    public static int sowFavorCost(int recentAttempts, PoliticsTables.DiplomacyRule r) {
        return r.sowFavorCost() + r.sowFavorStep() * Math.max(0, recentAttempts);
    }

    public static boolean exposed(int recentAttempts, Draws d, PoliticsTables.DiplomacyRule r) {
        return d.exposure() < Math.min(1.0, r.sowExposure() + r.sowExposureStep() * Math.max(0, recentAttempts));
    }
}

package dev.hywmill.politics;

/**
 * Military requests (M5-5), pure: the village evaluates, it does not grant blindly. It refuses when
 * not calm or while preparing a raid of its own; it offers what it can spare under its own raid rule
 * (never below its home share, never the kept sentry pairs and reserve); its willingness falls with
 * soldiers lost on the player's errands and rises with Favor; it may offer fewer than asked and says why.
 * Favor is paid only on acceptance.
 */
public final class Requests {
    private Requests() {}

    /** Detachments only: player-following escorts are deferred to a later phase (lent soldiers never teleport). */
    public enum Kind { DETACHMENT }

    public enum Refusal { OK, STANDING_TOO_LOW, NOT_CALM, RAID_PREPARING, NOTHING_TO_SPARE, UNWILLING, NO_FAVOR, COOLDOWN, OUT_OF_RANGE, BAD_REQUEST }

    /**
     * @param units     units the village lends (≤ asked)
     * @param favorCost Favor charged on acceptance
     * @param reason    why fewer than asked (or why refused)
     */
    public record Offer(Refusal refusal, int units, int favorCost, String reason) {
        public boolean ok() {
            return refusal == Refusal.OK;
        }
    }

    /**
     * @param standing   the player's effective standing with the village
     * @param spare      units the village's raid-style planner can spare now
     * @param days       detachment length
     * @param distance   detachment point distance from the village centre
     */
    public static Offer evaluate(Kind kind, Standing standing, int asked, int days, double distance, boolean calm, boolean raidPreparing,
                                 int spare, int casualties, int favor, long now, long lastRequest, PoliticsTables.RequestRule r) {
        int limit = r.detachMax(standing);
        if (asked <= 0 || days <= 0 || days > r.detachMaxDays()) {
            return new Offer(Refusal.BAD_REQUEST, 0, 0, asked <= 0 ? "ask for at least one" : "a detachment holds for 1 to " + r.detachMaxDays() + " day(s)");
        }
        if (limit <= 0) {
            return new Offer(Refusal.STANDING_TOO_LOW, 0, 0, "your standing (" + standing + ") does not allow it");
        }
        if (distance > r.detachRadius()) {
            return new Offer(Refusal.OUT_OF_RANGE, 0, 0, "the point is " + (int) distance + " blocks away; at most " + r.detachRadius());
        }
        if (!calm) {
            return new Offer(Refusal.NOT_CALM, 0, 0, "the village is under threat and keeps its soldiers home");
        }
        if (raidPreparing) {
            return new Offer(Refusal.RAID_PREPARING, 0, 0, "the village is preparing a raid of its own");
        }
        if (lastRequest >= 0 && now - lastRequest < r.cooldown()) {
            return new Offer(Refusal.COOLDOWN, 0, 0, "you asked recently; come back in " + (r.cooldown() - (now - lastRequest)) / 20 + " s");
        }
        int willing = (int) Math.floor(limit - casualties * r.casualtyWillingness() + favor / (double) r.favorWillingness());
        willing = Math.max(0, Math.min(limit, willing));
        if (willing <= 0) {
            return new Offer(Refusal.UNWILLING, 0, 0, "too many of our soldiers died on your errands");
        }
        int n = Math.min(asked, Math.min(willing, spare));
        if (n <= 0) {
            return new Offer(Refusal.NOTHING_TO_SPARE, 0, 0, "we cannot spare anyone without leaving the village bare");
        }
        int perUnit = r.detachFavorDay() * days;
        if (perUnit > 0) {
            int affordable = favor / perUnit;
            if (affordable <= 0) {
                return new Offer(Refusal.NO_FAVOR, 0, 0, "that asks " + perUnit + " Favor per soldier; you have " + favor);
            }
            n = Math.min(n, affordable);
        }
        String why = n >= asked ? "" : n == spare ? "we can spare " + n : n == willing ? "we are willing to send " + n
                : "your Favor covers " + n;
        return new Offer(Refusal.OK, n, n * perUnit, why);
    }
}

package dev.hywmill.politics;

/**
 * The amnesty (post-M5): a village that loses a siege submits to the winners, and its terms forgive the players who helped
 * them win. Their grievances with it are wiped (a peacetime killing included, so an outlaw is pardoned), and their combined
 * reputation with it is raised to at least the Trusted line. Pure.
 */
public final class Amnesty {
    private Amnesty() {}

    /** The reputation to grant so that {@code reputation} reaches the Trusted line (0 if it already does). */
    public static int raise(int reputation, PoliticsTables t) {
        return Math.max(0, t.standing().trusted() - reputation);
    }

    /** Applies the amnesty at {@code reputationAfter} (the caller has already raised it) and re-evaluates. Returns the new status. */
    public static Standing apply(PoliticsRecord r, long now, int reputationAfter, PoliticsTables t) {
        r.grievances.reduceTo(now, 0, t.grievance());
        r.refresh(now, reputationAfter, t);
        return r.status;
    }
}

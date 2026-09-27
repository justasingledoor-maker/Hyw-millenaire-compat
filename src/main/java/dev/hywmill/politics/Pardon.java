package dev.hywmill.politics;

/**
 * The formal (paid) pardon of an outlaw (M5-3). Pure. The ordinary pardon needs no request: it
 * happens at the status refresh once the grievance has decayed below {@code pardon} and reputation is
 * above the boycott line ({@link Standing}). The formal pardon shortens the wait: the player pays a
 * weregild in reputation (earned back through Millénaire donations) and the grievance is lowered to
 * just below the pardon line.
 */
public final class Pardon {
    private Pardon() {}

    public enum Outcome { OK, NOT_OUTLAW, DISABLED, TOO_POOR }

    /**
     * @param price      reputation the pardon costs now
     * @param repAfter   reputation after paying
     */
    public record Quote(Outcome outcome, int price, int repAfter, double grievance) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }

    /** The grievance a formal pardon leaves: just below the pardon line. */
    public static double residual(PoliticsTables t) {
        return Math.max(0, t.grievance().pardon() - 1);
    }

    public static Quote quote(PoliticsRecord r, long now, int reputation, PoliticsTables t) {
        double g = r.grievances.decayed(now, t.grievance());
        if (r.status != Standing.OUTLAW) {
            return new Quote(Outcome.NOT_OUTLAW, 0, reputation, g);
        }
        if (!t.pardon().enabled()) {
            return new Quote(Outcome.DISABLED, 0, reputation, g);
        }
        double over = Math.max(0, g - residual(t));
        long price = (long) Math.ceil(over * t.pardon().perGrievance() - 1e-9)
                + (r.grievances.peacetimeKillPending() ? t.pardon().killFee() : 0);
        int p = (int) Math.min(Integer.MAX_VALUE, price);
        long after = (long) reputation - p;
        if (after <= t.standing().boycott()) {
            return new Quote(Outcome.TOO_POOR, p, (int) Math.max(Integer.MIN_VALUE, after), g);
        }
        return new Quote(Outcome.OK, p, (int) after, g);
    }

    /**
     * Applies a paid pardon to the record (the caller has already taken the price from Millénaire's
     * reputation) and re-evaluates the status with the reputation after payment. Returns the new status.
     */
    public static Standing apply(PoliticsRecord r, long now, int reputationAfter, PoliticsTables t) {
        r.grievances.reduceTo(now, residual(t), t.grievance());
        r.refresh(now, reputationAfter, t);
        return r.status;
    }
}

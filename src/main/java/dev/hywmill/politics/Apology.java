package dev.hywmill.politics;

/**
 * An apology (post-M5): a player who is not an outlaw pays Millénaire money to settle their grievance with a village at
 * once, instead of waiting for it to fade. Pure. The price is {@code apologyPerGrievance} deniers per grievance point
 * (rounded up, at least one denier); the grievance goes to zero and the standing is re-evaluated, so an Unwelcome player
 * is back to what their reputation earns. Reputation is not touched. Outlaws are refused (that is the pardon's job).
 */
public final class Apology {
    private Apology() {}

    public enum Outcome { OK, NOTHING_TO_SETTLE, OUTLAW, DISABLED, TOO_POOR }

    /** @param price deniers the apology costs now */
    public record Quote(Outcome outcome, int price, double grievance) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }

    public static Quote quote(PoliticsRecord r, long now, int money, PoliticsTables t) {
        double g = r.grievances.decayed(now, t.grievance());
        if (r.status == Standing.OUTLAW || r.grievances.peacetimeKillPending()) {
            return new Quote(Outcome.OUTLAW, 0, g);
        }
        if (t.pardon().apologyPerGrievance() <= 0) {
            return new Quote(Outcome.DISABLED, 0, g);
        }
        if (g <= 0) {
            return new Quote(Outcome.NOTHING_TO_SETTLE, 0, g);
        }
        int price = (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.ceil(g * t.pardon().apologyPerGrievance() - 1e-9)));
        return new Quote(money >= price ? Outcome.OK : Outcome.TOO_POOR, price, g);
    }

    /** Applies a paid apology (the caller has already taken the money) and re-evaluates the status. Returns the new status. */
    public static Standing apply(PoliticsRecord r, long now, int reputation, PoliticsTables t) {
        r.grievances.reduceTo(now, 0, t.grievance());
        r.refresh(now, reputation, t);
        return r.status;
    }
}

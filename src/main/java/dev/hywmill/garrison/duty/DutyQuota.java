package dev.hywmill.garrison.duty;

/** How many units each standing duty gets (sentries in pairs). */
public record DutyQuota(int sentryPairs, int patrol, int scouts, int reserve) {
    public int total() {
        return 2 * sentryPairs + patrol + scouts + reserve;
    }

    /**
     * Quotas for {@code living} available units with at most {@code posts} sentry posts. The sum
     * never exceeds {@code living}: when it would, scouts are cut first, then patrol, then sentry
     * pairs, then the reserve.
     */
    public static DutyQuota of(DutyRule r, int living, int posts) {
        int pairs = living >= r.minUnitsForSentries() ? Math.min(Math.min(r.maxSentryPairs(), posts), (int) Math.floor(living * r.sentryShare() / 2.0 + 1e-9)) : 0;
        int patrol = living >= r.minUnitsForPatrol() ? Math.min(r.maxPatrol(), (int) Math.floor(living * r.patrolShare() + 1e-9)) : 0;
        int scouts = living >= r.minUnitsForScouts() ? Math.min(r.maxScouts(), (int) Math.floor(living * r.scoutShare() + 1e-9)) : 0;
        int reserve = Math.min(living, Math.max(r.minReserve(), (int) Math.floor(living * r.reserveShare() + 1e-9)));
        pairs = Math.max(0, pairs);
        while (2 * pairs + patrol + scouts + reserve > living) {
            if (scouts > 0) {
                scouts--;
            } else if (patrol > 0) {
                patrol--;
            } else if (pairs > 0) {
                pairs--;
            } else {
                reserve--;
            }
        }
        return new DutyQuota(pairs, patrol, scouts, Math.max(0, reserve));
    }
}

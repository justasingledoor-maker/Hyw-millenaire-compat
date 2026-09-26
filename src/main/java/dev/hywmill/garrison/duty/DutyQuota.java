package dev.hywmill.garrison.duty;

/** How many units each standing duty gets (sentries in pairs). */
public record DutyQuota(int sentryPairs, int patrol, int scouts, int reserve) {
    public int total() {
        return 2 * sentryPairs + patrol + scouts + reserve;
    }

    /**
     * Quotas for {@code living} available units with at most {@code posts} sentry posts. A duty is
     * staffed once the garrison reaches its minimum size (then with at least one unit or pair). The sum
     * never exceeds {@code living}: when it would, scouts are cut first, then patrol, then sentry
     * pairs, then the reserve.
     */
    public static DutyQuota of(DutyRule r, int living, int posts) {
        int pairs = living >= r.minUnitsForSentries() ? Math.min(Math.min(r.maxSentryPairs(), posts), atLeastOne(living * r.sentryShare() / 2.0)) : 0;
        int patrol = living >= r.minUnitsForPatrol() ? Math.min(r.maxPatrol(), atLeastOne(living * r.patrolShare())) : 0;
        int scouts = living >= r.minUnitsForScouts() ? Math.min(r.maxScouts(), atLeastOne(living * r.scoutShare())) : 0;
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

    /** A staffed duty (its minimum garrison reached, share &gt; 0) gets at least one unit (or pair). */
    private static int atLeastOne(double share) {
        return share <= 0 ? 0 : Math.max(1, (int) Math.floor(share + 1e-9));
    }
}

package dev.hywmill.garrison.duty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Which garrison units join the village's own Millénaire raid (M4). Pure and deterministic.
 *
 * <ul>
 *   <li>Only units at home and on standing duty are candidates (the caller passes those); scouts
 *       out on their ride are not.</li>
 *   <li>Size: {@code floor(available * commitFraction)}, at most {@code maxCommit}, and never more
 *       than leaves {@code ceil(available * minHome)} at home; below {@code minCommit} (or with
 *       fewer than {@code minGarrison} available) nobody goes.</li>
 *   <li>Order: GARRISON (roving) duty and reserve beyond {@code keepReserve} first, then patrol,
 *       then whole sentry pairs beyond {@code keepSentryPairs} (highest post index first), then
 *       scouts resting at home. Ties by roster id.</li>
 * </ul>
 */
public final class RaidPlanner {
    private RaidPlanner() {}

    public record Candidate(UUID rosterId, Duty duty, int index) {}

    public static int size(RaidRule r, int available) {
        if (!r.enabled() || available < r.minGarrison()) {
            return 0;
        }
        int want = Math.min(r.maxCommit(), (int) Math.floor(available * r.commitFraction() + 1e-9));
        int home = (int) Math.ceil(available * r.minHome() - 1e-9);
        want = Math.min(want, available - Math.max(1, home));
        return want >= r.minCommit() ? want : 0;
    }

    public static List<UUID> select(RaidRule r, List<Candidate> available) {
        int n = size(r, available.size());
        if (n == 0) {
            return List.of();
        }
        List<Candidate> sorted = new ArrayList<>(available);
        sorted.sort(Comparator.comparing(Candidate::rosterId));
        List<Candidate> order = new ArrayList<>();
        int reserveKept = 0;
        List<Candidate> reserveExcess = new ArrayList<>();
        for (Candidate c : sorted) {
            if (c.duty() == Duty.GARRISON) {
                order.add(c);
            } else if (c.duty() == Duty.RESERVE) {
                if (reserveKept < r.keepReserve()) {
                    reserveKept++;
                } else {
                    reserveExcess.add(c);
                }
            }
        }
        order.addAll(reserveExcess);
        for (Candidate c : sorted) {
            if (c.duty() == Duty.PATROL) {
                order.add(c);
            }
        }
        // sentry pairs, highest post index first; the lowest keepSentryPairs posts (the best ones) stay manned
        TreeMap<Integer, List<Candidate>> pairs = new TreeMap<>(Comparator.reverseOrder());
        for (Candidate c : sorted) {
            if (c.duty() == Duty.SENTRY) {
                pairs.computeIfAbsent(c.index(), k -> new ArrayList<>()).add(c);
            }
        }
        int kept = 0;
        List<Integer> keptPosts = new ArrayList<>();
        for (Map.Entry<Integer, List<Candidate>> e : pairs.descendingMap().entrySet()) {
            if (kept < r.keepSentryPairs()) {
                keptPosts.add(e.getKey());
                kept++;
            }
        }
        for (Map.Entry<Integer, List<Candidate>> e : pairs.entrySet()) {
            if (!keptPosts.contains(e.getKey())) {
                order.addAll(e.getValue());
            }
        }
        for (Candidate c : sorted) {
            if (c.duty() == Duty.SCOUT) {
                order.add(c);
            }
        }
        List<UUID> out = new ArrayList<>();
        for (Candidate c : order) {
            if (out.size() >= n) {
                break;
            }
            out.add(c.rosterId());
        }
        return out;
    }
}

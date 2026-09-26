package dev.hywmill.garrison.duty;

import dev.hywmill.garrison.tables.UnitClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic standing-duty allocation over existing garrison units (no units are created).
 * Pure. Scouts are chosen first, by preference (cavalry, then ranged, then levy), current scouts
 * winning ties, so mounted units take over scouting as soon as the village has them. For the
 * other duties a unit keeps its duty (and post/pair index) while the quota still has room; free
 * places are filled by preference (sentries: ranged and line; reserve: line), ties by rosterId.
 */
public final class DutyAllocator {
    private DutyAllocator() {}

    /** A living, available unit. {@code current} is its present standing duty, {@code index} its post/route index. */
    public record Candidate(UUID rosterId, UnitClass unitClass, Duty current, int index) {}

    public record Assignment(Duty duty, int index) {}

    public static Map<UUID, Assignment> allocate(List<Candidate> units, DutyQuota q) {
        List<Candidate> sorted = new ArrayList<>(units);
        sorted.sort(Comparator.comparing(Candidate::rosterId));
        Map<UUID, Assignment> out = new HashMap<>();
        // 0. scouts: best by preference; a current scout wins ties and keeps its post index
        List<Candidate> byScout = new ArrayList<>(sorted);
        byScout.sort(Comparator.comparingInt(DutyAllocator::scoutRank)
                .thenComparingInt(c -> c.current() == Duty.SCOUT ? 0 : 1).thenComparing(Candidate::rosterId));
        List<Candidate> scoutsChosen = byScout.subList(0, Math.min(q.scouts(), byScout.size()));
        boolean[] scoutIdx = new boolean[q.scouts()];
        List<Candidate> newScouts = new ArrayList<>();
        for (Candidate c : scoutsChosen) {
            if (c.current() == Duty.SCOUT && c.index() >= 0 && c.index() < q.scouts() && !scoutIdx[c.index()]) {
                scoutIdx[c.index()] = true;
                out.put(c.rosterId(), new Assignment(Duty.SCOUT, c.index()));
            } else {
                newScouts.add(c);
            }
        }
        for (Candidate c : newScouts) {
            int i = 0;
            while (scoutIdx[i]) {
                i++;
            }
            scoutIdx[i] = true;
            out.put(c.rosterId(), new Assignment(Duty.SCOUT, i));
        }
        // 1. keep existing assignments within quota (sentry pairs keep their post index)
        int[] pairFill = new int[q.sentryPairs()];
        boolean[] patrolIdx = new boolean[q.patrol()];
        int patrol = 0, reserve = 0;
        for (Candidate c : sorted) {
            if (out.containsKey(c.rosterId())) {
                continue;
            }
            switch (c.current()) {
                case SENTRY -> {
                    if (c.index() >= 0 && c.index() < q.sentryPairs() && pairFill[c.index()] < 2) {
                        pairFill[c.index()]++;
                        out.put(c.rosterId(), new Assignment(Duty.SENTRY, c.index()));
                    }
                }
                case PATROL -> {
                    if (c.index() >= 0 && c.index() < q.patrol() && !patrolIdx[c.index()]) {
                        patrolIdx[c.index()] = true;
                        patrol++;
                        out.put(c.rosterId(), new Assignment(Duty.PATROL, c.index()));
                    }
                }
                case RESERVE -> {
                    if (reserve < q.reserve()) {
                        reserve++;
                        out.put(c.rosterId(), new Assignment(Duty.RESERVE, -1));
                    }
                }
                default -> { }
            }
        }
        // 2. fill the rest by preference
        List<Candidate> free = new ArrayList<>();
        for (Candidate c : sorted) {
            if (!out.containsKey(c.rosterId())) {
                free.add(c);
            }
        }
        for (int p = 0; p < q.sentryPairs(); p++) {
            while (pairFill[p] < 2) {
                Candidate c = take(free, Comparator.comparingInt(DutyAllocator::sentryRank));
                if (c == null) {
                    break;
                }
                pairFill[p]++;
                out.put(c.rosterId(), new Assignment(Duty.SENTRY, p));
            }
        }
        for (int i = 0; i < q.patrol(); i++) {
            if (patrolIdx[i]) {
                continue;
            }
            Candidate c = take(free, Comparator.comparingInt(DutyAllocator::patrolRank));
            if (c == null) {
                break;
            }
            patrolIdx[i] = true;
            out.put(c.rosterId(), new Assignment(Duty.PATROL, i));
        }
        while (reserve < q.reserve()) {
            Candidate c = take(free, Comparator.comparingInt(DutyAllocator::reserveRank));
            if (c == null) {
                break;
            }
            reserve++;
            out.put(c.rosterId(), new Assignment(Duty.RESERVE, -1));
        }
        for (Candidate c : free) {
            out.put(c.rosterId(), new Assignment(Duty.GARRISON, -1));
        }
        return out;
    }

    /** Removes and returns the best candidate by rank, ties by rosterId (free is kept sorted). */
    private static Candidate take(List<Candidate> free, Comparator<Candidate> rank) {
        Candidate best = null;
        for (Candidate c : free) {
            if (best == null || rank.compare(c, best) < 0) {
                best = c;
            }
        }
        if (best != null) {
            free.remove(best);
        }
        return best;
    }

    static int scoutRank(Candidate c) {
        return switch (c.unitClass()) {
            case CAVALRY -> 0;
            case RANGED -> 1;
            case LEVY -> 2;
            default -> 3;
        };
    }

    static int sentryRank(Candidate c) {
        return switch (c.unitClass()) {
            case RANGED -> 0;
            case LINE -> 1;
            case LEVY -> 2;
            default -> 3; // cavalry are kept for scouting
        };
    }

    static int patrolRank(Candidate c) {
        return switch (c.unitClass()) {
            case LEVY, LINE -> 0;
            case RANGED -> 1;
            default -> 2;
        };
    }

    static int reserveRank(Candidate c) {
        return switch (c.unitClass()) {
            case LINE -> 0;
            case LEVY -> 1;
            case RANGED -> 2;
            default -> 3;
        };
    }
}

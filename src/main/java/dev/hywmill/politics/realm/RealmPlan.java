package dev.hywmill.politics.realm;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a realm's council last decided (docs/realm-design.md §10): its stance and the balance of arms behind it, its goals,
 * the men it holds back, the village it covets at peace (and since when), and a short log of its decisions. Mutable;
 * persisted at ledger level; shown on the Realm tab.
 */
public final class RealmPlan {
    public static final int LOG = 8;

    public Strategy.Stance stance = Strategy.Stance.AT_PEACE;
    public double ratio = 1;
    public int men, reserve;
    public final List<Strategy.Goal> goals = new ArrayList<>();
    @Nullable public UUID covets;
    public String claim = "";
    public long covetSince = -1;
    public long lastSession = -1, lastLaunch = -1;
    /** Villages the council courts against a common enemy (their friendship rises each session). */
    public final List<UUID> courting = new ArrayList<>();
    /** Newest first. */
    public final List<String> log = new ArrayList<>();

    public void note(String line) {
        log.add(0, line);
        while (log.size() > LOG) {
            log.remove(log.size() - 1);
        }
    }
}

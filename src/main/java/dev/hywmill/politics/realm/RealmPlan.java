package dev.hywmill.politics.realm;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** 1.7: targets its knights urged it on, until when. */
    public final Map<UUID, Long> urged = new LinkedHashMap<>();
    /** 1.7: its members an enemy council means to take (its scouts say), and that council's capital. Not persisted. */
    public final Map<UUID, UUID> eyed = new LinkedHashMap<>();
    /** 1.7: when it last offered peace to each enemy realm (its capital), and when it last made a demand. */
    public final Map<UUID, Long> offered = new LinkedHashMap<>();
    public long lastDemand = -1;
    /** 1.8: when each weak state at war with it last sued it for peace. Not persisted. */
    public final Map<UUID, Long> pleas = new LinkedHashMap<>();
    /** 1.7: when the leagues against it were last weighed, when it last courted, since when it is at peace. Not persisted. */
    public long lastLeague = -1, lastCourt = -1, peaceSince = -1;
    /** 1.7: enemies it does not pursue now (off its front, or past its few goals). Not persisted. */
    public int farEnemies;

    public void note(String line) {
        log.add(0, line);
        while (log.size() > LOG) {
            log.remove(log.size() - 1);
        }
    }
}

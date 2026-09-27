package dev.hywmill.politics.war;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The HYW relations HywMill's political state wants (M5-5b), pure: a war is HOSTILE between the two
 * village <b>faction</b> identities in both directions; a campaign is HOSTILE between the player and
 * the enemy's faction and FRIENDLY between the player and the ally's faction, both directions.
 * HOSTILE wins over FRIENDLY. Resident identities never appear (Option 1: civilians are never relation
 * targets). Outlawry is projected separately (M5-3) and is not part of this plan.
 */
public final class RelationPlan {
    private RelationPlan() {}

    public record Edge(UUID from, UUID to) {}

    public static final String HOSTILE = "HOSTILE";
    public static final String FRIENDLY = "FRIENDLY";

    /**
     * @param factionOf village id → its faction identity (null: unknown village, skipped)
     */
    public static Map<Edge, String> desired(Collection<WarRecord> wars, Collection<Campaign> campaigns, long now, Function<UUID, UUID> factionOf) {
        Map<Edge, String> out = new LinkedHashMap<>();
        for (WarRecord w : wars) {
            if (!w.atWar()) {
                continue;
            }
            UUID fa = factionOf.apply(w.a);
            UUID fb = factionOf.apply(w.b);
            if (fa != null && fb != null && !fa.equals(fb)) {
                both(out, fa, fb, HOSTILE);
            }
        }
        for (Campaign c : campaigns) {
            if (!c.active(now)) {
                continue;
            }
            UUID enemy = factionOf.apply(c.enemy());
            UUID ally = factionOf.apply(c.ally());
            if (enemy != null) {
                both(out, c.player(), enemy, HOSTILE);
            }
            if (ally != null) {
                both(out, c.player(), ally, FRIENDLY);
            }
        }
        return out;
    }

    private static void both(Map<Edge, String> out, UUID x, UUID y, String type) {
        put(out, new Edge(x, y), type);
        put(out, new Edge(y, x), type);
    }

    private static void put(Map<Edge, String> out, Edge e, String type) {
        String cur = out.get(e);
        if (cur == null || (HOSTILE.equals(type) && !HOSTILE.equals(cur))) {
            out.put(e, type);
        }
    }

    public static boolean wantsHostile(Map<Edge, String> plan, UUID x, UUID y) {
        return HOSTILE.equals(plan.get(new Edge(x, y))) || HOSTILE.equals(plan.get(new Edge(y, x)));
    }
}

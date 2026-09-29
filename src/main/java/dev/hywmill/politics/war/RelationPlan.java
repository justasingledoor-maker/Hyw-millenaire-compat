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
 * HOSTILE wins over FRIENDLY. Post-M5 (user decision: civilians are fair game in war, they respawn): a war also makes each
 * faction HOSTILE with the enemy's <b>resident</b> identity, and a campaign makes the player HOSTILE with the enemy's
 * residents, so soldiers and the player's own troops fight the villagers who attack them. Outlawry is projected separately
 * (M5-3) and is not part of this plan.
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
        return desired(wars, campaigns, now, factionOf, v -> null);
    }

    /** @param residentOf village id → its resident identity (null: none, no civilian edges) */
    public static Map<Edge, String> desired(Collection<WarRecord> wars, Collection<Campaign> campaigns, long now, Function<UUID, UUID> factionOf,
                                            Function<UUID, UUID> residentOf) {
        Map<Edge, String> out = new LinkedHashMap<>();
        for (WarRecord w : wars) {
            if (!w.atWar()) {
                continue;
            }
            UUID fa = factionOf.apply(w.a);
            UUID fb = factionOf.apply(w.b);
            if (fa != null && fb != null && !fa.equals(fb)) {
                both(out, fa, fb, HOSTILE);
                UUID ra = residentOf.apply(w.a), rb = residentOf.apply(w.b);
                if (rb != null) {
                    both(out, fa, rb, HOSTILE);
                }
                if (ra != null) {
                    both(out, fb, ra, HOSTILE);
                }
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
            UUID enemyResidents = residentOf.apply(c.enemy());
            if (enemyResidents != null) {
                both(out, c.player(), enemyResidents, HOSTILE);
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

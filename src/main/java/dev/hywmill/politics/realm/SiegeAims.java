package dev.hywmill.politics.realm;

import java.util.EnumMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The aim of a siege, chosen by the attacker's village when it is launched (docs/realm-design.md §3): a weighted draw from
 * the two villages' situation, stable per siege. Pure.
 */
public final class SiegeAims {
    private SiegeAims() {}

    public enum Aim {
        SUBJUGATE, ANNEX, PUNISH, RAZE;

        public String verb() {
            return switch (this) {
                case SUBJUGATE -> "to make it a vassal";
                case ANNEX -> "to annex it";
                case PUNISH -> "to punish it";
                case RAZE -> "to raze it";
            };
        }
    }

    /**
     * @param attackerTier ordinal of the attacker's military tier (NONE 0 … STRONGHOLD 4); targetTier likewise
     * @param grudge       earlier sieges between the two (either way)
     * @param rebelled     the target once rebelled against the attacker's realm
     * @param provinces    the attacker's provinces now; capacity how many it can hold (1 per tier step)
     * @param razeAllowed  the server allows razing and the target may be razed (not player-controlled)
     * @param realms       the realm system is on (else: always SUBJUGATE)
     */
    public record Facts(int attackerTier, int targetTier, boolean sameCulture, double distance, int grudge, boolean rebelled,
                        int provinces, int capacity, boolean razeAllowed, boolean realms) {}

    public static Map<Aim, Double> weights(Facts f) {
        Map<Aim, Double> w = new EnumMap<>(Aim.class);
        w.put(Aim.SUBJUGATE, 3.0 + (f.distance() > 800 ? 2 : 0) + (f.sameCulture() ? 0 : 1));
        if (!f.realms()) {
            return w;
        }
        double annex = 2 + (f.sameCulture() ? 2 : 0) + (f.distance() < 600 ? 2 : 0) - 2.0 * Math.max(0, f.provinces() + 1 - f.capacity());
        w.put(Aim.ANNEX, f.targetTier() > f.attackerTier() ? 0 : Math.max(0, annex));
        w.put(Aim.PUNISH, 2.0 + f.grudge() + (f.targetTier() > f.attackerTier() ? 1 : 0));
        double raze = 0;
        if (f.razeAllowed() && (f.grudge() >= 2 || f.rebelled())) {
            raze = (1 + f.grudge()) * (f.rebelled() ? 3 : 1) * (f.targetTier() >= 3 ? 0.3 : 1);
        }
        w.put(Aim.RAZE, raze);
        return w;
    }

    public static Aim choose(Facts f, long seed) {
        Map<Aim, Double> w = weights(f);
        double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
        double r = new SplittableRandom(seed).nextDouble() * total;
        for (Map.Entry<Aim, Double> e : w.entrySet()) {
            r -= e.getValue();
            if (r < 0) {
                return e.getKey();
            }
        }
        return Aim.SUBJUGATE;
    }

    /** How many provinces a sovereign of this tier can hold well (1 per tier step: WATCH 1 … STRONGHOLD 4). */
    public static int capacity(int tier) {
        return Math.max(1, tier);
    }
}

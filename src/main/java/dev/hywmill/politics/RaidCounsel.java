package dev.hywmill.politics;

import java.util.Random;

/**
 * Raid counsel (post-M5): a player on campaign with a village suggests that it raid the enemy (pure). The service gathers the
 * facts; this decides whether the suggestion may be made and how likely the village is to agree.
 */
public final class RaidCounsel {
    private RaidCounsel() {}

    public enum Refusal { OK, DISABLED, NOT_AT_WAR, NOT_ON_CAMPAIGN, ALREADY_RAIDING, TARGET_UNDER_ATTACK, NO_RAIDERS, COOLDOWN, NO_DIPLOMACY_POINT }

    /**
     * The facts of one suggestion.
     *
     * @param onCampaign   the player's active campaign is with this village against this target
     * @param raiding      the village is already planning or conducting a raid
     * @param lastCounsel  tick of the player's last counsel to this village (-1: never)
     * @param points       the player's Millénaire diplomacy points with the village (-1: unknown)
     */
    public record Facts(boolean atWar, boolean onCampaign, boolean raiding, boolean targetUnderAttack, int raidingStrength,
                        int defendingStrength, long now, long lastCounsel, int points) {}

    public static Refusal check(Facts f, PoliticsTables.RaidCounselRule r) {
        if (!r.enabled()) {
            return Refusal.DISABLED;
        }
        if (!f.atWar()) {
            return Refusal.NOT_AT_WAR;
        }
        if (!f.onCampaign()) {
            return Refusal.NOT_ON_CAMPAIGN;
        }
        if (f.raiding()) {
            return Refusal.ALREADY_RAIDING;
        }
        if (f.targetUnderAttack()) {
            return Refusal.TARGET_UNDER_ATTACK;
        }
        if (f.raidingStrength() <= 0) {
            return Refusal.NO_RAIDERS;
        }
        if (f.lastCounsel() >= 0 && f.now() - f.lastCounsel() < r.cooldown()) {
            return Refusal.COOLDOWN;
        }
        if (f.points() >= 0 && f.points() < r.pointCost()) {
            return Refusal.NO_DIPLOMACY_POINT;
        }
        return Refusal.OK;
    }

    /** Whether the target is too strong by Millénaire's own rule (defending strength at least twice the raiding strength). */
    public static boolean tooStrong(int raidingStrength, int defendingStrength) {
        return defendingStrength >= 2 * Math.max(0, raidingStrength);
    }

    /** The chance the village agrees: the standing's chance, reduced when the target is too strong, within the floor and ceiling. */
    public static double chance(Standing s, int raidingStrength, int defendingStrength, PoliticsTables.RaidCounselRule r) {
        double c = r.chance(s);
        if (c <= 0) {
            return 0;
        }
        if (tooStrong(raidingStrength, defendingStrength)) {
            c *= r.tooStrongFactor();
        }
        return Math.max(r.minChance(), Math.min(r.maxChance(), c));
    }

    /** The draw for one suggestion (deterministic per seed, in [0, 1)); the village agrees if it is below the chance. */
    public static double draw(long seed) {
        return new Random(seed).nextDouble();
    }

    /** "likely", "uncertain" or "unlikely" (the screen's band, as for envoys). */
    public static String band(double chance) {
        return chance >= 0.66 ? "likely" : chance >= 0.33 ? "uncertain" : "unlikely";
    }
}

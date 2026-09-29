package dev.hywmill.politics;

import java.util.Random;

/**
 * War and peace counsel (post-M5): the pure rules. A Patron or Sworn player suggests that a village declare war on
 * another village, or make peace with an enemy. The village's council decides first; a peace must then be accepted by the
 * enemy, who weighs the armies. See {@link PoliticsTables.WarCounselRule}.
 */
public final class WarCounsel {
    private WarCounsel() {}

    public enum Kind { WAR, PEACE }

    public enum Refusal { OK, DISABLED, SAME_VILLAGE, LONE_BUILDING, ALREADY_AT_WAR, NOT_AT_WAR, TRUCE, STANDING_TOO_LOW, COOLDOWN, NO_DIPLOMACY_POINT }

    /**
     * @param lastCounsel tick of the player's last war or peace counsel with this village (-1: never, or not counted)
     * @param points      the player's Millénaire diplomacy points with the village (-1: not counted)
     */
    public record Facts(Kind kind, boolean atWar, boolean truce, boolean loneBuilding, Standing standing, long now, long lastCounsel, int points) {}

    public static Refusal check(Facts f, PoliticsTables.WarCounselRule r) {
        if (!r.enabled()) {
            return Refusal.DISABLED;
        }
        if (f.loneBuilding()) {
            return Refusal.LONE_BUILDING;
        }
        if (f.kind() == Kind.WAR && f.atWar()) {
            return Refusal.ALREADY_AT_WAR;
        }
        if (f.kind() == Kind.PEACE && !f.atWar()) {
            return Refusal.NOT_AT_WAR;
        }
        if (f.kind() == Kind.WAR && f.truce()) {
            return Refusal.TRUCE;
        }
        if (f.standing().ordinal() < Standing.PATRON.ordinal()) {
            return Refusal.STANDING_TOO_LOW;
        }
        if (f.lastCounsel() >= 0 && f.now() - f.lastCounsel() < r.cooldown()) {
            return Refusal.COOLDOWN;
        }
        int cost = f.kind() == Kind.WAR ? r.warPoints() : r.peacePoints();
        if (f.points() >= 0 && f.points() < cost) {
            return Refusal.NO_DIPLOMACY_POINT;
        }
        return Refusal.OK;
    }

    /** Our share of the strength: ours^e / (ours^e + theirs^e); 0.5 when neither side has any. */
    public static double share(double ours, double theirs, double exponent) {
        double a = Math.pow(Math.max(0, ours), exponent), b = Math.pow(Math.max(0, theirs), exponent);
        return a + b <= 0 ? 0.5 : a / (a + b);
    }

    /**
     * The council's chance to follow the counsel.
     * <ul>
     *   <li>War: the standing's chance × (1 − relation/200), so bad blood (−100) makes it 1.5× as likely and friendship
     *       (+100) half as likely; × 2 × our share of the strength, capped at 1.25 (a village does not start a war it
     *       expects to lose).</li>
     *   <li>Peace: the standing's chance × (1.5 − our share): a village that is winning is less keen to stop.</li>
     * </ul>
     */
    public static double councilChance(Kind kind, Standing standing, int relation, double ours, double theirs, PoliticsTables.WarCounselRule r) {
        double s = share(ours, theirs, r.exponent());
        double c;
        if (kind == Kind.WAR) {
            c = r.warChance(standing) * (1 - Math.max(-100, Math.min(100, relation)) / 200.0) * Math.min(1.25, 2 * s);
        } else {
            c = r.peaceChance(standing) * (1.5 - s);
        }
        return Math.max(r.minChance(), Math.min(r.maxChance(), c));
    }

    /** The enemy accepts a peace with our share of the strength (the stronger we are, the likelier), within enemyMin..enemyMax. */
    public static double enemyAccepts(double ours, double theirs, PoliticsTables.WarCounselRule r) {
        return Math.max(r.enemyMin(), Math.min(r.enemyMax(), share(ours, theirs, r.exponent())));
    }

    public static double draw(long seed) {
        return new Random(seed).nextDouble();
    }
}

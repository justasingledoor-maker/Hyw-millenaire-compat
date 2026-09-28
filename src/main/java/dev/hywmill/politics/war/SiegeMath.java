package dev.hywmill.politics.war;

import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.Standing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** The pure rules of sieges (post-M5; docs/siege-design.md). */
public final class SiegeMath {
    private SiegeMath() {}

    /** A unit's weight in off-screen battles: its recruitment cost, +25% per equipment level. */
    public static double unitStrength(double cost, int equipmentLevel) {
        return Math.max(1, cost) * (1 + 0.25 * Math.max(0, equipmentLevel));
    }

    /** The defense: the target's garrison at home, Millénaire's defenders (weighted) and fortification (at most +50%). */
    public static double defense(double garrison, int millenaireDefending, int fortification, PoliticsTables.SiegeRule r) {
        double fort = 1 + Math.min(0.5, Math.max(0, fortification) / 100.0);
        return (garrison + r.millenaireWeight() * Math.max(0, millenaireDefending)) * fort;
    }

    /** P(the attacker wins an off-screen battle). */
    public static double winChance(double host, double defense, PoliticsTables.SiegeRule r) {
        if (host <= 0) {
            return 0;
        }
        if (defense <= 0) {
            return 1;
        }
        double h = Math.pow(host, r.exponent()), d = Math.pow(defense, r.exponent());
        return h / (h + d);
    }

    public static double draw(long seed) {
        return new Random(seed).nextDouble();
    }

    /** Shares of each side's HYW units killed: {@code [attacker, defender]}. */
    public static double[] losses(boolean attackerWon, double host, double defense, PoliticsTables.SiegeRule r) {
        double win = attackerWon ? host : defense, lose = attackerWon ? defense : host;
        double winnerLoss = win <= 0 ? 0 : Math.min(r.winnerLossMax(), r.winnerLossBase() * lose / win);
        return attackerWon ? new double[]{winnerLoss, r.loserLoss()} : new double[]{r.loserLoss(), winnerLoss};
    }

    /** Which of {@code slots} die for a loss share (rounded to the nearest unit, seeded, stable order). */
    public static List<UUID> casualties(List<UUID> slots, double share, long seed) {
        int n = (int) Math.round(slots.size() * Math.max(0, Math.min(1, share)));
        List<UUID> order = new ArrayList<>(slots);
        Collections.sort(order);
        Collections.shuffle(order, new Random(seed));
        return List.copyOf(order.subList(0, Math.min(n, order.size())));
    }

    /** A watched battle's state: WON, LOST or NONE (still going). At the deadline the side that kept more wins. */
    public static Siege.Outcome battle(int hostAlive, int hostStart, int defendersAlive, int defendersStart, boolean deadline,
                                       PoliticsTables.SiegeRule r) {
        double hostShare = hostStart <= 0 ? 0 : hostAlive / (double) hostStart;
        double defShare = defendersStart <= 0 ? 0 : defendersAlive / (double) defendersStart;
        if (hostAlive <= 0 || hostShare <= r.routFraction()) {
            return Siege.Outcome.LOST;
        }
        if (defendersAlive <= 0 || defShare <= r.breakFraction()) {
            return Siege.Outcome.WON;
        }
        if (deadline) {
            return hostShare > defShare ? Siege.Outcome.WON : Siege.Outcome.LOST;
        }
        return Siege.Outcome.NONE;
    }

    /** March time for a distance. */
    public static long marchTicks(double distance, PoliticsTables.SiegeRule r) {
        long t = Math.round(distance / 100.0 * r.marchPer100());
        return Math.max(r.minMarch(), Math.min(r.maxMarch(), t));
    }

    /** Levy points moved from the loser to the winner for a tribute. */
    public static double levy(int tribute, PoliticsTables.SiegeRule r) {
        return tribute / 4096.0 * r.levyShare();
    }

    /** Each helper's share of the tribute (deniers). */
    public static int helperPay(int tribute, int helpers, PoliticsTables.SiegeRule r) {
        return helpers <= 0 ? 0 : (int) Math.floor(tribute * r.playerShare() / helpers);
    }

    // ------------------------------------------------------------------ counsel and village decisions

    public enum Refusal { OK, DISABLED, NOT_AT_WAR, NOT_ON_CAMPAIGN, STANDING_TOO_LOW, ALREADY_BESIEGING, TARGET_BESIEGED, HOST_TOO_SMALL,
        COOLDOWN, NO_DIPLOMACY_POINT }

    /**
     * @param besieging   the attacker already has a siege under way
     * @param besieged    the target is already besieged
     * @param hostSize    the host the attacker could send now
     * @param lastCounsel the player's last siege counsel to this village (-1: never)
     * @param points      diplomacy points with the attacker (-1: unknown)
     */
    public record Facts(boolean atWar, boolean onCampaign, Standing standing, boolean besieging, boolean besieged, int hostSize, long now,
                        long lastCounsel, int points) {}

    public static Refusal check(Facts f, PoliticsTables.SiegeRule r) {
        if (!r.enabled()) {
            return Refusal.DISABLED;
        }
        if (!f.atWar()) {
            return Refusal.NOT_AT_WAR;
        }
        if (!f.onCampaign()) {
            return Refusal.NOT_ON_CAMPAIGN;
        }
        if (r.counselChance(f.standing()) <= 0) {
            return Refusal.STANDING_TOO_LOW;
        }
        if (f.besieging()) {
            return Refusal.ALREADY_BESIEGING;
        }
        if (f.besieged()) {
            return Refusal.TARGET_BESIEGED;
        }
        if (f.hostSize() < r.minCommit()) {
            return Refusal.HOST_TOO_SMALL;
        }
        if (f.lastCounsel() >= 0 && f.now() - f.lastCounsel() < r.counselCooldown()) {
            return Refusal.COOLDOWN;
        }
        if (f.points() >= 0 && f.points() < r.counselPoints()) {
            return Refusal.NO_DIPLOMACY_POINT;
        }
        return Refusal.OK;
    }

    /** The counsel's chance: by standing, cut when the defense is more than 1.5 times the host. */
    public static double counselChance(Standing s, double host, double defense, PoliticsTables.SiegeRule r) {
        double c = r.counselChance(s);
        if (c > 0 && defense > 1.5 * host) {
            c *= r.tooStrongFactor();
        }
        return Math.max(0, Math.min(1, c));
    }

    /** The chance a village launches on its own at one check ({@code aiDailyChance} spread over a day of checks). */
    public static double aiCheckChance(double host, double defense, PoliticsTables.SiegeRule r) {
        if (!r.enabled() || !r.aiEnabled() || host <= 0 || (defense > 0 && host / defense < r.aiMinRatio())) {
            return 0;
        }
        double checksPerDay = 24000.0 / r.aiInterval();
        return 1 - Math.pow(1 - r.aiDailyChance(), 1 / checksPerDay);
    }
}

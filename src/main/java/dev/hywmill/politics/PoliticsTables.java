package dev.hywmill.politics;

import java.util.EnumMap;
import java.util.Map;

/**
 * Data for the politics model ({@code data/<ns>/hywmill_politics/*.json}, loaded in M5-2). Pure
 * records; {@link #DEFAULTS} are the shipped values, used when no data is loaded.
 */
public record PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor, PardonRule pardon) {

    public PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor) {
        this(standing, grievance, favor, PardonRule.DEFAULT);
    }

    /**
     * Status thresholds on Millénaire's combined reputation (village + culture), reusing its own
     * constants: boycott −1024, hire 4096, friend of the village 8192, one of us 32768.
     *
     * @param keepFactor a status earned by reputation is kept while reputation stays above
     *                   {@code threshold × keepFactor} (slower to lose than to gain)
     */
    public record StandingRule(int boycott, int trusted, int patron, int sworn, int patronFavor, long swornFavorEarned,
                               double keepFactor) {}

    /**
     * @param warning  grievance at or above which the player is unwelcome
     * @param outlaw   a serious grievance: outlawry when combined reputation is also ≤ boycott
     * @param pardon   outlawry ends only below this (and above the boycott line)
     */
    public record GrievanceRule(Map<GrievanceKind, Double> weights, double insideFactor, double selfDefenseFactor,
                                long halfLifeTicks, double warning, double outlaw, double pardon, double max) {
        public double weight(GrievanceKind k) {
            return weights.getOrDefault(k, 0.0);
        }
    }

    public record FavorRule(Map<FavorSource, Integer> amounts, int cap) {
        public int amount(FavorSource s) {
            return amounts.getOrDefault(s, 0);
        }
    }

    /**
     * The formal (paid) pardon (M5-3): weregild paid in reputation the player has earned back through
     * Millénaire donations. The price is {@code perGrievance} reputation per grievance point above the
     * pardon line, plus {@code killFee} while a peacetime killing is pending. It is refused unless
     * reputation stays above the boycott line after paying.
     */
    public record PardonRule(boolean enabled, double perGrievance, int killFee) {
        public static final PardonRule DEFAULT = new PardonRule(true, 32, 1024);
    }

    public static final long DAY = 24000L;

    public static final PoliticsTables DEFAULTS = new PoliticsTables(
            new StandingRule(-1024, 4096, 8192, 32768, 20, 60, 0.9),
            new GrievanceRule(weights(), 1.5, 0.25, 21 * DAY, 20, 80, 20, 400),
            new FavorRule(favorAmounts(), 100));

    private static Map<GrievanceKind, Double> weights() {
        Map<GrievanceKind, Double> m = new EnumMap<>(GrievanceKind.class);
        m.put(GrievanceKind.KILL_RESIDENT, 100.0);
        m.put(GrievanceKind.KILL_GARRISON, 80.0);
        m.put(GrievanceKind.ASSAULT_RESIDENT, 10.0);
        m.put(GrievanceKind.ASSAULT_GARRISON, 6.0);
        m.put(GrievanceKind.ERRAND_ABUSE, 30.0);
        m.put(GrievanceKind.PLOT_EXPOSED, 60.0);
        return m;
    }

    private static Map<FavorSource, Integer> favorAmounts() {
        Map<FavorSource, Integer> m = new EnumMap<>(FavorSource.class);
        m.put(FavorSource.DEFENSE, 3);
        m.put(FavorSource.PRESENT_AT_DEFENSE, 1);
        m.put(FavorSource.REQUESTED_DIPLOMACY, 5);
        m.put(FavorSource.ERRAND_SUCCESS, 2);
        m.put(FavorSource.LONG_STANDING, 1);
        return m;
    }
}

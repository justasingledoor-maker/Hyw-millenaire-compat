package dev.hywmill.politics;

import java.util.EnumMap;
import java.util.Map;

/**
 * Data for the politics model ({@code data/<ns>/hywmill_politics/*.json}, loaded in M5-2). Pure
 * records; {@link #DEFAULTS} are the shipped values, used when no data is loaded.
 */
public record PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor, PardonRule pardon, DiplomacyRule diplomacy,
                             RequestRule requests) {

    public PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor) {
        this(standing, grievance, favor, PardonRule.DEFAULT, DiplomacyRule.DEFAULT, RequestRule.DEFAULT);
    }

    public PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor, PardonRule pardon) {
        this(standing, grievance, favor, pardon, DiplomacyRule.DEFAULT, RequestRule.DEFAULT);
    }

    public PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor, PardonRule pardon, DiplomacyRule diplomacy) {
        this(standing, grievance, favor, pardon, diplomacy, RequestRule.DEFAULT);
    }

    /**
     * Military requests (M5-5): escorts and detachments. Units are lent, never created.
     *
     * @param escortMax        most escort units per standing (Trusted, Patron, Sworn)
     * @param detachMax        most detachment units per standing (Patron, Sworn; Trusted none)
     * @param escortTicks      how long an escort lasts
     * @param detachMaxDays    longest detachment
     * @param detachRadius     a detachment's point must be within this distance of the village
     * @param escortFavor      Favor per escort unit, paid on acceptance
     * @param detachFavorDay   Favor per detachment unit per day, paid on acceptance
     * @param casualtyFavor    Favor lost per soldier killed on the player's errand
     * @param casualtyWillingness willingness lost per casualty on the player's errands (recent memory)
     * @param favorWillingness Favor points per extra unit of willingness
     * @param cooldown         ticks between two granted requests of the same player at the same village
     */
    public record RequestRule(Map<Standing, Integer> escortMax, Map<Standing, Integer> detachMax, long escortTicks, int detachMaxDays,
                              int detachRadius, int escortFavor, int detachFavorDay, int casualtyFavor, double casualtyWillingness,
                              int favorWillingness, long cooldown) {
        public static final RequestRule DEFAULT = new RequestRule(
                standingMap(Standing.TRUSTED, 2, Standing.PATRON, 4, Standing.SWORN, 6),
                standingMap(Standing.PATRON, 4, Standing.SWORN, 8),
                24000, 3, 256, 1, 2, 5, 0.5, 40, 6000);

        public int escortMax(Standing s) {
            return escortMax.getOrDefault(s, 0);
        }

        public int detachMax(Standing s) {
            return detachMax.getOrDefault(s, 0);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Standing, Integer> standingMap(Object... kv) {
        Map<Standing, Integer> m = new EnumMap<>(Standing.class);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((Standing) kv[i], (Integer) kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
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

    /**
     * Envoy diplomacy (M5-4). Success chance is logistic in contextual terms (see {@link DiplomacyOdds});
     * every weight, magnitude, delay and cooldown is data.
     *
     * @param bias             logit bias per proposal kind
     * @param standingWeight   per standing step of the player with the sponsoring village (Stranger = 0)
     * @param standingBWeight  per standing step with the other village
     * @param relationWeight   per 100 points of the sponsor's relation to the other village (inverted for sow discord)
     * @param conflictWeight   a raid between the two is planned or under way
     * @param cultureWeight    same culture
     * @param distanceWeight   per 1000 blocks between the villages
     * @param strengthWeight   the sponsor is the weaker (defending strength) side: keener on peace (inverted for sow discord)
     * @param attemptWeight    per recent proposal for the same pair (diminishing returns)
     * @param implausible      a proposal that makes no sense (reconciling friends)
     * @param delta            relation change on success per kind, Millénaire's own scale; random ±{@code jitter}
     * @param backfireShare    share of failures that backfire
     * @param backfireRep      reputation lost with the other village on a backfire
     * @param travelPer200     envoy travel time (ticks) per 200 blocks; at least {@code minTravel}
     * @param pairCooldown     the same player, same pair: one proposal per this many ticks
     * @param truceTicks       length of a truce
     * @param truceFloor       relation held during a truce (above Millénaire's −90 raid line)
     * @param minRepWithOther  reputation needed with the other village (reconcile, truce)
     * @param sowPlayerCooldown one sow-discord attempt per player per this many ticks
     * @param sowPairCooldown  per (sponsor, target) pair, shared by all players
     * @param sowFavorCost     Favor with the sponsor, plus {@code sowFavorStep} per recent attempt
     * @param sowExposure      chance the plot is exposed, plus {@code sowExposureStep} per recent attempt
     * @param sowRecent        attempts older than this no longer count
     * @param exposedRep       reputation lost with the sponsor when exposed (the target also holds a PLOT_EXPOSED grievance)
     */
    public record DiplomacyRule(Map<EnvoyKind, Double> bias, double standingWeight, double standingBWeight, double relationWeight,
                                double conflictWeight, double cultureWeight, double distanceWeight, double strengthWeight,
                                double attemptWeight, double implausible, Map<EnvoyKind, Integer> delta, double jitter,
                                double backfireShare, int backfireRep, long travelPer200, long minTravel, long pairCooldown,
                                long truceTicks, int truceFloor, int minRepWithOther, long sowPlayerCooldown, long sowPairCooldown,
                                int sowFavorCost, int sowFavorStep, double sowExposure, double sowExposureStep, long sowRecent,
                                int exposedRep) {
        public static final DiplomacyRule DEFAULT = new DiplomacyRule(
                enumMap(EnvoyKind.RECONCILE, 0.0, EnvoyKind.TRUCE, -0.5, EnvoyKind.ENCOURAGE, 0.5, EnvoyKind.SOW_DISCORD, -0.3),
                0.4, 0.2, 1.0, -0.8, 0.5, -0.5, 0.5, -0.5, -2.0,
                enumMap(EnvoyKind.RECONCILE, 10, EnvoyKind.TRUCE, 10, EnvoyKind.ENCOURAGE, 5, EnvoyKind.SOW_DISCORD, 10), 0.2,
                0.3, 256, 1000, 1000, 24000, 7 * 24000L, -85, 0, 7 * 24000L, 3 * 24000L, 10, 5, 0.2, 0.15, 28 * 24000L, 512);

        public double bias(EnvoyKind k) {
            return bias.getOrDefault(k, 0.0);
        }

        public int delta(EnvoyKind k) {
            return delta.getOrDefault(k, 0);
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<EnvoyKind, V> enumMap(Object... kv) {
        Map<EnvoyKind, V> m = new EnumMap<>(EnvoyKind.class);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((EnvoyKind) kv[i], (V) kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
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

package dev.hywmill.politics;

import java.util.EnumMap;
import java.util.Map;

/**
 * Data for the politics model ({@code data/<ns>/hywmill_politics/*.json}, loaded in M5-2). Pure
 * records; {@link #DEFAULTS} are the shipped values, used when no data is loaded.
 */
public record PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor, PardonRule pardon, DiplomacyRule diplomacy,
                             RequestRule requests, RaidCounselRule raidCounsel, SiegeRule siege, ArsenalRule arsenal,
                             WarCounselRule warCounsel, MobilizationRule mobilization, ReliefRule relief) {

    public PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor, PardonRule pardon, DiplomacyRule diplomacy,
                          RequestRule requests, RaidCounselRule raidCounsel, SiegeRule siege, ArsenalRule arsenal,
                          WarCounselRule warCounsel, MobilizationRule mobilization) {
        this(standing, grievance, favor, pardon, diplomacy, requests, raidCounsel, siege, arsenal, warCounsel, mobilization, ReliefRule.DEFAULT);
    }

    public PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor, PardonRule pardon, DiplomacyRule diplomacy,
                          RequestRule requests, RaidCounselRule raidCounsel, SiegeRule siege, ArsenalRule arsenal) {
        this(standing, grievance, favor, pardon, diplomacy, requests, raidCounsel, siege, arsenal, WarCounselRule.DEFAULT, MobilizationRule.DEFAULT);
    }

    public PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor, PardonRule pardon, DiplomacyRule diplomacy,
                          RequestRule requests, RaidCounselRule raidCounsel, SiegeRule siege) {
        this(standing, grievance, favor, pardon, diplomacy, requests, raidCounsel, siege, ArsenalRule.DEFAULT);
    }

    public PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor, PardonRule pardon, DiplomacyRule diplomacy,
                          RequestRule requests) {
        this(standing, grievance, favor, pardon, diplomacy, requests, RaidCounselRule.DEFAULT, SiegeRule.DEFAULT, ArsenalRule.DEFAULT);
    }

    public PoliticsTables(StandingRule standing, GrievanceRule grievance, FavorRule favor, PardonRule pardon, DiplomacyRule diplomacy,
                          RequestRule requests, RaidCounselRule raidCounsel) {
        this(standing, grievance, favor, pardon, diplomacy, requests, raidCounsel, SiegeRule.DEFAULT, ArsenalRule.DEFAULT);
    }

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
     * Military requests (M5-5): detachments. Units are lent, never created. (Player-following escorts are
     * deferred to a later phase; their data keys are reported and ignored.)
     *
     * @param detachMax        most detachment units per standing (Patron, Sworn; Trusted none)
     * @param detachMaxDays    longest detachment
     * @param detachRadius     a detachment's point must be within this distance of the village
     * @param detachFavorDay   Favor per detachment unit per day, paid on acceptance
     * @param casualtyFavor    Favor lost per soldier killed on the player's errand
     * @param casualtyWillingness willingness lost per casualty on the player's errands (recent memory)
     * @param favorWillingness Favor points per extra unit of willingness
     * @param cooldown         ticks between two granted requests of the same player at the same village
     */
    public record RequestRule(Map<Standing, Integer> detachMax, int detachMaxDays, int detachRadius, int detachFavorDay, int casualtyFavor,
                              double casualtyWillingness, int favorWillingness, long cooldown) {
        public static final RequestRule DEFAULT = new RequestRule(standingMap(Standing.PATRON, 4, Standing.SWORN, 8), 3, 256, 2, 5, 0.5, 40, 6000);

        public int detachMax(Standing s) {
            return detachMax.getOrDefault(s, 0);
        }
    }

    /**
     * Raid counsel (post-M5): a player on campaign with a village asks it to raid the enemy. Refused unless the two are at war,
     * the player's active campaign is with this village against that enemy, the village is not already raiding, the target is
     * not under attack and the village has raiders.
     *
     * @param chance          chance the village agrees, per the player's standing with it (absent: never)
     * @param tooStrongFactor chance factor when the target's defending strength is at least twice the village's raiding
     *                        strength (Millénaire's own limit for choosing a raid target)
     * @param minChance       floor of the final chance (when the standing has a chance at all)
     * @param maxChance       ceiling of the final chance
     * @param pointCost       Millénaire diplomacy points with the village, spent whatever the answer
     * @param cooldown        ticks before the same player may counsel the same village again
     */
    public record RaidCounselRule(boolean enabled, Map<Standing, Double> chance, double tooStrongFactor, double minChance, double maxChance,
                                  int pointCost, long cooldown) {
        public static final RaidCounselRule DEFAULT = new RaidCounselRule(true,
                standingDoubles(Standing.TRUSTED, 0.35, Standing.PATRON, 0.55, Standing.SWORN, 0.75), 0.3, 0.05, 0.9, 1, 24000L);

        public double chance(Standing s) {
            return chance.getOrDefault(s, 0.0);
        }
    }

    /**
     * Sieges (post-M5; docs/siege-design.md): HYW-only expeditions of a garrison against a village it is at war with.
     *
     * <p>Host: {@code commitFraction} of the living available garrison, between {@code minCommit} and {@code maxCommit},
     * keeping {@code minHome} of it, {@code keepSentryPairs} sentry pairs and {@code keepReserve} reserve at home; no siege
     * from a garrison below {@code minGarrison}.
     *
     * <p>Timing (ticks): muster, march ({@code marchPer100} per 100 blocks, within {@code minMarch}..{@code maxMarch}),
     * {@code waitTicks} at an unwatched target before the off-screen resolution, {@code battleTicks} at most for a watched
     * battle.
     *
     * <p>Battle: attackers win when the defenders fall to {@code breakFraction} of their start, lose when the host falls to
     * {@code routFraction}. Off-screen: P(win) = H^e/(H^e+D^e) with {@code exponent}; Millénaire's defending strength counts
     * {@code millenaireWeight}; the loser loses {@code loserLoss} of its HYW units, the winner {@code winnerLossBase} ×
     * loser/winner strength, at most {@code winnerLossMax}.
     *
     * <p>Counsel: chance by standing, × {@code tooStrongFactor} when the defense exceeds 1.5 × the host; costs
     * {@code counselPoints} diplomacy points; {@code counselCooldown} per player and attacker. Villages: every
     * {@code aiInterval}, with {@code aiDailyChance} per day when host/defense ≥ {@code aiMinRatio}; {@code aiCooldown}
     * after a village's siege.
     *
     * <p>Outcome: tribute (deniers) by the loser's tier; {@code levyShare} levy points per 4096 deniers move from the loser
     * to the winner; {@code playerShare} of the tribute is paid to the winner's helpers; helpers gain {@code helperRep}
     * reputation and SIEGE_VICTORY Favor.
     */
    public record SiegeRule(boolean enabled, double commitFraction, int minCommit, int maxCommit, double minHome, int keepSentryPairs,
                            int keepReserve, int minGarrison, long musterTicks, long marchPer100, long minMarch, long maxMarch, long waitTicks,
                            long battleTicks, double breakFraction, double routFraction, double millenaireWeight, double exponent,
                            double loserLoss, double winnerLossBase, double winnerLossMax, Map<Standing, Double> counselChance,
                            double tooStrongFactor, int counselPoints, long counselCooldown, boolean aiEnabled, long aiInterval,
                            double aiDailyChance, double aiMinRatio, long aiCooldown, Map<MilitaryTierKey, Integer> tribute,
                            double levyShare, double playerShare, int helperRep) {
        public static final SiegeRule DEFAULT = new SiegeRule(true, 0.5, 6, 64, 0.4, 1, 1, 10,
                1200, 1200, 1200, 12000, 1200, 18000, 0.2, 0.2, 0.3, 2.0, 0.5, 0.35, 0.5,
                standingDoubles(Standing.PATRON, 0.5, Standing.SWORN, 0.75), 0.4, 2, 48000, true, 1200, 0.25, 0.9, 72000,
                tributes(), 0.5, 0.4, 512);

        public double counselChance(Standing s) {
            return counselChance.getOrDefault(s, 0.0);
        }

        public int tribute(MilitaryTierKey tier) {
            return tribute.getOrDefault(tier, 2048);
        }
    }

    /**
     * War arsenal (post-M5): a village at war fields siege engines, each with one engineer, for the length of the war.
     *
     * @param engines         engines granted per village tier when it goes to war (absent: none)
     * @param types           HYW entity ids (path only) the engines are drawn from, in a stable order
     * @param strongholdTypes one engine of a stronghold is drawn from these instead (e.g. a nest of bees)
     * @param engineStrength  an engine's weight in off-screen battles (a soldier weighs its cost, 1 to 4)
     * @param fortCut         share of the target's fortification bonus each attacking engine cancels
     */
    public record ArsenalRule(boolean enabled, Map<MilitaryTierKey, Integer> engines, java.util.List<String> types,
                              java.util.List<String> strongholdTypes, double engineStrength, double fortCut) {
        public static final ArsenalRule DEFAULT = new ArsenalRule(true, engineCounts(), java.util.List.of("mangonels", "springald"),
                java.util.List.of("nest_of_bees"), 8, 0.25);

        public int engines(MilitaryTierKey tier) {
            return engines.getOrDefault(tier, 0);
        }
    }

    /**
     * War and peace counsel (post-M5): a Patron or Sworn player suggests that a village declare war on another, or make
     * peace with an enemy. The village's council decides first: {@code warChance}/{@code peaceChance} by standing, scaled
     * by the relation (war) and the balance of strength, within minChance..maxChance. A peace must then be accepted by the
     * enemy, with P = (our strength)^e / (ours^e + theirs^e) within enemyMin..enemyMax. Each suggestion costs diplomacy
     * points with the village and starts a per-player cooldown. A peace, and every finished siege, sets both relations to
     * {@code peaceRelation} and ends the war at once.
     */
    public record WarCounselRule(boolean enabled, Map<Standing, Double> warChance, Map<Standing, Double> peaceChance, double minChance,
                                 double maxChance, int warPoints, int peacePoints, long cooldown, int peaceRelation, int warRelation,
                                 double enemyMin, double enemyMax, double exponent, boolean peaceAfterSiege) {
        public static final WarCounselRule DEFAULT = new WarCounselRule(true,
                standingDoubles(Standing.PATRON, 0.4, Standing.SWORN, 0.65), standingDoubles(Standing.PATRON, 0.5, Standing.SWORN, 0.75),
                0.05, 0.95, 2, 1, 24000, -75, -100, 0.1, 0.9, 2.0, true);

        public double warChance(Standing s) {
            return warChance.getOrDefault(s, 0.0);
        }

        public double peaceChance(Standing s) {
            return peaceChance.getOrDefault(s, 0.0);
        }
    }

    /**
     * Relief forces (post-M5): when a siege is launched, each village with a relation of at least {@code minRelation} to the
     * besieged village (and at peace with it) sends relief with {@code chance}, at most {@code maxHelpers} per siege. The
     * force is shareMin..shareMax of its garrison at home (a garrison of at least {@code minGarrison}); it sets out when the
     * attackers march and arrives after minTicks..maxTicks. On the way it may be ambushed ({@code ambushChance}: it loses
     * ambushLossMin..ambushLossMax of its soldiers, and routs home with {@code routChance}) or lose its way
     * ({@code lostChance}: lostMin..all of it never arrives, unharmed). It fights until the siege ends, then goes home.
     */
    public record ReliefRule(boolean enabled, int minRelation, double chance, int maxHelpers, int minGarrison, double shareMin, double shareMax,
                             long minTicks, long maxTicks, double ambushChance, double ambushLossMin, double ambushLossMax, double routChance,
                             double lostChance, double lostMin) {
        public static final ReliefRule DEFAULT = new ReliefRule(true, 80, 0.2, 6, 4, 0.05, 0.2, 1200, 2400, 0.12, 0.2, 0.6, 0.4, 0.06, 0.3);
    }

    /**
     * Wartime mobilization (post-M5): a village that is not a stronghold fills its garrison up to its current target when
     * it goes to war, at no levy cost, with fresh troops equipped at max(equipmentFloor, regular level - equipmentDrop).
     * While the war lasts, losses are made good the same way: every {@code reinforceInterval} ticks up to
     * {@code reinforceBatch} more levies while the garrison is below target. Levies are drawn from the village's composition
     * plus {@code levyUnits} (extra weights, allowed below their usual tier: shieldmen and spearmen hold a line well).
     * They serve like any other unit and are sent home when the village is at peace again.
     */
    /**
     * @param rotateInterval in a long war, once the garrison is at strength, one mobilized levy at home is sent home and replaced
     *                       by a paid regular (if the levy points cover it) every this many ticks; 0 = never
     */
    public record MobilizationRule(boolean enabled, int equipmentFloor, int equipmentDrop, long reinforceInterval, int reinforceBatch,
                                   Map<String, Integer> levyUnits, long rotateInterval) {
        public static final long DEFAULT_ROTATE = 2400;
        public static final MobilizationRule DEFAULT = new MobilizationRule(true, 1, 1, 600, 2, defaultLevyUnits(), DEFAULT_ROTATE);

        public MobilizationRule(boolean enabled, int equipmentFloor, int equipmentDrop) {
            this(enabled, equipmentFloor, equipmentDrop, 600, 2, defaultLevyUnits(), DEFAULT_ROTATE);
        }

        public MobilizationRule(boolean enabled, int equipmentFloor, int equipmentDrop, long reinforceInterval, int reinforceBatch,
                                Map<String, Integer> levyUnits) {
            this(enabled, equipmentFloor, equipmentDrop, reinforceInterval, reinforceBatch, levyUnits, DEFAULT_ROTATE);
        }
    }

    private static Map<String, Integer> defaultLevyUnits() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("spear_man", 3);
        m.put("shieldman", 3);
        return java.util.Collections.unmodifiableMap(m);
    }

    private static Map<MilitaryTierKey, Integer> engineCounts() {
        Map<MilitaryTierKey, Integer> m = new EnumMap<>(MilitaryTierKey.class);
        m.put(MilitaryTierKey.WATCH, 1);
        m.put(MilitaryTierKey.GUARD_POST, 2);
        m.put(MilitaryTierKey.GARRISON, 3);
        m.put(MilitaryTierKey.STRONGHOLD, 4);
        return java.util.Collections.unmodifiableMap(m);
    }

    /** A village tier as the siege tribute table names it (mirrors {@code military.MilitaryTier}, kept pure here). */
    public enum MilitaryTierKey { NONE, WATCH, GUARD_POST, GARRISON, STRONGHOLD }

    private static Map<MilitaryTierKey, Integer> tributes() {
        Map<MilitaryTierKey, Integer> m = new EnumMap<>(MilitaryTierKey.class);
        m.put(MilitaryTierKey.WATCH, 32768);
        m.put(MilitaryTierKey.GUARD_POST, 65536);
        m.put(MilitaryTierKey.GARRISON, 163840);
        m.put(MilitaryTierKey.STRONGHOLD, 393216);
        return java.util.Collections.unmodifiableMap(m);
    }

    private static Map<Standing, Double> standingDoubles(Object... kv) {
        Map<Standing, Double> m = new EnumMap<>(Standing.class);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((Standing) kv[i], (Double) kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
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
     *
     * <p>Post-M5: {@code apologyPerGrievance} is the price of an apology (see {@link Apology}) in Millénaire deniers per
     * grievance point; 0 disables apologies.
     */
    public record PardonRule(boolean enabled, double perGrievance, int killFee, double apologyPerGrievance) {
        public static final double APOLOGY_DEFAULT = 64;
        public static final PardonRule DEFAULT = new PardonRule(true, 32, 1024, APOLOGY_DEFAULT);

        public PardonRule(boolean enabled, double perGrievance, int killFee) {
            this(enabled, perGrievance, killFee, APOLOGY_DEFAULT);
        }
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
        m.put(GrievanceKind.JOINED_ENEMY, 30.0);
        return m;
    }

    private static Map<FavorSource, Integer> favorAmounts() {
        Map<FavorSource, Integer> m = new EnumMap<>(FavorSource.class);
        m.put(FavorSource.DEFENSE, 3);
        m.put(FavorSource.PRESENT_AT_DEFENSE, 1);
        m.put(FavorSource.REQUESTED_DIPLOMACY, 5);
        m.put(FavorSource.ERRAND_SUCCESS, 2);
        m.put(FavorSource.LONG_STANDING, 1);
        m.put(FavorSource.SIEGE_VICTORY, 5);
        return m;
    }
}

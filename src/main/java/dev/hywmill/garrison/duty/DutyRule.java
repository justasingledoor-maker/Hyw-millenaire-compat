package dev.hywmill.garrison.duty;

/**
 * Duty allocation rule for one culture and military tier (data: {@code hywmill_duties}). Shares
 * are fractions of the living available garrison; every quota also has a hard maximum and a
 * minimum garrison size below which the duty is not staffed at all.
 */
public record DutyRule(double sentryShare, int maxSentryPairs, int minUnitsForSentries,
                       double patrolShare, int maxPatrol, int minUnitsForPatrol,
                       double scoutShare, int maxScouts, int minUnitsForScouts,
                       double reserveShare, int minReserve) {
    public static final DutyRule NONE = new DutyRule(0, 0, Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0);
}

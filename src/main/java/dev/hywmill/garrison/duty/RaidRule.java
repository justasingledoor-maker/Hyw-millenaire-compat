package dev.hywmill.garrison.duty;

/**
 * How much of a village's garrison joins its own Millénaire raids (M4 data, {@code hywmill_duties}
 * "raid"; independent of the M2 doctrine).
 *
 * @param enabled         whether HYW units join this culture's raids at all
 * @param commitFraction  share of the living available garrison sent
 * @param minCommit       smallest contingent worth sending (below it none is sent)
 * @param maxCommit       largest contingent
 * @param minHome         share of the living available garrison that always stays home
 * @param keepSentryPairs sentry pairs that are never sent
 * @param keepReserve     reserve units that are never sent
 * @param minGarrison     smallest living garrison that sends anyone
 */
public record RaidRule(boolean enabled, double commitFraction, int minCommit, int maxCommit, double minHome, int keepSentryPairs,
                       int keepReserve, int minGarrison) {
    public static final RaidRule DEFAULT = new RaidRule(true, 0.35, 2, 12, 0.5, 1, 1, 6);
    public static final RaidRule OFF = new RaidRule(false, 0, 0, 0, 1, 0, 0, Integer.MAX_VALUE);
}

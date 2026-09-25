package dev.hywmill.garrison;

/**
 * Timing and policy settings of the garrison (from the server config), passed into the pure logic.
 *
 * @param slotInterval ledger interval: one garrison slot per village per interval
 */
public record GarrisonSettings(boolean enabled, int slotInterval, int spawnsPerSlot, int spawnsPerTick, int settleIntervals,
                               long recruitInterval, long deathCooldown, long wipeoutCooldown, long missingGrace, long lostTimeout,
                               long villageGoneGrace, long returnTimeout, OrphanPolicy orphanPolicy, boolean equipmentDrops,
                               long terminalRetention) {
    public enum OrphanPolicy { KEEP, DISCARD }

    public static final GarrisonSettings DEFAULTS = new GarrisonSettings(true, 200, 2, 2, 2, 2400, 1200, 24000, 1200, 72000,
            6000, 1200, OrphanPolicy.KEEP, false, 24000);

    public long settleTicks() {
        return (long) settleIntervals * slotInterval;
    }

    /** Longest gap between two slots still counted as active time (anything longer was inactivity). */
    public long maxActiveStep() {
        return 2L * slotInterval;
    }
}

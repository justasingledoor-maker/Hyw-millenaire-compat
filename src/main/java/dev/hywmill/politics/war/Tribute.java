package dev.hywmill.politics.war;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Tribute (post-M5): the loser of a siege pays the winner the siege's tribute every Minecraft day for 3-5 days (the full
 * amount each day, not split). Each payment moves the tribute's levy points from the loser to the winner and pays each
 * helper of the winning side their share in money, so a campaigning player draws a wage for the days after a victory.
 * Pure and mutable; persisted at ledger level.
 */
public final class Tribute {
    public static final int MIN_DAYS = 3, MAX_DAYS = 5;
    public static final long DAY = 24000;

    public final UUID payer;
    public final UUID payee;
    /** Helpers of the winning side, paid their share of each installment. */
    public final List<UUID> helpers = new ArrayList<>();
    /** The tribute paid each day (deniers), the levy points it moves each day, and each helper's share each day (deniers). */
    public final int total;
    public final double levy;
    public final int helperPay;
    public final int days;
    public int paid;
    public long nextTick;

    public Tribute(UUID payer, UUID payee, int total, double levy, int helperPay, int days, long nextTick) {
        this.payer = payer;
        this.payee = payee;
        this.total = total;
        this.levy = levy;
        this.helperPay = helperPay;
        this.days = Math.max(1, days);
        this.nextTick = nextTick;
    }

    /** How many days a tribute is paid over, drawn from the siege's seed: {@link #MIN_DAYS}-{@link #MAX_DAYS}. */
    public static int days(long seed) {
        return MIN_DAYS + new SplittableRandom(seed ^ 0x7472696275L).nextInt(MAX_DAYS - MIN_DAYS + 1);
    }

    /** Everything paid over all the days (deniers). */
    public static long whole(int perDay, int days) {
        return (long) perDay * Math.max(1, days);
    }

    public boolean done() {
        return paid >= days;
    }

    /** Each day's money for one helper (deniers): the full share, every day. */
    public int helperInstallment() {
        return helperPay;
    }

    /** Each day's tribute (deniers): the full amount, every day. */
    public int installment() {
        return total;
    }
}

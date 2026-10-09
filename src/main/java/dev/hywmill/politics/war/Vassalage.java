package dev.hywmill.politics.war;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Vassalage (post-M5): the loser of a siege becomes the winner's vassal for {@link #DAYS} Minecraft days. The two are allies
 * for that time and neutral again after. When its overlord besieges, or is besieged, the vassal usually ({@link #HELP_CHANCE})
 * sends what it can scrape together: {@link #MIN}-{@link #MAX} soldiers of mixed quality (about half levies, half regulars),
 * arriving with the mercenaries, a minute before the assault. Pure; persisted at ledger level.
 */
public final class Vassalage {
    public static final int DAYS = 21;
    public static final double HELP_CHANCE = 0.75;
    public static final int MIN = 25, MAX = 38;
    /** Relation while sworn (allies), and after (neutral). */
    public static final int ALLIED = 90, NEUTRAL = 0;

    public final UUID vassal;
    public final UUID overlord;
    public final long since;
    public final long until;
    /**
     * Post-M5 realms (docs/realm-design.md §4-5): a province (conquered: its army is the overlord's) rather than a vassal (its
     * own army); its loyalty (0-100); the last day loyalty was updated.
     */
    public boolean province;
    public double loyalty = dev.hywmill.politics.realm.Loyalty.START_VASSAL;
    public long loyaltyDay = -1;

    public Vassalage(UUID vassal, UUID overlord, long since, long until) {
        this.vassal = vassal;
        this.overlord = overlord;
        this.since = since;
        this.until = until;
    }

    /** The legacy 21-day fealty (realms off). */
    public static Vassalage sworn(UUID vassal, UUID overlord, long now) {
        return new Vassalage(vassal, overlord, now, now + DAYS * Tribute.DAY);
    }

    /** Post-M5 realms: a subject tie with no end but rebellion; a province, or a vassal. */
    public static Vassalage subject(UUID subject, UUID sovereign, long now, boolean province) {
        Vassalage v = new Vassalage(subject, sovereign, now, Long.MAX_VALUE);
        v.province = province;
        v.loyalty = province ? dev.hywmill.politics.realm.Loyalty.START_PROVINCE : dev.hywmill.politics.realm.Loyalty.START_VASSAL;
        v.loyaltyDay = now / Tribute.DAY;
        return v;
    }

    public boolean indefinite() {
        return until == Long.MAX_VALUE;
    }

    /** "the province of X (loyalty 52)" / "the vassal of X (3 day(s) left)". */
    public String describe(String overlordName, long now) {
        return "the " + kindLabel() + " of " + overlordName + " (" + (indefinite() ? "loyalty " + Math.round(loyalty) : daysLeft(now) + " day(s) left") + ")";
    }

    public String kindLabel() {
        return province ? "province" : "vassal";
    }

    public boolean over(long now) {
        return now >= until;
    }

    /** Days left (rounded up; a subject tie with no end: Long.MAX_VALUE). */
    public long daysLeft(long now) {
        if (indefinite()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, (until - now + Tribute.DAY - 1) / Tribute.DAY);
    }

    /** What the vassal sends to one siege: nothing (25%), or 25-38 soldiers, each a regular (true) or a levy (false). */
    public static List<Boolean> levy(long seed) {
        SplittableRandom r = new SplittableRandom(seed ^ 0x76617373L);
        List<Boolean> out = new ArrayList<>();
        if (r.nextDouble() >= HELP_CHANCE) {
            return out;
        }
        int n = MIN + r.nextInt(MAX - MIN + 1);
        for (int i = 0; i < n; i++) {
            out.add(r.nextBoolean());
        }
        return out;
    }
}

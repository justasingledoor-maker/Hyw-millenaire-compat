package dev.hywmill.politics.realm;

/**
 * Realm unity (docs/realm-design.md §8): a realm (a capital and its vassals and provinces) has one diplomacy and one war.
 * Its members are friends; a hatred any of them holds for an outsider is the whole realm's; a war on any of them is a war of
 * the whole realm with the whole of the other side's realm. When it goes to war, every vassal is called: provinces always
 * come. Pure.
 */
public final class RealmCall {
    private RealmCall() {}

    /** A relation at or below this is hatred: it spreads through a realm, and a vassal will not march beside a member it hates. */
    public static final int HATE = -50;
    /** The members' relation with each other: set when a village enters the realm, and kept for provinces. */
    public static final int FRIENDLY = 90;
    /** A vassal this loyal or less uses a call to war to break away. */
    public static final double BREAK_BELOW = 20;
    /** A vassal less loyal than this will not fight a village it counts a friend ({@link #FRIENDS_WITH_ENEMY} or better). */
    public static final double FRIENDS_LOYALTY = 40;
    public static final int FRIENDS_WITH_ENEMY = 75;
    /** The council reconciles a feud by decree when the vassal is this loyal and the rest of the realm at least as strong. */
    public static final double RECONCILE_LOYALTY = 35;
    /** Otherwise, at a call to war, it excuses a vassal this loyal for scutage. */
    public static final double EXCUSE_LOYALTY = 20;
    /** Loyalty lost when the council orders a feud ended, and when a vassal buys its way out of a war. */
    public static final double DECREE_LOYALTY = -10, SCUTAGE_LOYALTY = -5;
    /** Scutage: deniers per soldier the vassal keeps home, paid to the capital each day for {@link #SCUTAGE_DAYS} days. */
    public static final int SCUTAGE_PER_SOLDIER = 64, SCUTAGE_DAYS = 3;

    /**
     * JOIN: it marches with the realm. BREAK: it refuses, and is free of the realm and at war with it. RECONCILE: the council
     * ends its feud by decree and it marches. EXCUSE: it stays out of this war and pays scutage. CAST_OUT: the council casts
     * it out: free, and at war with the realm.
     */
    public enum Answer { JOIN, BREAK, RECONCILE, EXCUSE, CAST_OUT }

    /**
     * A vassal's answer to the realm's call to war.
     *
     * @param hatesComrade  it holds another member of the realm at {@link #HATE} or worse
     * @param bestWithEnemy its best relation with a village of the enemy's realm
     * @param own           its soldiers
     * @param rest          the soldiers of the rest of its realm
     */
    public static Answer call(double loyalty, boolean hatesComrade, int bestWithEnemy, int own, int rest) {
        if (hatesComrade) {
            return council(loyalty, own, rest, true);
        }
        if (loyalty <= BREAK_BELOW) {
            return Answer.BREAK;
        }
        if (loyalty < FRIENDS_LOYALTY && bestWithEnemy >= FRIENDS_WITH_ENEMY) {
            return Answer.BREAK;
        }
        return Answer.JOIN;
    }

    /**
     * The capital's council on a vassal at feud with a fellow member: it ends the feud by decree when it can make it stick (a
     * loyal enough vassal, the rest of the realm at least as strong); else, when the realm is going to war, it lets the vassal
     * stay home for scutage; else it casts it out.
     */
    public static Answer council(double loyalty, int own, int rest, boolean warCall) {
        if (loyalty >= RECONCILE_LOYALTY && rest >= own) {
            return Answer.RECONCILE;
        }
        if (warCall && loyalty >= EXCUSE_LOYALTY) {
            return Answer.EXCUSE;
        }
        return Answer.CAST_OUT;
    }

    /** Of two vassals at feud, the council holds the less loyal to account (a tie: the first). */
    public static boolean blameFirst(double loyaltyFirst, double loyaltySecond) {
        return loyaltyFirst <= loyaltySecond;
    }

    /** The realm's feeling for an outsider: its members' worst, when that is hatred; else null (nothing to share). */
    public static Integer shared(int... relations) {
        Integer worst = null;
        for (int r : relations) {
            worst = worst == null ? r : Math.min(worst, r);
        }
        return worst != null && worst <= HATE ? worst : null;
    }

    /** Scutage due each day (deniers) for a vassal keeping {@code soldiers} home. */
    public static int scutage(int soldiers) {
        return SCUTAGE_PER_SOLDIER * Math.max(1, soldiers);
    }
}

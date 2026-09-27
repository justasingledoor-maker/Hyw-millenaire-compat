package dev.hywmill.politics;

/** Political capital with one village: current points (capped) and the total ever earned (for Sworn). */
public final class Favor {
    private int points;
    private long earnedTotal;

    /** Earns up to {@code amount} (bounded by the cap); returns the points actually added. */
    public int earn(FavorSource source, PoliticsTables.FavorRule rule) {
        int amount = rule.amount(source);
        int add = Math.max(0, Math.min(amount, rule.cap() - points));
        points += add;
        earnedTotal += add;
        return add;
    }

    /** Spends {@code cost} if available. */
    public boolean spend(int cost) {
        if (cost < 0 || points < cost) {
            return false;
        }
        points -= cost;
        return true;
    }

    /** Loses points without going negative (casualties on errands); the earned total is unchanged. */
    public int lose(int amount) {
        int l = Math.min(points, Math.max(0, amount));
        points -= l;
        return l;
    }

    public int points() {
        return points;
    }

    public long earnedTotal() {
        return earnedTotal;
    }

    public boolean isEmpty() {
        return points == 0 && earnedTotal == 0;
    }

    public static Favor restore(int points, long earnedTotal) {
        Favor f = new Favor();
        f.points = Math.max(0, points);
        f.earnedTotal = Math.max(f.points, earnedTotal);
        return f;
    }
}

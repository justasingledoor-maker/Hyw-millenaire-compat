package dev.hywmill.politics;

/**
 * A player's decaying grievance with one village: one number with a half-life, plus the context of
 * the last offence, plus the approved "killing inside the village in peacetime" flag, which keeps the
 * player an outlaw until pardoned regardless of the decayed value.
 */
public final class Grievances {
    private double value;
    private long lastTick;
    private GrievanceKind lastKind;
    private boolean lastInside;
    private boolean lastPeacetime;
    private boolean lastSelfDefense;
    private int lastX;
    private int lastY;
    private int lastZ;
    /** Tick of the last peacetime killing inside the village; -1 if none pending (cleared by a pardon). */
    private long peacetimeKillTick = -1;

    /** Adds one offence; returns the severity added. */
    public double add(GrievanceEvent e, PoliticsTables.GrievanceRule rule) {
        value = decayed(e.tick(), rule);
        double w = rule.weight(e.kind());
        if (e.selfDefense()) {
            w *= rule.selfDefenseFactor();
        }
        if (e.insideVillage()) {
            w *= rule.insideFactor();
        }
        value = Math.min(rule.max(), value + w);
        lastTick = e.tick();
        lastKind = e.kind();
        lastInside = e.insideVillage();
        lastPeacetime = e.peacetime();
        lastSelfDefense = e.selfDefense();
        lastX = e.x();
        lastY = e.y();
        lastZ = e.z();
        if (e.immediateOutlaw()) {
            peacetimeKillTick = e.tick();
        }
        return w;
    }

    /** The value decayed to {@code now} (half-life from the tables); never negative. */
    public double decayed(long now, PoliticsTables.GrievanceRule rule) {
        if (value <= 0) {
            return 0;
        }
        long dt = Math.max(0, now - lastTick);
        double v = value * Math.pow(0.5, dt / (double) rule.halfLifeTicks());
        return v < 1e-6 ? 0 : v;
    }

    public boolean peacetimeKillPending() {
        return peacetimeKillTick >= 0;
    }

    /** Called when the player is pardoned (leaves outlawry). */
    public void clearPeacetimeKill() {
        peacetimeKillTick = -1;
    }

    /** A formal pardon: the grievance is settled at {@code now} and lowered to at most {@code target}; the peacetime-killing flag is cleared. */
    public void reduceTo(long now, double target, PoliticsTables.GrievanceRule rule) {
        settle(now, rule);
        value = Math.max(0, Math.min(value, target));
        peacetimeKillTick = -1;
    }

    /** Folds the decay into the stored value at {@code now} (keeps numbers small in saves). */
    public void settle(long now, PoliticsTables.GrievanceRule rule) {
        value = decayed(now, rule);
        if (value > 0) {
            lastTick = now;
        }
    }

    public boolean isEmpty() {
        return value <= 0 && peacetimeKillTick < 0;
    }

    // ---- persistence (codec in the settlement package) ----
    public double value() { return value; }
    public long lastTick() { return lastTick; }
    public GrievanceKind lastKind() { return lastKind; }
    public boolean lastInside() { return lastInside; }
    public boolean lastPeacetime() { return lastPeacetime; }
    public boolean lastSelfDefense() { return lastSelfDefense; }
    public int lastX() { return lastX; }
    public int lastY() { return lastY; }
    public int lastZ() { return lastZ; }
    public long peacetimeKillTick() { return peacetimeKillTick; }

    public static Grievances restore(double value, long lastTick, GrievanceKind lastKind, boolean inside, boolean peacetime,
                                     boolean selfDefense, int x, int y, int z, long peacetimeKillTick) {
        Grievances g = new Grievances();
        g.value = Math.max(0, value);
        g.lastTick = lastTick;
        g.lastKind = lastKind;
        g.lastInside = inside;
        g.lastPeacetime = peacetime;
        g.lastSelfDefense = selfDefense;
        g.lastX = x;
        g.lastY = y;
        g.lastZ = z;
        g.peacetimeKillTick = peacetimeKillTick;
        return g;
    }
}

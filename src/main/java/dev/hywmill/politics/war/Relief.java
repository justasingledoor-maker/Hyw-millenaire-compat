package dev.hywmill.politics.war;

import dev.hywmill.politics.PoliticsTables;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * A relief force (post-M5): a village on great terms with a besieged village sends a small part of its garrison to help
 * defend it. Pure and mutable; kept on its {@link Siege} and persisted with it. The force's slots stay in the helper's
 * roster (duty SIEGE, DEPLOYED); they are stowed while marching.
 */
public final class Relief {
    /** PENDING: waiting for the attackers to march. MARCH: on the way (stowed). PRESENT: at the target. RETURN: going home. */
    public enum Phase { PENDING, MARCH, PRESENT, RETURN, DONE }

    /** What happened on the way. */
    public enum Fate { NONE, CLEAN, AMBUSHED, ROUTED, STRAGGLED, LOST }

    public final UUID helper;
    public Phase phase = Phase.PENDING;
    public long phaseEnd;
    public Fate fate = Fate.NONE;
    /** Soldiers with the force (at the target, or marching there or home). */
    public final List<UUID> units = new ArrayList<>();
    /** Soldiers who did not arrive (lost their way, or routed) and are on their way home. */
    public final List<UUID> strays = new ArrayList<>();
    public int sent;
    /** Post-M5: the helper was reached by the besieged's messenger (only then does it set out); quick sieges call at once. */
    public boolean called = true;
    public int killed;

    public Relief(UUID helper) {
        this.helper = helper;
    }

    public boolean active() {
        return phase == Phase.MARCH || phase == Phase.PRESENT;
    }

    // ------------------------------------------------------------------ pure rules

    /** Whether a village on these terms may send relief: great relations, at peace with the besieged, not a party to it. */
    public static boolean eligible(int relation, boolean atWarWithTarget, boolean isParty, boolean loneBuilding, int available,
                                   PoliticsTables.ReliefRule r) {
        return r.enabled() && !isParty && !loneBuilding && !atWarWithTarget && relation >= r.minRelation() && available >= r.minGarrison();
    }

    /**
     * The force's size: shareMin..shareMax of the garrison at home (drawn), but at least {@code minForce} soldiers (post-M5;
     * a force of one is no relief) as long as that leaves the helper half its garrison; at least one soldier.
     */
    public static int size(int available, double draw, PoliticsTables.ReliefRule r) {
        if (available <= 0) {
            return 0;
        }
        double share = r.shareMin() + (r.shareMax() - r.shareMin()) * Math.max(0, Math.min(1, draw));
        int floor = Math.min(r.minForce(), Math.max(1, available / 2));
        return Math.max(Math.max(1, floor), Math.min(available, (int) Math.round(available * share)));
    }

    /** Travel time: minTicks..maxTicks (a forced march), drawn. */
    public static long travel(double draw, PoliticsTables.ReliefRule r) {
        return r.minTicks() + Math.round((r.maxTicks() - r.minTicks()) * Math.max(0, Math.min(1, draw)));
    }

    /**
     * The journey of a force of {@code n}: {@code killed} die in an ambush (a real loss to the helper's garrison);
     * {@code strays} do not arrive but go home unharmed (routed after an ambush, or lost their way).
     */
    public record Journey(Fate fate, int killed, int strays) {
        public int arrive(int n) {
            return Math.max(0, n - killed - strays);
        }
    }

    public static Journey journey(int n, long seed, PoliticsTables.ReliefRule r) {
        SplittableRandom rnd = new SplittableRandom(seed); // mixes nearby seeds well (java.util.Random's first draw does not)
        double u = rnd.nextDouble();
        if (u < r.ambushChance()) {
            double loss = r.ambushLossMin() + (r.ambushLossMax() - r.ambushLossMin()) * rnd.nextDouble();
            int killed = Math.min(n, (int) Math.round(n * loss));
            if (rnd.nextDouble() < r.routChance() || killed >= n) {
                return new Journey(Fate.ROUTED, killed, n - killed);
            }
            return new Journey(Fate.AMBUSHED, killed, 0);
        }
        if (u < r.ambushChance() + r.lostChance()) {
            double share = r.lostMin() + (1 - r.lostMin()) * rnd.nextDouble();
            int strays = Math.min(n, Math.max(1, (int) Math.round(n * share)));
            return new Journey(strays >= n ? Fate.LOST : Fate.STRAGGLED, 0, strays);
        }
        return new Journey(Fate.CLEAN, 0, 0);
    }

    /** A spot on the ring round the village for soldier {@code i} of {@code n}, as an {x, z} offset from its centre. */
    public static int[] post(int i, int n, int villageRadius, long seed) {
        double ring = Math.max(12, Math.min(40, villageRadius * 0.5));
        double base = Math.floorMod(seed, 360L);
        double angle = Math.toRadians(base + 360.0 * i / Math.max(1, n));
        return new int[]{(int) Math.round(Math.cos(angle) * ring), (int) Math.round(Math.sin(angle) * ring)};
    }
}

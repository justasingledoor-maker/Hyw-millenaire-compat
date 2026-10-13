package dev.hywmill.politics.war;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A player's own assault on a village (1.9, docs/siege-design.md "A player's assault"): he names the village, the side his
 * men gather on (one of eight points of the compass), how long until they go in, and whether he asks his side for help.
 * His own men (the soldiers he hired) gathered at the rally point when the time comes are his host, with any men his side
 * lent him; the village, seeing them gather, presses levies into service. It is decided by morale ({@link Morale}) like any
 * siege: an assault thrown back regroups at the rally point and goes in again; the village yields when its defenders have
 * lost their resolve; the player's band gives up when it has lost its campaign's worth. Pure and mutable; persisted at
 * ledger level.
 */
public final class Assault {
    public enum Phase { GATHER, FIGHT, REGROUP, DONE }

    public enum Outcome { NONE, WON, LOST, CALLED_OFF }

    /** The rally points, from east round by the south (in Minecraft south is +z). */
    public static final String[] SIDES = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
    public static final String[] SIDE_NAMES = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
    public static final int[] MINUTES = {2, 5, 10, 15, 20};
    /** The rally point stands this far outside the village's edge (blocks). */
    public static final int RALLY_OUT = 40;
    /** His men count when within this far of the rally point at the hour (blocks). */
    public static final int GATHER_REACH = 48;
    /** A band regroups this long after an assault is thrown back (ticks: 90 s). */
    public static final long REGROUP = 1800;
    /** The fighting lasts at most this long (ticks: 15 min); then the defenders have held. */
    public static final long FIGHT_MAX = 18000;
    /** His men fall wounded (carried back to the rally point, out of the fight) this often instead of dying. */
    public static final double WOUND = 0.35;
    /** The fewest men of his own a player needs to lead an assault. */
    public static final int MIN_MEN = 2;

    public final UUID id, player, target;
    public final int side;
    public final boolean help;
    public final long launched, assaultAt;
    public int rallyX, rallyY, rallyZ;
    public Phase phase = Phase.GATHER;
    public Outcome outcome = Outcome.NONE;
    public long phaseSince;
    /** His own men in it (entity ids), and the roster ids of the men his side lent him (of {@code helper}). */
    public final List<UUID> host = new ArrayList<>(), lent = new ArrayList<>();
    @Nullable public UUID helper;
    /** The village's levies pressed into service against him (its roster ids). */
    public final List<UUID> levies = new ArrayList<>();
    public int hostStart, hostFallen, assault, assaultEngaged, assaultFallen, defStart, defFallen, merit;
    public long assaultSince = -1, lastSeen;
    public double hostResolve = Morale.Grade.REGULAR.resolve, defResolve = Morale.Grade.REGULAR.resolve;
    public boolean noticed;
    public final List<String> notes = new ArrayList<>();

    public Assault(UUID id, UUID player, UUID target, int side, boolean help, long launched, long assaultAt) {
        this.id = id;
        this.player = player;
        this.target = target;
        this.side = Math.floorMod(side, SIDES.length);
        this.help = help;
        this.launched = launched;
        this.assaultAt = assaultAt;
        this.phaseSince = launched;
        this.lastSeen = launched;
    }

    /** The rally point's direction from the village (unit vector x, z). */
    public static double[] direction(int side) {
        double a = Math.toRadians(45.0 * Math.floorMod(side, SIDES.length));
        return new double[]{Math.cos(a), Math.sin(a)};
    }

    /** The choices of the Politics screen, packed in one number: side, minutes' index, help. */
    public static int pack(int side, int minutesIndex, boolean help) {
        return Math.floorMod(side, SIDES.length) | Math.floorMod(minutesIndex, MINUTES.length) << 3 | (help ? 1 : 0) << 6;
    }

    public static int side(int packed) {
        return packed & 7;
    }

    public static int minutes(int packed) {
        return MINUTES[Math.min(MINUTES.length - 1, packed >> 3 & 7)];
    }

    public static boolean help(int packed) {
        return (packed >> 6 & 1) != 0;
    }

    public boolean active() {
        return phase != Phase.DONE;
    }

    public String sideName() {
        return SIDE_NAMES[side];
    }
}

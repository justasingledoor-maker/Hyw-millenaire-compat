package dev.hywmill.politics.war;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A column on the road (post-M5): anything that travels between villages in a war and can be caught on the way. Mercenary
 * companies riding to the side that hired them, a vassal's men answering its overlord, messengers (a call for help to an ally,
 * an alarm to a besieged village), and supply convoys (camped for the night, standing still). Off-screen a column is numbers:
 * its position follows its road by time. Near a player it becomes real soldiers ({@code materialized}), who fight; killed to the
 * last, the column never arrives. Most columns are unknown until a scout finds them ({@code revealedBy}); then a player may
 * take the job of intercepting it, or the village council decides. Pure and mutable; persisted at ledger level.
 */
public final class Column {
    public enum Kind {
        /** A mercenary company riding to the village that hired it. */
        MERCS,
        /** A vassal's men answering its overlord. */
        VASSAL,
        /** A messenger calling an ally (or a vassal) to help. */
        MESSENGER,
        /** Scouts riding home with the news that an army marches: the besieged open their coffers. */
        ALARM,
        /** A supply convoy, camped for the night. */
        CONVOY,
        /**
         * 1.10: a supply train on the road to a siege: carts and their guards bringing food and kit to the host's camp, or to the
         * besieged before the host arrives. Arrived, it stiffens its side; cut, it weakens it (and its carts are loot).
         */
        SUPPLY
    }

    public enum State { ROAD, ARRIVED, DESTROYED, FAILED, EXPIRED }

    public final UUID id;
    public final Kind kind;
    /** The siege it belongs to (null: a convoy, any time in a war). */
    @Nullable public final UUID siege;
    /** The village whose banner its men march under (their faction), and the village it rides to (null: a convoy). */
    public UUID owner;
    @Nullable public UUID destination;
    public final int fromX, fromZ;
    public int toX, toZ;
    public long depart, arrive;
    /** Its men (unit keys), and for each whether a regular (else a levy). */
    public final List<String> units = new ArrayList<>();
    public final List<Boolean> regular = new ArrayList<>();
    /** A mercenary company's look and name; a convoy's theme. */
    public String look = "", name = "", theme = "";
    /** For a messenger: the village it calls (the helper). */
    @Nullable public UUID helper;
    public State state = State.ROAD;
    public int survivors;
    /** The village whose scouts found it (null: unknown), when, and the player who took the job of intercepting it. */
    @Nullable public UUID revealedBy;
    public long revealedAt = -1;
    @Nullable public UUID takenBy;
    public boolean councilDecided;
    /** Bribed: the company now rides for the other side. */
    public boolean bribed;
    /** Real soldiers in the world now (entity ids), and since when (its march is held while they stand). */
    public final List<UUID> materialized = new ArrayList<>();
    public long heldSince = -1;
    /** A convoy's carts are in the world (they stay as loot once its guards fall). */
    public boolean cartsSpawned;
    public final List<UUID> carts = new ArrayList<>();
    public String outcome = "";
    /** 1.10: the road it follows (points from its start to its end), or empty: straight across country. */
    public int[] way = new int[0];
    /** 1.10, a supply train: whether it supplies the host (else the besieged); the full slots left in its carts (-1: untouched). */
    public boolean forHost;
    public int cargo = -1;
    private Route route;

    public Column(UUID id, Kind kind, @Nullable UUID siege, UUID owner, @Nullable UUID destination, int fromX, int fromZ, int toX, int toZ,
                  long depart, long arrive) {
        this.id = id;
        this.kind = kind;
        this.siege = siege;
        this.owner = owner;
        this.destination = destination;
        this.fromX = fromX;
        this.fromZ = fromZ;
        this.toX = toX;
        this.toZ = toZ;
        this.depart = depart;
        this.arrive = arrive;
    }

    public boolean onRoad() {
        return state == State.ROAD;
    }

    public boolean open(long now) {
        return onRoad() && revealedBy != null && !councilDecided && takenBy == null;
    }

    /** The way it travels: its road, or straight from its start to its end. */
    public Route route() {
        if (route == null) {
            route = Route.of(way, fromX, fromZ, toX, toZ);
        }
        return route;
    }

    /** It takes this road (its points; null or short: straight). */
    public void road(int[] points) {
        way = points != null && points.length >= 6 ? points.clone() : new int[0];
        route = null;
    }

    /** How far along its journey it is at {@code now} (0..1). */
    public double progress(long now) {
        long t = heldSince >= 0 ? heldSince : now;
        return kind == Kind.CONVOY ? 0 : arrive <= depart ? 1 : Math.max(0, Math.min(1, (t - depart) / (double) (arrive - depart)));
    }

    /** Where it is at {@code now} (x, z): along its road (or the line from its start to its end) by time; a convoy stands at its camp. */
    public double[] position(long now) {
        return kind == Kind.CONVOY ? new double[]{fromX, fromZ} : route().at(progress(now));
    }

    /** Whether it is on a road at {@code now} (not on its way to or from one, nor across country). */
    public boolean onTheRoad(long now) {
        return kind != Kind.CONVOY && route().onRoad(progress(now));
    }

    /** Its march is held while its men stand in the world; resuming pushes its arrival back by as long. */
    public void resume(long now) {
        if (heldSince >= 0) {
            long held = Math.max(0, now - heldSince);
            arrive += held;
            depart += held;
            heldSince = -1;
        }
    }

    /** Eight-point compass bearing from (x, z) to (tx, tz) (north is -z). */
    public static String bearing(double x, double z, double tx, double tz) {
        double deg = (Math.toDegrees(Math.atan2(tx - x, -(tz - z))) + 360) % 360;
        String[] n = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return n[(int) Math.round(deg / 45) % 8];
    }

    public static int distance(double x, double z, double tx, double tz) {
        return (int) Math.round(Math.hypot(tx - x, tz - z));
    }

    /** "1,240 m north-east" from (x, z). */
    public static String where(double x, double z, double tx, double tz) {
        int d = distance(x, z, tx, tz);
        return (d >= 1000 ? String.format("%.1f km", d / 1000.0) : d + " m") + " " + bearing(x, z, tx, tz);
    }

    /** A supply train's carts: one for every three of the guards it set out with, one to three. */
    public int carts() {
        return Math.max(1, Math.min(3, units.size() / 3));
    }

    /** What it is, in a few words. */
    public String label() {
        int n = Math.max(0, survivors);
        return switch (kind) {
            case MERCS -> (name.isEmpty() ? "a mercenary company" : name) + " (" + n + ")";
            case VASSAL -> "a vassal's men (" + n + ")";
            case MESSENGER -> "a messenger (" + n + " rider" + (n == 1 ? "" : "s") + ")";
            case ALARM -> "enemy scouts carrying the alarm (" + n + ")";
            case CONVOY -> (theme.isEmpty() ? "a supply" : ("aeiou".indexOf(theme.charAt(0)) >= 0 ? "an " : "a ") + theme) + " convoy (" + n + " guards)";
            case SUPPLY -> "a supply train" + (forHost ? " for the host" : " for the besieged") + " (" + carts() + " cart" + (carts() == 1 ? "" : "s") + ", "
                    + n + " guards)";
        };
    }
}

package dev.hywmill.politics.war;

/**
 * The way something travels between two places (1.10, docs/siege-design.md "War on the roads"): straight across country, or
 * by the roads (the Village Roads mod's network) when they serve the journey. A road journey leaves its start across country
 * for the nearest road, follows the roads and leaves them for its end; on the road a column goes {@link #ROAD_PACE} times as
 * fast, so a road a little longer than the straight line takes no longer. Positions are by the share of the journey's time.
 * Pure.
 */
public final class Route {
    /** On the road, travellers go this much faster than across country. */
    public static final double ROAD_PACE = 1.25;

    private final int[] xz;
    private final boolean roads;
    /** Cumulative time (in blocks across country) at each point. */
    private final double[] at;
    private final double length;

    /**
     * @param xz    points {x0, z0, x1, z1, ...} from start to end (at least two)
     * @param roads whether the points are a road journey: its first and last legs across country, the rest on the road
     */
    public Route(int[] xz, boolean roads) {
        if (xz == null || xz.length < 4 || xz.length % 2 != 0) {
            throw new IllegalArgumentException("a route needs two points or more");
        }
        this.xz = xz.clone();
        this.roads = roads && xz.length >= 6;
        int n = xz.length / 2;
        at = new double[n];
        double len = 0;
        for (int i = 1; i < n; i++) {
            double d = Math.hypot(xz[2 * i] - xz[2 * i - 2], xz[2 * i + 1] - xz[2 * i - 1]);
            len += d;
            boolean road = this.roads && i > 1 && i < n - 1;
            at[i] = at[i - 1] + (road ? d / ROAD_PACE : d);
        }
        length = len;
    }

    public static Route straight(int x0, int z0, int x1, int z1) {
        return new Route(new int[]{x0, z0, x1, z1}, false);
    }

    /** The road journey if there is one ({@code road}: the roads' points, or null/too short), else straight. */
    public static Route of(int[] road, int x0, int z0, int x1, int z1) {
        return road != null && road.length >= 6 ? new Route(road, true) : straight(x0, z0, x1, z1);
    }

    public boolean roads() {
        return roads;
    }

    /** Its points, for saving (empty for a straight journey: it is rebuilt from its ends). */
    public int[] saved() {
        return roads ? xz.clone() : new int[0];
    }

    public int[] points() {
        return xz.clone();
    }

    /** How long it is (blocks). */
    public double length() {
        return length;
    }

    /** How long it takes, as so many blocks across country. */
    public double time() {
        return at[at.length - 1];
    }

    /** Where a traveller is when {@code f} (0..1) of the journey's time has passed: {x, z}. */
    public double[] at(double f) {
        int i = segment(f);
        double t = Math.max(0, Math.min(1, f)) * time();
        double span = at[i + 1] - at[i];
        double u = span <= 0 ? 0 : Math.max(0, Math.min(1, (t - at[i]) / span));
        return new double[]{xz[2 * i] + (xz[2 * i + 2] - xz[2 * i]) * u, xz[2 * i + 1] + (xz[2 * i + 3] - xz[2 * i + 1]) * u};
    }

    /** Which way a traveller faces at {@code f}: a unit vector {x, z} (east if the journey has no length). */
    public double[] heading(double f) {
        int i = segment(f);
        double dx = xz[2 * i + 2] - xz[2 * i], dz = xz[2 * i + 3] - xz[2 * i + 1], d = Math.hypot(dx, dz);
        return d == 0 ? new double[]{1, 0} : new double[]{dx / d, dz / d};
    }

    /** The direction of the stretch nearest (x, z): a unit vector {x, z} (a meeting point's road, for the lines of battle). */
    public double[] headingNear(double x, double z) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i + 1 < at.length; i++) {
            double ax = xz[2 * i], az = xz[2 * i + 1], bx = xz[2 * i + 2], bz = xz[2 * i + 3];
            double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / l2));
            double d = Math.hypot(x - (ax + t * dx), z - (az + t * dz));
            if (d < bd && l2 > 0) {
                bd = d;
                best = i;
            }
        }
        double dx = xz[2 * best + 2] - xz[2 * best], dz = xz[2 * best + 3] - xz[2 * best + 1], d = Math.hypot(dx, dz);
        return d == 0 ? new double[]{1, 0} : new double[]{dx / d, dz / d};
    }

    /** Whether a traveller is on the road at {@code f} (not on the legs to and from it). */
    public boolean onRoad(double f) {
        int i = segment(f);
        return roads && i > 0 && i < at.length - 2;
    }

    private int segment(double f) {
        double t = Math.max(0, Math.min(1, f)) * time();
        int i = 0;
        while (i < at.length - 2 && at[i + 1] < t) {
            i++;
        }
        return i;
    }

    /** The point on the road journey {@code share} of its length from its start that is on the road, or null for a straight one. */
    public double[] roadPoint(double share) {
        if (!roads) {
            return null;
        }
        // from the first road point to the last, by length
        int n = at.length;
        double total = 0;
        for (int i = 2; i < n - 1; i++) {
            total += Math.hypot(xz[2 * i] - xz[2 * i - 2], xz[2 * i + 1] - xz[2 * i - 1]);
        }
        double want = Math.max(0, Math.min(1, share)) * total, run = 0;
        for (int i = 2; i < n - 1; i++) {
            double d = Math.hypot(xz[2 * i] - xz[2 * i - 2], xz[2 * i + 1] - xz[2 * i - 1]);
            if (run + d >= want && d > 0) {
                double u = (want - run) / d;
                return new double[]{xz[2 * i - 2] + (xz[2 * i] - xz[2 * i - 2]) * u, xz[2 * i - 1] + (xz[2 * i + 1] - xz[2 * i - 1]) * u};
            }
            run += d;
        }
        return new double[]{xz[2 * (n - 2)], xz[2 * (n - 2) + 1]};
    }
}

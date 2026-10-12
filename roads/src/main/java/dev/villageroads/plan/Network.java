package dev.villageroads.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Which villages a road links: each to its natural neighbours, as roads grow between settlements. Two villages are linked
 * when they are within reach and no third village lies nearer to both of them than they are to each other (the "relative
 * neighbourhood" of the villages): no road runs past one village to the next when it could go by way of it, and no village is
 * cut off from a near neighbour. Pure.
 */
public final class Network {
    private Network() {}

    public record Place(String id, int x, int z) {}

    public record Link(Place a, Place b, double distance) {}

    public static List<Link> links(List<Place> places, double reach) {
        List<Link> out = new ArrayList<>();
        int n = places.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Place a = places.get(i), b = places.get(j);
                double d = dist(a, b);
                if (d > reach || d < 1) {
                    continue;
                }
                boolean between = false;
                for (int k = 0; k < n && !between; k++) {
                    if (k != i && k != j) {
                        Place c = places.get(k);
                        between = Math.max(dist(a, c), dist(b, c)) < d;
                    }
                }
                if (!between) {
                    out.add(new Link(a, b, d));
                }
            }
        }
        out.sort((p, q) -> Double.compare(p.distance(), q.distance()));
        return out;
    }

    static double dist(Place a, Place b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /** The same key for a link either way round. */
    public static String key(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }
}

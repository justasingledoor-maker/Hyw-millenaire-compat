package dev.hywmill.garrison.duty;

import dev.hywmill.military.classify.BuildingRole;
import dev.hywmill.settlement.SettlementSource.Layout;
import dev.hywmill.settlement.SettlementSource.LayoutPoint;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where each standing duty stands or walks, derived deterministically from a village's layout
 * (M4). Pure; computed only when the layout (or the rules) change. Positions carry the layout's
 * Y; the service resolves standable ground when it issues a move.
 *
 * @param sentryPosts ordered sentry posts (best first); pair {@code i} stands at post {@code i}
 * @param patrol      closed patrol loop (at least 3 waypoints)
 * @param scoutPosts  scout posts on the ring outside the village
 * @param muster      standing points of GARRISON duty (military buildings, else the defending position)
 * @param reserve     the reserve's assembly point (armoury, barracks, fort townhall, training ground, else defending position)
 * @param scoutBase   where scouts rest between rides
 * @param key         layout key the plan was computed from
 */
public record DutyPlan(List<BlockPos> sentryPosts, List<BlockPos> patrol, List<BlockPos> scoutPosts, List<BlockPos> muster,
                       BlockPos reserve, BlockPos scoutBase, long key) {
    /** Sentry post preference: gates first, then guardhouses, towers, walls, then armoury/military buildings. */
    public static final List<BuildingRole> SENTRY_PRIORITY = List.of(BuildingRole.GATE, BuildingRole.GUARDHOUSE, BuildingRole.WATCHTOWER,
            BuildingRole.TOWER, BuildingRole.WALL, BuildingRole.ARMOURY, BuildingRole.BARRACKS, BuildingRole.FORT_TOWNHALL,
            BuildingRole.TRAINING);
    /** Posts closer than this to an already chosen post are skipped (pairs spread over the defences). */
    public static final int POST_SPACING = 10;

    public static DutyPlan of(Layout l, UUID village, MoveRule move, ScoutRule scout) {
        return new DutyPlan(sentryPosts(l), patrol(l, move), scoutPosts(l, village, scout), muster(l), reservePoint(l), l.defendingPos(), l.key());
    }

    /**
     * Sentry posts by role priority; within a role, farthest-point order (each next post is the
     * candidate farthest from the posts already chosen; ties by position), skipping candidates
     * within {@link #POST_SPACING}. Without military buildings: the defending position and the townhall.
     */
    public static List<BlockPos> sentryPosts(Layout l) {
        List<BlockPos> out = new ArrayList<>();
        for (BuildingRole role : SENTRY_PRIORITY) {
            List<BlockPos> cands = new ArrayList<>();
            for (LayoutPoint p : l.military()) {
                if (p.role() == role) {
                    cands.add(p.pos());
                }
            }
            cands.sort(Comparator.comparingLong(BlockPos::asLong));
            while (!cands.isEmpty()) {
                BlockPos best = null;
                double bestD = Double.NEGATIVE_INFINITY;
                for (BlockPos c : cands) {
                    double d = out.isEmpty() ? -dist2(c, l.center()) : minDist2(c, out);
                    if (d > bestD) {
                        bestD = d;
                        best = c;
                    }
                }
                cands.remove(best);
                if (out.isEmpty() || minDist2(best, out) >= (double) POST_SPACING * POST_SPACING) {
                    out.add(best);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(l.defendingPos());
            if (dist2(l.townhall(), l.defendingPos()) >= (double) POST_SPACING * POST_SPACING) {
                out.add(l.townhall());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Patrol loop: in each of {@code patrolPoints} equal angular sectors around the centre, the
     * building anchor (or military point) farthest from the centre within
     * {@code radius * patrolRadius}; sectors in angular order form a closed loop. With fewer than
     * three such points, a regular ring at 60% of that radius is used instead.
     */
    public static List<BlockPos> patrol(Layout l, MoveRule move) {
        double r = Math.max(8, l.radius() * move.patrolRadius());
        int k = move.patrolPoints();
        BlockPos[] best = new BlockPos[k];
        double[] bestD = new double[k];
        List<BlockPos> cands = new ArrayList<>(l.anchors());
        for (LayoutPoint p : l.military()) {
            cands.add(p.pos());
        }
        for (BlockPos c : cands) {
            double dx = c.getX() - l.center().getX(), dz = c.getZ() - l.center().getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 > r * r || d2 < 16) {
                continue;
            }
            int s = sector(dx, dz, k);
            if (best[s] == null || d2 > bestD[s] || (d2 == bestD[s] && c.asLong() < best[s].asLong())) {
                best[s] = c;
                bestD[s] = d2;
            }
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos b : best) {
            if (b != null) {
                out.add(b);
            }
        }
        if (out.size() >= 3) {
            return List.copyOf(out);
        }
        out.clear();
        for (int i = 0; i < k; i++) {
            double a = 2 * Math.PI * (i + 0.5) / k;
            out.add(BlockPos.containing(l.center().getX() + 0.6 * r * Math.cos(a), l.center().getY(), l.center().getZ() + 0.6 * r * Math.sin(a)));
        }
        return List.copyOf(out);
    }

    static int sector(double dx, double dz, int k) {
        double a = Math.atan2(dz, dx);
        if (a < 0) {
            a += 2 * Math.PI;
        }
        return Math.min(k - 1, (int) Math.floor(a / (2 * Math.PI) * k));
    }

    /** {@code posts} points on the ring {@code radius + distance} from the centre, rotated by a per-village angle. */
    public static List<BlockPos> scoutPosts(Layout l, UUID village, ScoutRule s) {
        double rot = Math.floorMod(village.getLeastSignificantBits() ^ village.getMostSignificantBits(), 3600L) / 3600.0 * 2 * Math.PI;
        double r = l.radius() + s.distance();
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < s.posts(); i++) {
            double a = rot + 2 * Math.PI * i / s.posts();
            out.add(BlockPos.containing(l.center().getX() + r * Math.cos(a), l.center().getY(), l.center().getZ() + r * Math.sin(a)));
        }
        return List.copyOf(out);
    }

    /** GARRISON duty standing points: every military point (layout order), else the defending position. */
    public static List<BlockPos> muster(Layout l) {
        List<BlockPos> out = new ArrayList<>();
        for (LayoutPoint p : l.military()) {
            out.add(p.pos());
        }
        if (out.isEmpty()) {
            out.add(l.defendingPos());
        }
        return List.copyOf(out);
    }

    public static BlockPos reservePoint(Layout l) {
        for (BuildingRole role : List.of(BuildingRole.ARMOURY, BuildingRole.BARRACKS, BuildingRole.FORT_TOWNHALL, BuildingRole.TRAINING)) {
            BlockPos best = null;
            for (LayoutPoint p : l.military()) {
                if (p.role() == role && (best == null || dist2(p.pos(), l.center()) < dist2(best, l.center()))) {
                    best = p.pos();
                }
            }
            if (best != null) {
                return best;
            }
        }
        return l.defendingPos();
    }

    /** Standing point of a GARRISON-duty unit: a muster point chosen by its roster id (stable while the layout is). */
    public BlockPos musterFor(UUID rosterId) {
        return muster.get((int) Math.floorMod(rosterId.getLeastSignificantBits(), (long) muster.size()));
    }

    /** Sentry {@code member} (0 or 1) of pair {@code pair}: either side of the post, across the line to the centre. */
    public BlockPos sentrySpot(int pair, int member, BlockPos center, int spacing) {
        BlockPos post = sentryPosts.get(Math.floorMod(pair, sentryPosts.size()));
        double dx = post.getX() - center.getX(), dz = post.getZ() - center.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double px = len < 1e-6 ? 1 : -dz / len, pz = len < 1e-6 ? 0 : dx / len;
        double off = member == 0 ? -spacing / 2.0 : spacing / 2.0;
        return BlockPos.containing(post.getX() + 0.5 + px * off, post.getY(), post.getZ() + 0.5 + pz * off);
    }

    /** First waypoint of patrol slot {@code slot} of {@code count}: slots are spread evenly over the loop. */
    public int patrolStart(int slot, int count) {
        return count <= 0 ? 0 : (int) ((long) Math.max(0, slot) * patrol.size() / count) % patrol.size();
    }

    public BlockPos scoutPost(int index) {
        return scoutPosts.get(Math.floorMod(index, scoutPosts.size()));
    }

    /** Reserve units stand around the reserve point (deterministic offset by roster id). */
    public BlockPos reserveSpot(UUID rosterId) {
        int[][] ring = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};
        int[] o = ring[(int) Math.floorMod(rosterId.getMostSignificantBits(), (long) ring.length)];
        return reserve.offset(o[0], 0, o[1]);
    }

    static double dist2(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    static double minDist2(BlockPos c, List<BlockPos> chosen) {
        double m = Double.MAX_VALUE;
        for (BlockPos o : chosen) {
            m = Math.min(m, dist2(c, o));
        }
        return m;
    }

    /** Diagnostic summary. */
    public Map<String, Object> describe() {
        return Map.of("sentryPosts", sentryPosts.size(), "patrol", patrol.size(), "scoutPosts", scoutPosts.size(), "muster", muster.size(),
                "reserve", reserve.toShortString());
    }
}

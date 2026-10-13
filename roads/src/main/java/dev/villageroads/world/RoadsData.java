package dev.villageroads.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a dimension remembers of its roads: the village links built (never built twice) and those that failed (tried again
 * later), the columns of this mod's own roads (a later road may join or cross them as roads), and each road's way (its centre
 * line, which travellers follow: {@link dev.villageroads.api.Roads}). The road network built from the ways is kept while the
 * server runs and built again when a road is added or taken up.
 */
public final class RoadsData extends SavedData {
    static final String NAME = "villageroads";

    final Set<String> built = new HashSet<>();
    final Map<String, Long> failed = new HashMap<>();
    final LongOpenHashSet cells = new LongOpenHashSet();
    /** Each road's way {x0, z0, x1, z1, ...}: a village road by its link key (from one village's centre to the other's), a staff road by its own. */
    final Map<String, int[]> ways = new HashMap<>();

    /** Not saved: the network built from the ways, the journeys asked of it, and the links whose way could not be traced. */
    transient dev.villageroads.plan.RoadGraph graph;
    final transient Map<Long, int[]> journeys = new HashMap<>();
    final transient Set<String> untraceable = new HashSet<>();

    public static RoadsData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(RoadsData::new, RoadsData::load, null), NAME);
    }

    static RoadsData load(CompoundTag tag, HolderLookup.Provider lookup) {
        RoadsData d = new RoadsData();
        for (Tag t : tag.getList("built", Tag.TAG_STRING)) {
            d.built.add(t.getAsString());
        }
        CompoundTag f = tag.getCompound("failed");
        for (String k : f.getAllKeys()) {
            d.failed.put(k, f.getLong(k));
        }
        for (long c : tag.getLongArray("cells")) {
            d.cells.add(c);
        }
        CompoundTag w = tag.getCompound("ways");
        for (String k : w.getAllKeys()) {
            d.ways.put(k, w.getIntArray(k));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        ListTag b = new ListTag();
        built.forEach(k -> b.add(StringTag.valueOf(k)));
        tag.put("built", b);
        CompoundTag f = new CompoundTag();
        failed.forEach(f::putLong);
        tag.put("failed", f);
        tag.putLongArray("cells", cells.toLongArray());
        CompoundTag w = new CompoundTag();
        ways.forEach(w::putIntArray);
        tag.put("ways", w);
        return tag;
    }

    /** Forgets the village links (built and failed), so the network is looked at afresh; the roads stay. */
    public static void forget(RoadsData d) {
        d.built.clear();
        d.failed.clear();
        d.setDirty();
    }

    /** A road's way is kept (or dropped, null): the network is built again when next asked. */
    void way(String key, int[] xz) {
        if (xz == null) {
            ways.remove(key);
        } else {
            ways.put(key, xz);
        }
        graph = null;
        journeys.clear();
        setDirty();
    }

    /** How many roads travellers can follow (older roads' ways traced first). */
    public int wayCount(ServerLevel level) {
        network(level);
        return ways.size();
    }

    /** The journey by the roads (see {@link dev.villageroads.api.Roads#route}); journeys asked before are remembered. */
    public int[] route(ServerLevel level, int fx, int fz, int tx, int tz) {
        dev.villageroads.plan.RoadGraph g = network(level);
        long key = (((long) (fx >> 3) & 0xFFFF) << 48) | (((long) (fz >> 3) & 0xFFFF) << 32) | (((long) (tx >> 3) & 0xFFFF) << 16) | ((tz >> 3) & 0xFFFF);
        int[] r = journeys.get(key);
        if (r == null) {
            if (journeys.size() > 4096) {
                journeys.clear();
            }
            int[] found = g.route(fx, fz, tx, tz);
            r = found == null ? new int[0] : found;
            journeys.put(key, r);
        }
        if (r.length < 4) {
            return null;
        }
        int[] out = r.clone();
        out[0] = fx;
        out[1] = fz;
        out[out.length - 2] = tx;
        out[out.length - 1] = tz;
        return out;
    }

    /** The network of the ways, built when first asked after a change; the ways of roads built before ways were kept are traced first. */
    dev.villageroads.plan.RoadGraph network(ServerLevel level) {
        if (graph == null) {
            traceOlder(level);
            graph = new dev.villageroads.plan.RoadGraph(ways.values());
            RoadManager.LOG.info("Roads: {} way(s) for travellers, {} places", ways.size(), graph.places());
        }
        return graph;
    }

    /** Village roads built before their ways were kept: traced over their columns, from one village to the other. */
    private void traceOlder(ServerLevel level) {
        List<String> missing = new java.util.ArrayList<>();
        for (String k : built) {
            if (!ways.containsKey(k) && !untraceable.contains(k)) {
                missing.add(k);
            }
        }
        if (missing.isEmpty() || !dev.villageroads.integration.millenaire.MillVillages.available()) {
            return;
        }
        Map<String, VillageInfo> byId = new HashMap<>();
        for (VillageInfo v : dev.villageroads.integration.millenaire.MillVillages.list(level)) {
            byId.put(v.id(), v);
        }
        int traced = 0;
        for (String k : missing) {
            int bar = k.indexOf('|');
            VillageInfo a = bar < 0 ? null : byId.get(k.substring(0, bar)), b = bar < 0 ? null : byId.get(k.substring(bar + 1));
            if (a == null || b == null) {
                untraceable.add(k);
                continue;
            }
            int pad = dev.villageroads.RoadsConfig.CORRIDOR.get() + 32;
            int ax = a.center().getX(), az = a.center().getZ(), bx = b.center().getX(), bz = b.center().getZ();
            int[] xz = dev.villageroads.plan.Ways.trace(cells::contains, ax, az, a.radius() + 40, bx, bz, b.radius() + 40,
                    Math.min(ax, bx) - pad, Math.min(az, bz) - pad, Math.max(ax, bx) + pad, Math.max(az, bz) + pad, 4_000_000);
            if (xz == null) {
                untraceable.add(k);
                continue;
            }
            ways.put(k, dev.villageroads.plan.Ways.simplify(xz, dev.villageroads.plan.Ways.TOLERANCE));
            traced++;
        }
        if (traced > 0) {
            setDirty();
        }
        RoadManager.LOG.info("Roads: traced the ways of {} older road(s) ({} could not be traced)", traced, missing.size() - traced);
    }

    public static long cell(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public boolean ours(int x, int z) {
        return cells.contains(cell(x, z));
    }
}

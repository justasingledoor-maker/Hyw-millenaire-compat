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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a dimension remembers of its roads: the village links built (never built twice) and those that failed (tried again
 * later), and the columns of this mod's own roads (a later road may join or cross them as roads).
 */
public final class RoadsData extends SavedData {
    static final String NAME = "villageroads";

    final Set<String> built = new HashSet<>();
    final Map<String, Long> failed = new HashMap<>();
    final LongOpenHashSet cells = new LongOpenHashSet();

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
        return tag;
    }

    /** Forgets the village links (built and failed), so the network is looked at afresh; the roads stay. */
    public static void forget(RoadsData d) {
        d.built.clear();
        d.failed.clear();
        d.setDirty();
    }

    public static long cell(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public boolean ours(int x, int z) {
        return cells.contains(cell(x, z));
    }
}

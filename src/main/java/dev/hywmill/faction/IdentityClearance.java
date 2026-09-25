package dev.hywmill.faction;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Villages whose residents must NOT carry our faction identity marker (set by
 * {@code /hywmill admin clear-identities}, lifted by {@code restore-identities}). Persisted so
 * villagers that are unloaded when the command runs are cleared when they next load, and so the
 * marker is not re-applied before the mod is uninstalled. Stored in the Overworld as
 * {@code data/hywmill_identity_clearance.dat}.
 */
public final class IdentityClearance extends SavedData {
    public static final String DATA_NAME = "hywmill_identity_clearance";

    private final Set<UUID> villages = new LinkedHashSet<>();
    private boolean all;

    public static IdentityClearance get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(IdentityClearance::new, IdentityClearance::load, null), DATA_NAME);
    }

    public boolean isCleared(UUID village) {
        return all || villages.contains(village);
    }

    public boolean all() {
        return all;
    }

    public Set<UUID> villages() {
        return Collections.unmodifiableSet(villages);
    }

    public void clear(UUID village) {
        if (villages.add(village)) {
            setDirty();
        }
    }

    public void clearAll() {
        if (!all) {
            all = true;
            setDirty();
        }
    }

    /** @return true if the village was individually cleared (an "all" flag is lifted only by {@link #restoreAll}). */
    public boolean restore(UUID village) {
        boolean removed = villages.remove(village);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public void restoreAll() {
        if (all || !villages.isEmpty()) {
            all = false;
            villages.clear();
            setDirty();
        }
    }

    private static IdentityClearance load(CompoundTag root, HolderLookup.Provider registries) {
        IdentityClearance c = new IdentityClearance();
        c.all = root.getBoolean("all");
        ListTag list = root.getList("villages", Tag.TAG_INT_ARRAY);
        for (Tag t : list) {
            c.villages.add(NbtUtils.loadUUID(t));
        }
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putBoolean("all", all);
        ListTag list = new ListTag();
        villages.forEach(v -> list.add(NbtUtils.createUUID(v)));
        root.put("villages", list);
        return root;
    }
}

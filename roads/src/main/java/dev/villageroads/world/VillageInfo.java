package dev.villageroads.world;

import net.minecraft.core.BlockPos;

/** A village a road may link: its id, name, centre, the radius its buildings reach, and whether it is a lone building. */
public record VillageInfo(String id, String name, BlockPos center, int radius, boolean lone) {}

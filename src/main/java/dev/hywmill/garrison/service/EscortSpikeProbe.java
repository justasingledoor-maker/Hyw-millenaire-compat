package dev.hywmill.garrison.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/**
 * M5-0 spike H only: exposes M4's existing hop resolution ({@link DutyService#hopTarget}) to the dev
 * escort probe, unchanged. No production code calls this.
 */
public final class EscortSpikeProbe {
    private EscortSpikeProbe() {}

    @Nullable
    public static BlockPos hop(ServerLevel level, Entity unit, BlockPos goal, int maxHop) {
        return DutyService.hopTarget(level, unit, goal, maxHop);
    }
}

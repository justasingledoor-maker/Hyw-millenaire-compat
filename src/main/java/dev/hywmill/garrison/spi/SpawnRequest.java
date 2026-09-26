package dev.hywmill.garrison.spi;

import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.garrison.tag.GarrisonTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Everything needed to create one garrison unit. The owner is always the village faction UUID;
 * the entity UUID is the roster's deterministic UUID for (rosterId, generation).
 * {@code context}: the M4 equipment context (null: the provider's context-free apply).
 */
public record SpawnRequest(UnitSpec unit, UUID owner, UUID entityUuid, Vec3 pos, BlockPos home, int equipmentLevel,
                           boolean equipmentDrops, GarrisonTag tag, EquipmentProvider equipment, @Nullable EquipmentProvider.Context context) {
    public SpawnRequest(UnitSpec unit, UUID owner, UUID entityUuid, Vec3 pos, BlockPos home, int equipmentLevel, boolean equipmentDrops,
                        GarrisonTag tag, EquipmentProvider equipment) {
        this(unit, owner, entityUuid, pos, home, equipmentLevel, equipmentDrops, tag, equipment, null);
    }
}

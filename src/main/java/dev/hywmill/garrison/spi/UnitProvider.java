package dev.hywmill.garrison.spi;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Creates and drives garrison units. The only implementation is backed by HYW and lives in
 * {@code integration.hyw}; garrison code never touches HYW types directly.
 */
public interface UnitProvider {
    /** True if {@code entityTypeId} is a registered entity type that creates a combat unit this provider manages. */
    boolean isValidUnitType(String entityTypeId);

    /** Creates, configures and adds the unit in the audited HYW order. Never adds a half-configured entity. */
    SpawnResult spawn(ServerLevel level, SpawnRequest request);

    boolean isUnit(Entity entity);

    /** HYW OwnerUUID of a unit (null if none or not a unit). */
    @Nullable
    UUID ownerOf(Entity entity);

    int equipmentLevel(Entity entity);

    @Nullable
    BlockPos home(Entity entity);

    @Nullable
    LivingEntity target(Entity entity);

    /** Deployment: temporary HYW hostility plus a direct target. Never a permanent relation. */
    void engage(Entity unit, LivingEntity target);

    /** Clears the current target (the unit then returns home through HYW's own behaviour). */
    void disengage(Entity unit);

    /** Whether HYW's temporary hostility from {@code unit} to {@code target} is currently active. */
    boolean isTemporarilyHostile(Entity unit, LivingEntity target);

    /** One-line diagnostic description. */
    String describe(Entity entity);
}

package dev.hywmill.integration.hyw;

import dev.hywmill.garrison.spi.SpawnRequest;
import dev.hywmill.garrison.spi.SpawnResult;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.garrison.tag.GarrisonAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity;
import ydmsama.hundred_years_war.main.entity.utils.AttackStrategy;
import ydmsama.hundred_years_war.main.entity.utils.TemporaryHostileTargetManager;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * HYW-backed garrison units. The spawn sequence mirrors HYW's own
 * {@code RecruitmentOrderManager.spawnQueuedUnit} (verified with javap against 0.7.1r-fix1):
 * create, owner, supply flag, equipment, home, attack strategy, position, addFreshEntity; HywMill
 * adds no-despawn, drop chances, the garrison tag and the deterministic UUID before the add.
 */
public final class HywUnitProvider implements UnitProvider {
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    @Override
    public boolean isValidUnitType(String entityTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(entityTypeId);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return false;
        }
        return id.getNamespace().equals("hundred_years_war");
    }

    @Override
    public SpawnResult spawn(ServerLevel level, SpawnRequest req) {
        ResourceLocation id = ResourceLocation.tryParse(req.unit().entityType());
        Optional<EntityType<?>> type = id == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        if (type.isEmpty()) {
            return SpawnResult.failed("unregistered entity type " + req.unit().entityType());
        }
        Entity created = type.get().create(level);
        if (!(created instanceof BaseCombatEntity unit)) {
            if (created != null) {
                created.discard();
            }
            return SpawnResult.failed(req.unit().entityType() + " is not an HYW combat unit");
        }
        unit.setOwnerUUID(req.owner());
        unit.setRequiresSupply(false);
        unit.setCanNaturalDespawn(false);
        int applied = req.equipment().apply(unit, req.unit(), req.equipmentLevel());
        unit.setHomePosition(req.home());
        unit.setAttackStrategy(AttackStrategy.DEFAULT);
        if (!req.equipmentDrops()) {
            for (EquipmentSlot slot : SLOTS) {
                unit.setDropChance(slot, 0.0f);
            }
        }
        GarrisonAttachments.set(unit, req.tag());
        unit.setUUID(req.entityUuid());
        unit.setPos(req.pos().x, req.pos().y, req.pos().z);
        if (!level.addFreshEntity(unit)) {
            return SpawnResult.failed("addFreshEntity refused (UUID already present?)");
        }
        return new SpawnResult(unit, applied, "");
    }

    @Override
    public boolean isUnit(Entity entity) {
        return entity instanceof BaseCombatEntity;
    }

    @Override
    @Nullable
    public UUID ownerOf(Entity entity) {
        return entity instanceof BaseCombatEntity b ? b.getOwnerUUID() : null;
    }

    @Override
    public int equipmentLevel(Entity entity) {
        return entity instanceof BaseCombatEntity b ? b.getEquipmentLevel() : -1;
    }

    @Override
    @Nullable
    public BlockPos home(Entity entity) {
        return entity instanceof BaseCombatEntity b ? b.getHomePosition() : null;
    }

    @Override
    @Nullable
    public LivingEntity target(Entity entity) {
        return entity instanceof BaseCombatEntity b ? b.getTarget() : null;
    }

    @Override
    public void engage(Entity unit, LivingEntity target) {
        if (unit instanceof BaseCombatEntity b) {
            TemporaryHostileTargetManager.markHostile(b, target);
            b.setTarget(target);
        }
    }

    @Override
    public void disengage(Entity unit) {
        if (unit instanceof BaseCombatEntity b) {
            b.setTarget(null);
        }
    }

    @Override
    public boolean isTemporarilyHostile(Entity unit, LivingEntity target) {
        return unit instanceof BaseCombatEntity b && TemporaryHostileTargetManager.isHostile(b, target);
    }

    @Override
    public String describe(Entity entity) {
        if (!(entity instanceof BaseCombatEntity b)) {
            return "not an HYW unit";
        }
        return BuiltInRegistries.ENTITY_TYPE.getKey(b.getType()) + " owner=" + b.getOwnerUUID() + " equipment=" + b.getEquipmentLevel()
                + " home=" + (b.getHomePosition() == null ? "none" : b.getHomePosition().toShortString())
                + " supply=" + b.requiresSupply() + " despawn=" + b.canNaturalDespawn();
    }
}

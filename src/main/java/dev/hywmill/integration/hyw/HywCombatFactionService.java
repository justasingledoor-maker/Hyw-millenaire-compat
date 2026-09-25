package dev.hywmill.integration.hyw;

import dev.hywmill.faction.CombatFactionService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity;
import ydmsama.hundred_years_war.main.entity.entities.tags.NonCombatUnit;
import ydmsama.hundred_years_war.main.entity.utils.TemporaryHostileTargetManager;
import ydmsama.hundred_years_war.main.utils.RelationOwnerMarkedEntity;
import ydmsama.hundred_years_war.main.utils.RelationSystem;
import ydmsama.hundred_years_war.main.utils.ServerRelationHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * HYW 0.7.1r-fix1 calls used (all public, verified with javap against the jar):
 * <ul>
 *   <li>{@code RelationOwnerMarkedEntity.hyw$markRelationOwnerUUID / hyw$getMarkedRelationOwnerUUID /
 *       hyw$hasRelationIdentityMarker / hyw$clearRelationIdentityMarker} (mixed into Entity)</li>
 *   <li>{@code ServerRelationHelper.getRelationUUID(Entity)}, {@code isEnemyRelationByUUID(UUID, UUID)}</li>
 *   <li>{@code RelationSystem.getRelation(UUID, UUID)}, {@code setRelation(UUID, UUID, RelationType)} (escalation guard only)</li>
 *   <li>{@code BaseCombatEntity.getOwnerUUID()}, {@code getHywTarget()}</li>
 *   <li>{@code TemporaryHostileTargetManager.isHostile(BaseCombatEntity, LivingEntity)}</li>
 * </ul>
 */
final class HywCombatFactionService implements CombatFactionService {

    @Override
    public boolean isCombatUnit(Entity entity) {
        return entity instanceof BaseCombatEntity b && isCombatCapable(b);
    }

    @Override
    public boolean isHywUnit(Entity entity) {
        return entity instanceof BaseCombatEntity && !(entity instanceof NonCombatUnit);
    }

    /**
     * HYW's own notion of a combat-capable unit: alive, not a NonCombatUnit, and not a neutral
     * uncrewed siege weapon (a CrewOperatedSiegeWeapon with no operational crew, no owner and no
     * passengers, which HYW itself treats as inert). A siege weapon becomes a combat unit again
     * as soon as it is crewed, owned or ridden.
     */
    private static boolean isCombatCapable(BaseCombatEntity b) {
        return b.isAlive() && !(b instanceof NonCombatUnit) && !b.isNeutralUncrewedSiegeWeapon();
    }

    @Nullable
    @Override
    public UUID ownerOf(Entity unit) {
        return unit instanceof BaseCombatEntity b ? b.getOwnerUUID() : null;
    }

    @Override
    public boolean isEnemyOfIdentity(Entity unit, UUID identity) {
        if (!(unit instanceof BaseCombatEntity b)) {
            return false;
        }
        // HYW's own rule: exactly one null owner => enemy; otherwise HOSTILE in either direction.
        return ServerRelationHelper.isEnemyRelationByUUID(b.getOwnerUUID(), identity);
    }

    @Nullable
    @Override
    public LivingEntity currentTarget(Entity unit) {
        if (!(unit instanceof BaseCombatEntity b)) {
            return null;
        }
        LivingEntity t = b.getHywTarget();
        return t != null ? t : b.getTarget();
    }

    @Override
    public boolean isTemporarilyHostile(Entity unit, LivingEntity target) {
        return unit instanceof BaseCombatEntity b && TemporaryHostileTargetManager.isHostile(b, target);
    }

    @Nullable
    @Override
    public UUID relationIdentity(Entity entity) {
        return ServerRelationHelper.getRelationUUID(entity);
    }

    @Nullable
    @Override
    public UUID markedIdentity(Entity entity) {
        RelationOwnerMarkedEntity m = marked(entity);
        return m.hyw$hasRelationIdentityMarker() ? m.hyw$getMarkedRelationOwnerUUID() : null;
    }

    @Override
    public boolean hasIdentityMarker(Entity entity) {
        return marked(entity).hyw$hasRelationIdentityMarker();
    }

    @Override
    public void markIdentity(Entity entity, UUID identity) {
        marked(entity).hyw$markRelationOwnerUUID(identity);
    }

    @Override
    public void clearIdentity(Entity entity) {
        marked(entity).hyw$clearRelationIdentityMarker();
    }

    private static RelationOwnerMarkedEntity marked(Entity entity) {
        // Safe: HYW's EntityRelationOwnerMarkerMixin makes every Entity implement this interface
        // (verified in HywIntegration.init).
        return (RelationOwnerMarkedEntity) entity;
    }

    @Override
    public String relation(UUID a, UUID b) {
        return RelationSystem.getRelation(a, b).name();
    }

    @Override
    public boolean isHostileEitherWay(UUID a, UUID b) {
        return RelationSystem.getRelation(a, b) == RelationSystem.RelationType.HOSTILE
                || RelationSystem.getRelation(b, a) == RelationSystem.RelationType.HOSTILE;
    }

    @Override
    public void resetHostileToNeutral(UUID a, UUID b) {
        // HYW stores HOSTILE symmetrically, so both directions are reset.
        if (RelationSystem.getRelation(a, b) == RelationSystem.RelationType.HOSTILE) {
            RelationSystem.setRelation(a, b, RelationSystem.RelationType.NEUTRAL);
        }
        if (RelationSystem.getRelation(b, a) == RelationSystem.RelationType.HOSTILE) {
            RelationSystem.setRelation(b, a, RelationSystem.RelationType.NEUTRAL);
        }
    }

    @Override
    public Set<UUID> permanentHostilesOf(UUID identity) {
        Set<UUID> out = new HashSet<>();
        RelationSystem.getAllRelations(identity).forEach((other, type) -> {
            if (type == RelationSystem.RelationType.HOSTILE) {
                out.add(other);
            }
        });
        // Inbound direction: HYW normally stores HOSTILE symmetrically, but a manual one-way
        // relation (e.g. set by a command or another mod) is only visible from the other side.
        for (UUID holder : RelationSystem.getAllRelationData().keySet()) {
            if (!holder.equals(identity) && RelationSystem.getRelation(holder, identity) == RelationSystem.RelationType.HOSTILE) {
                out.add(holder);
            }
        }
        return out;
    }

    @Override
    public List<LivingEntity> findCombatUnits(ServerLevel level, AABB box) {
        return new ArrayList<>(level.getEntitiesOfClass(BaseCombatEntity.class, box,
                HywCombatFactionService::isCombatCapable));
    }

    @Override
    public String describe(Entity entity) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
        String id = entity.getUUID().toString().substring(0, 8);
        if (entity instanceof BaseCombatEntity b) {
            UUID owner = b.getOwnerUUID();
            return type + "[" + id + " owner=" + (owner == null ? "null" : owner.toString().substring(0, 8)) + "]";
        }
        return type + "[" + id + "]";
    }
}

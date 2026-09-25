package dev.hywmill.faction;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * View of a combat/faction mod (HYW in M1). Implementations only OBSERVE the mod's relation
 * state, except for {@link #markIdentity}/{@link #clearIdentity} (identity marker) and
 * {@link #resetHostileToNeutral} (escalation guard, config-gated).
 */
public interface CombatFactionService {
    /** True for an HYW combat unit (excludes workers and other non-combat units). */
    boolean isCombatUnit(Entity entity);

    /** An HYW combat unit type (BaseCombatEntity, not a NonCombatUnit), alive or not (death statistics). */
    boolean isHywUnit(Entity entity);

    /** Owner UUID of a combat unit; null means HYW "null owner" (e.g. a summoned bandit). */
    @Nullable
    UUID ownerOf(Entity unit);

    /** HYW's own verdict: is this unit's owner an enemy of that identity (null owner vs anyone = enemy)? */
    boolean isEnemyOfIdentity(Entity unit, UUID identity);

    /** The unit's current combat target (HYW target, falling back to the vanilla target). */
    @Nullable
    LivingEntity currentTarget(Entity unit);

    /** HYW's temporary (600-tick) retaliation state of this unit's owner group toward the target. */
    boolean isTemporarilyHostile(Entity unit, LivingEntity target);

    /** HYW relation identity of any entity (player UUID, unit owner, or marker); null if none/unknown. */
    @Nullable
    UUID relationIdentity(Entity entity);

    /** The identity marker HYW stores on the entity itself, or null. */
    @Nullable
    UUID markedIdentity(Entity entity);

    boolean hasIdentityMarker(Entity entity);

    void markIdentity(Entity entity, UUID identity);

    void clearIdentity(Entity entity);

    /** HYW RelationSystem relation from a to b, as a name (HOSTILE/NEUTRAL/FRIENDLY/CONTROL). */
    String relation(UUID a, UUID b);

    boolean isHostileEitherWay(UUID a, UUID b);

    /** Sets both directions to NEUTRAL. Only used by the escalation guard and reconciliation. */
    void resetHostileToNeutral(UUID a, UUID b);

    /**
     * Every identity with a permanent HOSTILE relation to or from {@code identity} in the mod's
     * relation store (not temporary retaliation). Read-only.
     */
    Set<UUID> permanentHostilesOf(UUID identity);

    List<LivingEntity> findCombatUnits(ServerLevel level, AABB box);

    /** Short description for logs: type id and owner. */
    String describe(Entity entity);
}

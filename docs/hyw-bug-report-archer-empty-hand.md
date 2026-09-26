# Crash: Archer with an empty main hand throws "Invalid weapon firing an arrow"

**Mod:** Hundred Years War 0.7.1r-fix1 (NeoForge 1.21.1)
**Environment:** NeoForge 21.1.251, Minecraft 1.21.1, integrated server (single player)

## Summary

`ArcherEntity.canLobAttackTarget` passes the archer's main-hand item to
`ProjectileUtil.getMobArrow(...)` without checking whether it is empty. Since 1.21, vanilla's
`AbstractArrow` constructor throws `IllegalArgumentException("Invalid weapon firing an arrow")`
when the weapon stack passed to it is non-null but empty. So any HYW archer that ends up with an
empty main hand crashes the server the next time it evaluates a lob trajectory against a target
(`tick → updateCombatMovementPauseByTargetTransition → isActiveTarget → isValidTarget →
canAttackTargetWithAnyTrajectory → canLobAttackTarget`).

## Stack trace (relevant part)

```
java.lang.IllegalArgumentException: Invalid weapon firing an arrow
  at net.minecraft.world.entity.projectile.AbstractArrow.<init>(AbstractArrow.java:99)
  at net.minecraft.world.entity.projectile.Arrow.<init>(Arrow.java:38)
  at net.minecraft.world.item.ArrowItem.createArrow(ArrowItem.java:18)
  at net.minecraft.world.entity.projectile.ProjectileUtil.getMobArrow(ProjectileUtil.java:167)
  at ydmsama.hundred_years_war.main.entity.entities.ArcherEntity.canLobAttackTarget(ArcherEntity.java:435)
  at ydmsama.hundred_years_war.main.entity.entities.ArcherEntity.canAttackTargetWithAnyTrajectory(ArcherEntity.java:417)
  at ydmsama.hundred_years_war.main.entity.entities.ArcherEntity.isValidTarget(ArcherEntity.java:386)
  at ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity.isValidOrForcedAttackTarget(BaseCombatEntity.java:5977)
  at ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity.isActiveTarget(BaseCombatEntity.java:6067)
  at ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity.updateCombatMovementPauseByTargetTransition(BaseCombatEntity.java:6020)
  at ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity.tick(BaseCombatEntity.java:2743)
  at ydmsama.hundred_years_war.main.entity.entities.ArcherEntity.tick(ArcherEntity.java:126)
```

## Cause

Bytecode of `ArcherEntity.canLobAttackTarget`:

```
new ItemStack(Items.ARROW)
getItemInHand(InteractionHand.MAIN_HAND)       // may be ItemStack.EMPTY
ProjectileUtil.getMobArrow(this, arrow, velocity, weapon)
```

Vanilla 1.21.1, in `AbstractArrow(EntityType, double, double, double, Level, ItemStack pickup, @Nullable ItemStack firedFromWeapon)`:

```java
if (firedFromWeapon != null && level instanceof ServerLevel) {
    if (firedFromWeapon.isEmpty()) {
        throw new IllegalArgumentException("Invalid weapon firing an arrow");
    }
    ...
}
```

This check is only a trajectory test, so no arrow is actually fired, yet a real arrow entity is
constructed with the main-hand stack as its weapon.

## How an archer's main hand can become empty

HYW's own equipment tables always give archers a bow (`minecraft:bow` or
`magistuarmory:longbow`). The main hand can still become empty at runtime. For example, with
equipment that is *external* (set on the unit after HYW applied its intrinsic equipment),
`BaseCombatEntity.tryReplaceEquipment(Player)` calls `dropFirstVisibleExternalEquipment()`. That
drops the external item from the hand. If no intrinsic item is kept for that slot, the hand stays
empty. Other mods that modify mob equipment can produce the same state.

## Suggested fix

In `canLobAttackTarget`, and any similar trajectory checks, skip the lob test when the main hand
does not hold a usable ranged weapon, or pass a non-empty stack. For example:

```java
ItemStack weapon = this.getItemInHand(InteractionHand.MAIN_HAND);
if (weapon.isEmpty() || !(weapon.getItem() instanceof ProjectileWeaponItem)) {
    return false; // cannot lob without a bow; also avoids the vanilla exception
}
AbstractArrow arrow = ProjectileUtil.getMobArrow(this, new ItemStack(Items.ARROW), velocity, weapon);
```

Also worth considering:
* the same guard wherever HYW builds a projectile from the held item (crossbowmen, mounted
  archers);
* not dropping or removing an archer's only ranged weapon in `tryReplaceEquipment` unless an
  intrinsic weapon is restored to that slot.

## Workaround for affected worlds

Give the affected archer a bow right after the world loads:

```
/item replace entity @e[type=hundred_years_war:archer,distance=..8] weapon.mainhand with minecraft:bow
```

Or remove the unit with `/kill` using a precise selector.

Thanks for the mod!

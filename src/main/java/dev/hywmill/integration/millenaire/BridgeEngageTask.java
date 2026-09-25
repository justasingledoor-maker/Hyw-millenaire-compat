package dev.hywmill.integration.millenaire;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerNavDriver;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.StopReason;
import org.millenaire.goal.TravelPhase;
import org.millenaire.goal.VillagerTask;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Fights one HYW target using only Millénaire's public combat API, mirroring the package-private
 * CombatGoalSupport.pursueAndAttack: look at target, equip combat weapon, archers hold and shoot
 * at 5–20 blocks, otherwise melee within 2 blocks or path toward the target.
 * Damage, cooldowns and weapon choice stay entirely Millénaire's (MillVillager.performAttack).
 */
final class BridgeEngageTask implements VillagerTask {
    private static final double WALK_SPEED = 0.7;
    private static final double MELEE_RANGE_SQ = 4.0;
    private static final double MAX_PURSUIT_SQ = 80.0 * 80.0; // Millénaire's own combat drop-off distance
    private static final int MAX_TICKS = 1200;
    private static final int RECHECK_INTERVAL = 20;

    private final ResourceLocation goalId;
    @Nullable private final LivingEntity target;
    private final UUID village;
    @Nullable private MillVillager villager;
    private int ticks;
    private int attacks;
    private boolean finished;

    BridgeEngageTask(ResourceLocation goalId, @Nullable LivingEntity target, UUID village) {
        this.goalId = goalId;
        this.target = target;
        this.village = village;
        this.finished = target == null;
    }

    @Override
    public ResourceLocation goalId() {
        return goalId;
    }

    @Override
    public List<ItemStack> getHeldItems(TravelPhase phase) {
        return villager == null ? List.of() : List.of(villager.getCombatWeapon());
    }

    @Override
    public void tick(GoalContext ctx) {
        MillVillager v = ctx.villager();
        villager = v;
        ticks++;
        if (target == null || !target.isAlive() || target.isRemoved() || ticks > MAX_TICKS
                || v.distanceToSqr(target) > MAX_PURSUIT_SQ) {
            finished = true;
            return;
        }
        if (ticks == 1) {
            if (v.getAttackTarget() != target) {
                v.setAttackTarget(target);
            }
        } else if (v.getAttackTarget() != target) {
            // Millénaire cleared or replaced the target (e.g. 'defensive' leash from defendingPos,
            // Peaceful difficulty, a newer attacker). Respect its decision.
            finished = true;
            return;
        }
        HywMillRuntime rt = HywMillRuntime.get();
        if (rt == null || (ticks % RECHECK_INTERVAL == 0
                && !rt.threats().isThreat(village, target)
                && !rt.incidents().recentlyAttackedVillage(target.getUUID(), village, ctx.gameTime()))) {
            finished = true;
            return;
        }

        v.getLookControl().setLookAt(target, 30.0F, 30.0F);
        v.ensureCombatWeaponEquipped();
        double distSq = v.distanceToSqr(target);
        VillagerNavDriver nav = v.getNavManager();
        boolean archerHold = MillTypes.isArcher(v) && v.getMainHandItem().getItem() instanceof BowItem
                && distSq > 25.0 && distSq < 400.0;
        if (archerHold || distSq <= MELEE_RANGE_SQ) {
            nav.stop(v);
            if (v.performAttack(target)) {
                attacks++;
                if (attacks == 1) {
                    HmLog.diag("Villager {} landed first attack on HYW target {}", v.getUUID(), target.getUUID());
                }
            }
        } else {
            BlockPos to = target.blockPosition();
            BlockPos dest = nav.getDestination();
            if (dest == null || dest.distSqr(to) > 4.0 || !nav.isDestinationCombatTarget()) {
                nav.navigateToCombatTarget(v, to, WALK_SPEED);
            }
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void stop(GoalContext ctx, StopReason reason) {
        if (ctx == null) {
            return;
        }
        MillVillager v = ctx.villager();
        v.getNavManager().stop(v);
        if (reason != StopReason.INTERRUPTED && v.getAttackTarget() == target) {
            v.setAttackTarget(null);
        }
        HmLog.diag("Bridge engage task ended for {} (reason={}, ticks={}, attacks={})", v.getUUID(), reason, ticks, attacks);
    }
}

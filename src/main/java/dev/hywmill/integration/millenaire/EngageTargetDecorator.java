package dev.hywmill.integration.millenaire;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.Services;
import dev.hywmill.faction.CombatFactionService;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import org.millenaire.entity.MillVillager;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.goal.VillagerTask;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Decorates {@code millenaire:engage_target}. Millénaire's EngageTargetGoal.resolveTarget only
 * accepts ServerPlayer, Monster and MillVillager, so an HYW unit set as attackTarget by
 * VillagerCombat.onHurt / CombatHelper.callForHelp is silently ignored. This decorator accepts
 * such a target only when:
 * <ul>
 *   <li>the villager is a defender (helpInAttacks, not a raid clone) — civilians never engage HYW units;</li>
 *   <li>the target is an HYW combat unit; and</li>
 *   <li>the threat tracker lists it for this village, or it damaged a resident within the recent window.</li>
 * </ul>
 */
final class EngageTargetDecorator extends BridgeDecorator {
    EngageTargetDecorator(VillagerGoal original) {
        super(original);
    }

    @Nullable
    static LivingEntity bridgeTarget(GoalContext ctx) {
        MillVillager v = ctx.villager();
        LivingEntity t = v.getAttackTarget();
        if (t == null || !t.isAlive() || t.isRemoved()) {
            return null;
        }
        if (t instanceof Player || t instanceof Monster || t instanceof MillVillager) {
            return null; // original goal's domain
        }
        CombatFactionService factions = Services.factions();
        HywMillRuntime rt = HywMillRuntime.get();
        if (rt == null || factions == null || !factions.isCombatUnit(t) || !MillTypes.isDefender(v)) {
            return null;
        }
        UUID village = ctx.village().getId().uuid();
        if (rt.threats().isThreat(village, t) || rt.incidents().recentlyAttackedVillage(t.getUUID(), village, ctx.gameTime())) {
            return t;
        }
        return null;
    }

    @Override
    protected boolean bridgeCanStart(GoalContext ctx) {
        return bridgeTarget(ctx) != null;
    }

    @Override
    protected VillagerTask bridgeStart(GoalContext ctx) {
        LivingEntity t = bridgeTarget(ctx);
        logSelection(ctx, t, "engage_target");
        return new BridgeEngageTask(id(), t, ctx.village().getId().uuid());
    }

    static void logSelection(GoalContext ctx, @Nullable LivingEntity t, String via) {
        if (t == null) {
            return;
        }
        CombatFactionService f = Services.factions();
        HmLog.infoThrottled("select-" + ctx.villager().getUUID() + t.getUUID(), 10_000L,
                "Millénaire villager {} ({}) selects HYW target {} via {}",
                ctx.villager().getUUID(), ctx.villager().getVillagerTypeId(), f != null ? f.describe(t) : t.getUUID(), via);
    }
}

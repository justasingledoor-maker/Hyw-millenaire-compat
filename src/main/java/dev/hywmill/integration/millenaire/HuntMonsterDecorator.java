package dev.hywmill.integration.millenaire;

import dev.hywmill.military.ThreatTracker;
import net.minecraft.world.entity.LivingEntity;
import org.millenaire.entity.MillVillager;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.goal.VillagerTask;

import javax.annotation.Nullable;

/**
 * Decorates {@code millenaire:hunt_monster} (injected by Millénaire only into helpInAttacks types).
 * HuntMonsterGoal only considers Monster and hostile-tagged MillVillagers, so a hostile HYW unit
 * walking into the village is never noticed until it hits someone. The extra condition lets an
 * idle defender pick the nearest tracked threat; the actual fighting is the same bridge task
 * used by the engage decorator.
 */
final class HuntMonsterDecorator extends BridgeDecorator {
    /** Same order of magnitude as Millénaire's hunt zone (50 blocks around the town hall). */
    private static final double MAX_HUNT_DISTANCE = 64.0;

    HuntMonsterDecorator(VillagerGoal original) {
        super(original);
    }

    @Nullable
    private static LivingEntity huntTarget(GoalContext ctx) {
        MillVillager v = ctx.villager();
        if (!MillTypes.isDefender(v)) {
            return null;
        }
        LivingEntity current = v.getAttackTarget();
        if (current != null && current.isAlive()) {
            return null; // already busy; engage_target handles it
        }
        return ThreatTracker.nearestThreat(ctx.village().getId().uuid(), v.position(), MAX_HUNT_DISTANCE);
    }

    @Override
    protected boolean bridgeCanStart(GoalContext ctx) {
        return huntTarget(ctx) != null;
    }

    @Override
    protected VillagerTask bridgeStart(GoalContext ctx) {
        LivingEntity t = huntTarget(ctx);
        if (t != null) {
            ctx.villager().setAttackTarget(t);
        }
        EngageTargetDecorator.logSelection(ctx, t, "hunt_monster");
        return new BridgeEngageTask(id(), t, ctx.village().getId().uuid());
    }
}

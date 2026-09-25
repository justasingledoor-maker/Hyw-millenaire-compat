package dev.hywmill.integration.millenaire;

import dev.hywmill.core.HmLog;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerNavDriver;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.StopReason;
import org.millenaire.goal.VillagerTask;

/**
 * A reserve defender walks to Millénaire's defending position and holds it. It never chases.
 * Ends when the reserve is released (village CALM, or no longer in the reserve), when a real
 * Millénaire raid starts (the original defend_village then takes over), or when the villager is
 * attacked (self-defense through engage_target).
 */
final class BridgeHoldTask implements VillagerTask {
    private static final double WALK_SPEED = 0.7;
    private static final double HOLD_SQ = 9.0;

    private final ResourceLocation goalId;
    private final BlockPos pos;
    private int ticks;
    private boolean finished;

    BridgeHoldTask(ResourceLocation goalId, BlockPos pos) {
        this.goalId = goalId;
        this.pos = pos;
    }

    @Override
    public ResourceLocation goalId() {
        return goalId;
    }

    @Override
    public void tick(GoalContext ctx) {
        MillVillager v = ctx.villager();
        ticks++;
        if (!DefendVillageDecorator.shouldHold(ctx)) {
            finished = true;
            return;
        }
        if (ticks == 1) {
            HmLog.diag("Reserve defender {} ({}) holds the defending position {}", v.getUUID(), v.getVillagerTypeId(), pos.toShortString());
        }
        VillagerNavDriver nav = v.getNavManager();
        if (v.blockPosition().distSqr(pos) > HOLD_SQ) {
            if (nav.getDestination() == null) {
                nav.navigateTo(v, pos, WALK_SPEED);
            }
        } else {
            nav.stop(v);
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void stop(GoalContext ctx, StopReason reason) {
        if (ctx != null) {
            ctx.villager().getNavManager().stop(ctx.villager());
            HmLog.diag("Reserve defender {} stops holding (reason={}, ticks={})", ctx.villager().getUUID(), reason, ticks);
        }
    }
}

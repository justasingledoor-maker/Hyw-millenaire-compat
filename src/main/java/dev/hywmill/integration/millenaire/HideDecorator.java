package dev.hywmill.integration.millenaire;

import dev.hywmill.core.HywMillRuntime;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.goal.VillagerTask;

/**
 * Decorates {@code millenaire:hide}. HideGoal only runs while Millénaire's own raid state says
 * the village is under attack, and that state cannot be faked (RaidManager clears it when no
 * raid records exist). The extra condition makes civilians — exactly the types Millénaire itself
 * gives the hide goal — shelter while the threat tracker reports hostile HYW units in the village.
 */
final class HideDecorator extends BridgeDecorator {
    HideDecorator(VillagerGoal original) {
        super(original);
    }

    @Override
    protected boolean bridgeCanStart(GoalContext ctx) {
        HywMillRuntime rt = HywMillRuntime.get();
        return rt != null && MillTypes.isCivilian(ctx.villager()) && rt.threats().hasThreat(ctx.village().getId().uuid());
    }

    @Override
    protected VillagerTask bridgeStart(GoalContext ctx) {
        return new BridgeHideTask(id(), ctx.village().getId().uuid());
    }
}

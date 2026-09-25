package dev.hywmill.integration.millenaire;

import dev.hywmill.core.HywMillRuntime;
import org.millenaire.entity.MillVillager;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.goal.VillagerTask;

/**
 * Decorates {@code millenaire:hide}. HideGoal only runs while Millénaire's own raid state says
 * the village is under attack, and that state cannot be faked (RaidManager clears it when no
 * raid records exist). The extra condition makes civilians — exactly the types Millénaire itself
 * gives the hide goal — shelter while the village is ALERT/ENGAGED and an HYW threat is within the
 * doctrine's shelter radius of the civilian (or village-wide with shelterRadius = -1).
 */
final class HideDecorator extends BridgeDecorator {
    HideDecorator(VillagerGoal original) {
        super(original);
    }

    @Override
    protected boolean bridgeCanStart(GoalContext ctx) {
        HywMillRuntime rt = HywMillRuntime.get();
        MillVillager v = ctx.villager();
        return rt != null && MillTypes.isCivilian(v)
                && rt.defense().shouldShelter(ctx.village().getId().uuid(), v.getX(), v.getY(), v.getZ());
    }

    @Override
    protected VillagerTask bridgeStart(GoalContext ctx) {
        return new BridgeHideTask(id(), ctx.village().getId().uuid());
    }
}

package dev.hywmill.integration.millenaire;

import dev.hywmill.core.HywMillRuntime;
import org.millenaire.combat.raid.RaidManager;
import org.millenaire.entity.MillVillager;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.goal.VillagerTask;

/**
 * Decorates {@code millenaire:defend_village} (injected by Millénaire into helpInAttacks types)
 * so the doctrine reserve does something: while the village is ALERT, ENGAGED or RECOVERY,
 * reserve defenders walk to and hold Millénaire's own defending position
 * ({@link RaidManager#resolveDefendingPos}).
 *
 * <p>During a real Millénaire raid ({@code Village.isUnderAttack()}) the original goal can start
 * and runs unchanged. The reserve stops holding as soon as it is itself attacked (self-defense):
 * {@code defend_village} has priority 9999, higher than {@code engage_target} (5000), and
 * Millénaire's scheduler only preempts a combat task for a higher priority, so the hold branch
 * declines to start or continue while the villager was hurt within the last 100 ticks.
 */
final class DefendVillageDecorator extends BridgeDecorator {
    DefendVillageDecorator(VillagerGoal original) {
        super(original);
    }

    @Override
    protected boolean bridgeCanStart(GoalContext ctx) {
        return shouldHold(ctx);
    }

    static boolean shouldHold(GoalContext ctx) {
        MillVillager v = ctx.villager();
        HywMillRuntime rt = HywMillRuntime.get();
        return rt != null && !ctx.village().isUnderAttack() && MillTypes.isRoleDefender(v) && !MillTypes.recentlyHurt(v)
                && rt.defense().shouldHold(ctx.village().getId().uuid(), v.getUUID());
    }

    @Override
    protected VillagerTask bridgeStart(GoalContext ctx) {
        return new BridgeHoldTask(id(), MillenaireSettlementSource.defendingPos(ctx.village()));
    }
}

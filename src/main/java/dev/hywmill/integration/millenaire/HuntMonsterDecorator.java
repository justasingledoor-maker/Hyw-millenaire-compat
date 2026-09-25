package dev.hywmill.integration.millenaire;

import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.Services;
import dev.hywmill.faction.CombatFactionService;
import net.minecraft.world.entity.LivingEntity;
import org.millenaire.entity.MillVillager;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.goal.VillagerTask;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Decorates {@code millenaire:hunt_monster} (injected by Millénaire only into helpInAttacks types).
 * HuntMonsterGoal only considers Monster and hostile-tagged MillVillagers, so a hostile HYW unit
 * is never noticed until it hits someone. The extra condition lets an idle defender go after the
 * HYW threat the defense coordinator assigned it to (M2: only reactive threats, unless the
 * village's doctrine is proactive). The fighting itself is the bridge engage task.
 */
final class HuntMonsterDecorator extends BridgeDecorator {
    HuntMonsterDecorator(VillagerGoal original) {
        super(original);
    }

    @Nullable
    private static LivingEntity huntTarget(GoalContext ctx) {
        MillVillager v = ctx.villager();
        HywMillRuntime rt = HywMillRuntime.get();
        if (rt == null || !MillTypes.isRoleDefender(v)) {
            return null;
        }
        UUID village = ctx.village().getId().uuid();
        LivingEntity current = v.getAttackTarget();
        if (current != null && current.isAlive() && !isDisallowedHywTarget(rt, village, v, current)) {
            return null; // already busy; engage_target handles it
        }
        return rt.threats().threatEntity(village, rt.defense().assignedThreat(village, v.getUUID()));
    }

    /**
     * Millénaire's callForHelp may point a defender at an HYW unit the coordinator did not give it;
     * engage_target then refuses it. Such a target must not block the defender's own assignment.
     */
    private static boolean isDisallowedHywTarget(HywMillRuntime rt, UUID village, MillVillager v, LivingEntity current) {
        CombatFactionService factions = Services.factions();
        return factions != null && factions.isCombatUnit(current)
                && !rt.defense().mayEngage(village, v.getUUID(), current.getUUID(), MillTypes.selfDefense(v, current));
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

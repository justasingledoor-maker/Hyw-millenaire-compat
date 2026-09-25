package dev.hywmill.integration.millenaire;

import dev.hywmill.core.HmLog;
import dev.hywmill.military.ThreatTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.millenaire.building.BuildingInstance;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerNavDriver;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.StopReason;
import org.millenaire.goal.VillagerTask;
import org.millenaire.village.Village;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Walks a civilian to the same shelter Millénaire's own HideGoal uses: the town hall's
 * {@code shelterPos} special point, falling back to the town hall origin, then the village
 * center. Ends as soon as the threat tracker reports the village clear.
 */
final class BridgeHideTask implements VillagerTask {
    private static final double WALK_SPEED = 0.65;
    private static final double ARRIVED_SQ = 9.0;
    private static final int MAX_TICKS = 2400;

    private final ResourceLocation goalId;
    private final UUID village;
    @Nullable private BlockPos shelter;
    private int ticks;
    private boolean finished;

    BridgeHideTask(ResourceLocation goalId, UUID village) {
        this.goalId = goalId;
        this.village = village;
    }

    @Override
    public ResourceLocation goalId() {
        return goalId;
    }

    @Override
    public void tick(GoalContext ctx) {
        MillVillager v = ctx.villager();
        ticks++;
        if (!ThreatTracker.hasThreat(village) || ticks > MAX_TICKS) {
            finished = true;
            return;
        }
        if (shelter == null) {
            shelter = resolveShelter(ctx.village());
            HmLog.infoThrottled("hide-" + v.getUUID(), 60_000L,
                    "Civilian {} ({}) enters hide behavior: HYW threat in village, sheltering at {}",
                    v.getUUID(), v.getVillagerTypeId(), shelter.toShortString());
        }
        VillagerNavDriver nav = v.getNavManager();
        if (v.blockPosition().distSqr(shelter) > ARRIVED_SQ) {
            if (nav.getDestination() == null) {
                nav.navigateTo(v, shelter, WALK_SPEED);
            }
        } else {
            nav.stop(v);
        }
    }

    private static BlockPos resolveShelter(Village village) {
        BuildingInstance th = village.getTownhall();
        if (th != null) {
            BlockPos p = th.getFirstPointPos("shelterPos");
            if (p != null) {
                return p;
            }
            if (th.getOrigin() != null) {
                return th.getOrigin();
            }
        }
        return village.getCenter();
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void stop(GoalContext ctx, StopReason reason) {
        if (ctx != null) {
            ctx.villager().getNavManager().stop(ctx.villager());
            HmLog.diag("Civilian {} leaves hide behavior (reason={}, ticks={})", ctx.villager().getUUID(), reason, ticks);
        }
    }
}

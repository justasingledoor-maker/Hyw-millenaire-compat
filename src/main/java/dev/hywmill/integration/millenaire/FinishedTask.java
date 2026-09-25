package dev.hywmill.integration.millenaire;

import net.minecraft.resources.ResourceLocation;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.StopReason;
import org.millenaire.goal.VillagerTask;

/** A task that is already finished; returned when a bridge branch has to bail out safely. */
final class FinishedTask implements VillagerTask {
    private final ResourceLocation id;

    private FinishedTask(ResourceLocation id) {
        this.id = id;
    }

    static VillagerTask of(ResourceLocation id) {
        return new FinishedTask(id);
    }

    @Override
    public ResourceLocation goalId() {
        return id;
    }

    @Override
    public void tick(GoalContext ctx) {
    }

    @Override
    public boolean isFinished() {
        return true;
    }

    @Override
    public void stop(GoalContext ctx, StopReason reason) {
    }
}

package dev.hywmill.integration.millenaire;

import dev.hywmill.core.HmLog;
import net.minecraft.resources.ResourceLocation;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.goal.VillagerTask;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps one built-in Millénaire goal. Everything is delegated to the original; subclasses only
 * add an extra start condition for HYW threats. When the original can start, the original's own
 * task runs, so normal Millénaire behavior is preserved.
 */
abstract class BridgeDecorator implements VillagerGoal {
    protected final VillagerGoal original;
    final AtomicLong originalStarts = new AtomicLong();
    final AtomicLong bridgeStarts = new AtomicLong();
    private volatile boolean firstInvocationLogged;

    BridgeDecorator(VillagerGoal original) {
        this.original = original;
    }

    VillagerGoal original() {
        return original;
    }

    /** Extra condition, evaluated only when the original goal cannot start. Must be cheap (runs every tick). */
    protected abstract boolean bridgeCanStart(GoalContext ctx);

    protected abstract VillagerTask bridgeStart(GoalContext ctx);

    @Override
    public final boolean canStart(GoalContext ctx) {
        if (!firstInvocationLogged) {
            firstInvocationLogged = true;
            HmLog.info("Goal decorator {} is live (first canStart call observed).", id());
        }
        return original.canStart(ctx) || (ctx != null && ctx.village() != null && bridgeCanStart(ctx));
    }

    @Override
    public final VillagerTask start(GoalContext ctx) {
        if (original.canStart(ctx)) {
            originalStarts.incrementAndGet();
            return original.start(ctx);
        }
        bridgeStarts.incrementAndGet();
        return bridgeStart(ctx);
    }

    @Override public ResourceLocation id() { return original.id(); }
    @Override public int computePriority(GoalContext ctx) { return original.computePriority(ctx); }
    @Override public boolean isLeisure() { return original.isLeisure(); }
    @Override public boolean showInTravelBook() { return original.showInTravelBook(); }
    @Override public boolean canBeDoneAtNight() { return original.canBeDoneAtNight(); }
    @Override public boolean canBeDoneInDayTime() { return original.canBeDoneInDayTime(); }
    @Override public long reoccurDelayTicks() { return original.reoccurDelayTicks(); }
    @Override public boolean isCombatUrgent() { return original.isCombatUrgent(); }

    String stats() {
        return "original starts=" + originalStarts.get() + ", bridge starts=" + bridgeStarts.get();
    }
}

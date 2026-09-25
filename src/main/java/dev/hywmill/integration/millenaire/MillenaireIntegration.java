package dev.hywmill.integration.millenaire;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Integration;
import dev.hywmill.core.Services;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Registers the Millénaire settlement source and the goal bridge listener. */
public final class MillenaireIntegration implements Integration {
    @Override
    public String modId() {
        return "millenaire";
    }

    @Override
    public void init(IEventBus modBus) {
        Services.registerSettlements(new MillenaireSettlementSource());
        // LOW: must run after Millénaire's own NORMAL-priority ServerStartedEvent listener,
        // which calls GoalRegistry.resetToBuiltins().
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, false, ServerStartedEvent.class, MillenaireGoalBridge::onServerStarted);
        Services.putDiagnostic("millenaire.goalBridge", "pending (installs at ServerStartedEvent, priority LOW)");
        Services.putDiagnosticSupplier("millenaire.goalStats", MillenaireGoalBridge::liveStats);
        HmLog.info("Millénaire settlement source registered; goal bridge scheduled for ServerStartedEvent (LOW).");
    }
}

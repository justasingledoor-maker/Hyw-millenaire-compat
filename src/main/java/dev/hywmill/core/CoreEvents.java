package dev.hywmill.core;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.faction.FactionMarker;
import dev.hywmill.military.EscalationGuard;
import dev.hywmill.military.IncidentLedger;
import dev.hywmill.military.ThreatTracker;
import dev.hywmill.settlement.GarrisonUpdater;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Core NeoForge listeners. They only talk to foreign mods through {@link Services}; a
 * LinkageError from an integration disables the offending service instead of crashing the server.
 */
public final class CoreEvents {
    private CoreEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (Services.settlements() == null) {
            return;
        }
        ServerLevel overworld = event.getServer().overworld();
        long tick = overworld.getGameTime();
        guarded("ledger update", () -> {
            if (tick % HywMillConfig.LEDGER_UPDATE_INTERVAL.get() == 0) {
                GarrisonUpdater.update(overworld);
            }
        });
        guarded("threat scan", () -> {
            if (tick % HywMillConfig.THREAT_SCAN_INTERVAL.get() == 0) {
                ThreatTracker.scan(overworld);
                IncidentLedger.prune(tick);
            }
        });
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        guarded("identity marking", () -> FactionMarker.onJoin(entity));
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        guarded("incident ledger", () -> IncidentLedger.record(event.getEntity(), event.getSource(), event.getNewDamage())
                .ifPresent(inc -> {
                    Entity attacker = event.getSource().getEntity();
                    if (attacker != null) {
                        EscalationGuard.afterDamage(attacker, event.getEntity());
                    }
                }));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ThreatTracker.reset();
        IncidentLedger.reset();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        HywMillCommands.register(event.getDispatcher());
    }

    private static void guarded(String what, Runnable body) {
        try {
            body.run();
        } catch (LinkageError e) {
            HmLog.error("API mismatch during {}: {}. Disabling integrations to keep the server running.", what, e.toString(), e);
            Services.disableSettlements(e);
            Services.disableFactions(e);
        } catch (RuntimeException e) {
            HmLog.warnThrottled("err-" + what, 30_000L, "Error during {}: {}", what, e.toString());
            HmLog.LOG.debug("[hywmill] stack trace", e);
        }
    }
}

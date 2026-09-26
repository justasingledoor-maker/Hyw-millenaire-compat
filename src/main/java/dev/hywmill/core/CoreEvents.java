package dev.hywmill.core;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.faction.FactionMarker;
import dev.hywmill.military.EscalationGuard;
import dev.hywmill.settlement.GarrisonUpdater;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import dev.hywmill.faction.CombatFactionService;
import dev.hywmill.military.IncidentLedger;
import dev.hywmill.military.classify.RoleTableLoader;
import dev.hywmill.military.defense.DefenseStatsRecorder;
import dev.hywmill.military.doctrine.DoctrineLoader;
import dev.hywmill.garrison.tables.GarrisonTableLoader;
import dev.hywmill.settlement.SettlementSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import dev.hywmill.garrison.tag.GarrisonAttachments;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Core NeoForge listeners. They only talk to foreign mods through {@link Services}; a
 * LinkageError from an integration disables the offending service instead of crashing the server.
 */
public final class CoreEvents {
    private CoreEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        HywMillRuntime.start(event.getServer());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStopped(ServerStoppedEvent event) {
        HywMillRuntime.stop();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        HywMillRuntime rt = HywMillRuntime.get();
        if (rt == null || Services.settlements() == null) {
            return;
        }
        ServerLevel overworld = event.getServer().overworld();
        long tick = overworld.getGameTime();
        long t0 = rt.perf().start();
        guarded("ledger update", () -> GarrisonUpdater.tick(overworld, rt));
        guarded("relation reconciliation", () -> EscalationGuard.reconcile(rt, tick));
        guarded("threat scan", () -> {
            rt.threats().scan(overworld);
            if (tick % HywMillConfig.THREAT_SCAN_INTERVAL.get() == 0) {
                rt.incidents().prune(tick);
            }
        });
        rt.perf().stop("tick.total", t0);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        HywMillRuntime rt = HywMillRuntime.get();
        if (rt != null && event.getLevel() instanceof ServerLevel level && GarrisonAttachments.get(entity) != null) {
            guarded("garrison join", () -> {
                if (!rt.garrison().onJoin(entity, level)) {
                    event.setCanceled(true);
                }
            });
            if (event.isCanceled()) {
                return;
            }
        }
        guarded("identity marking", () -> FactionMarker.onJoin(entity));
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        HywMillRuntime rt = HywMillRuntime.get();
        if (rt == null || !(event.getLevel() instanceof ServerLevel level) || GarrisonAttachments.get(event.getEntity()) == null) {
            return;
        }
        guarded("garrison leave", () -> rt.garrison().onLeave(event.getEntity(), level));
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        HywMillRuntime rt = HywMillRuntime.get();
        if (rt == null || event.getEntity().level().isClientSide()) {
            return;
        }
        guarded("incident ledger", () -> rt.incidents().record(event.getEntity(), event.getSource(), event.getNewDamage())
                .ifPresent(inc -> {
                    Entity attacker = event.getSource().getEntity();
                    if (attacker != null) {
                        EscalationGuard.afterDamage(attacker, event.getEntity());
                        engageSignals(rt, inc, attacker, event.getEntity());
                    }
                }));
    }

    /**
     * Actual fighting between an HYW unit and a village moves the village to ENGAGED on the next
     * tick (not the next staggered scan), so the response to a real attack is never delayed.
     */
    private static void engageSignals(HywMillRuntime rt, IncidentLedger.Incident inc, Entity attacker, LivingEntity victim) {
        CombatFactionService factions = Services.factions();
        SettlementSource settlements = Services.settlements();
        if (factions == null || settlements == null) {
            return;
        }
        if (inc.victimResidentOf() != null && factions.isCombatUnit(attacker)) {
            rt.defense().engageSignal(inc.victimResidentOf());
        }
        if (inc.victimGarrisonOf() != null) {
            rt.defense().engageSignal(inc.victimGarrisonOf());
        }
        if (factions.isCombatUnit(victim)) {
            settlements.residentInfo(attacker).filter(r -> !r.raider())
                    .ifPresent(r -> rt.defense().engageSignal(r.settlementId()));
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (HywMillRuntime.get() == null || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        guarded("defense statistics", () -> DefenseStatsRecorder.onDeath(level, event.getEntity(), event.getSource().getEntity()));
        if (GarrisonAttachments.get(event.getEntity()) != null) {
            guarded("garrison death", () -> HywMillRuntime.require().garrison().onDeath(event.getEntity(), level));
        }
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new RoleTableLoader());
        event.addListener(new DoctrineLoader());
        event.addListener(new GarrisonTableLoader());
        event.addListener(new dev.hywmill.garrison.duty.DutyTableLoader());
        event.addListener(new dev.hywmill.garrison.equip.EquipmentProfileLoader());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        HywMillCommands.register(event.getDispatcher());
    }

    static void guarded(String what, Runnable body) {
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

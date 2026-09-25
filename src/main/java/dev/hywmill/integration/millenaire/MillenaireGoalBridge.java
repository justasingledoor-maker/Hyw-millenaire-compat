package dev.hywmill.integration.millenaire;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.millenaire.Millenaire;
import org.millenaire.culture.VillagerType;
import org.millenaire.entity.MillVillager;
import org.millenaire.goal.GoalRegistry;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.goal.impl.EngageTargetGoal;
import org.millenaire.goal.impl.HideGoal;
import org.millenaire.goal.impl.HuntMonsterGoal;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Timing (verified in Millénaire 9.0.2 bytecode):
 * <ol>
 *   <li>Millénaire's mod constructor registers built-in goals and calls GoalRegistry.freezeBuiltins().</li>
 *   <li>Its ServerStartedEvent listener (priority NORMAL) calls resetToBuiltins() — discarding any
 *       earlier replacement — then loads content, then initGoals() on every loaded villager.</li>
 *   <li>Its EntityJoinLevelEvent listener only calls initGoals() when a villager has no scheduler;
 *       VillagerSpawnFactory calls initGoals() for every spawned/resurrected villager.</li>
 * </ol>
 * So we replace from a ServerStartedEvent listener at priority LOW (after step 2) and re-run
 * initGoals() on villagers already loaded. Every later initGoals() resolves our decorators
 * because MillVillager.initGoals looks goals up by id in the registry.
 */
final class MillenaireGoalBridge {
    private static final List<BridgeDecorator> INSTALLED = new ArrayList<>();

    private MillenaireGoalBridge() {}

    static void onServerStarted(ServerStartedEvent event) {
        try {
            install(event.getServer().overworld());
        } catch (LinkageError e) {
            HmLog.error("Goal bridge could not be installed (Millénaire API mismatch): {}", e.toString(), e);
            Services.putDiagnostic("millenaire.goalBridge", "FAILED: " + e);
        }
    }

    private static void install(ServerLevel overworld) {
        INSTALLED.clear();
        GoalRegistry registry = Millenaire.getGoalRegistry();
        if (registry == null) {
            report("FAILED: Millenaire.getGoalRegistry() returned null at ServerStartedEvent(LOW)");
            return;
        }
        List<String> results = new ArrayList<>();
        if (HywMillConfig.ENGAGE_BRIDGE.get()) {
            results.add(replace(registry, EngageTargetGoal.ID, EngageTargetDecorator::new));
        }
        if (HywMillConfig.HUNT_BRIDGE.get()) {
            results.add(replace(registry, HuntMonsterGoal.ID, HuntMonsterDecorator::new));
        }
        if (HywMillConfig.HIDE_BRIDGE.get()) {
            results.add(replace(registry, HideGoal.ID, HideDecorator::new));
        }
        int reinit = reinitLoadedVillagers(overworld, registry);
        report(String.join("; ", results) + "; loaded villagers re-initialized: " + reinit);
    }

    private static String replace(GoalRegistry registry, ResourceLocation id, Function<VillagerGoal, BridgeDecorator> factory) {
        VillagerGoal current = registry.get(id);
        if (current == null) {
            HmLog.error("Goal {} not found in Millénaire registry; bridge for it NOT installed.", id);
            return id + "=MISSING";
        }
        VillagerGoal original = current instanceof BridgeDecorator d ? d.original() : current;
        BridgeDecorator decorator = factory.apply(original);
        registry.replace(decorator);
        VillagerGoal after = registry.get(id);
        boolean ok = after == decorator;
        if (ok) {
            INSTALLED.add(decorator);
            HmLog.info("Goal replacement initialized: {} {} -> {}", id, original.getClass().getName(), decorator.getClass().getSimpleName());
        } else {
            HmLog.error("Goal replacement for {} did not take effect (registry returns {}).", id, after == null ? "null" : after.getClass().getName());
        }
        return id + (ok ? "=bridged" : "=REPLACE_FAILED");
    }

    private static int reinitLoadedVillagers(ServerLevel level, GoalRegistry registry) {
        int n = 0;
        for (Entity e : level.getAllEntities()) {
            if (e instanceof MillVillager mv && mv.getVillagerTypeId() != null) {
                VillagerType type = MillTypes.typeOf(mv);
                if (type != null) {
                    mv.initGoals(registry, type);
                    n++;
                }
            }
        }
        HmLog.info("Re-initialized goals of {} already-loaded Millénaire villager(s) so they use the bridged goals.", n);
        return n;
    }

    private static void report(String status) {
        Services.putDiagnostic("millenaire.goalBridge", status);
        HmLog.info("Goal bridge status: {}", status);
    }

    static String liveStats() {
        StringBuilder sb = new StringBuilder();
        for (BridgeDecorator d : INSTALLED) {
            sb.append(d.id()).append(" {").append(d.stats()).append("} ");
        }
        return sb.toString().trim();
    }
}

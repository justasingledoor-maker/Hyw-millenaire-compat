package dev.hywmill.core;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads optional integrations by class name so that no foreign class is touched unless the
 * foreign mod is present. Any {@link LinkageError} (API drift) disables that integration.
 */
public final class Integrations {
    private static final Map<String, String> STATE = new LinkedHashMap<>();

    private Integrations() {}

    public static void loadAll(IEventBus modBus) {
        load("millenaire", "dev.hywmill.integration.millenaire.MillenaireIntegration", modBus);
        load("hundred_years_war", "dev.hywmill.integration.hyw.HywIntegration", modBus);
    }

    private static void load(String modId, String className, IEventBus modBus) {
        if (!ModList.get().isLoaded(modId)) {
            STATE.put(modId, "absent (integration disabled)");
            HmLog.info("{} not loaded; its integration is disabled.", modId);
            return;
        }
        try {
            Integration integration = (Integration) Class.forName(className).getDeclaredConstructor().newInstance();
            integration.init(modBus);
            STATE.put(modId, "enabled");
            HmLog.info("{} integration enabled.", modId);
        } catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
            STATE.put(modId, "FAILED: " + e);
            HmLog.error("{} integration failed to initialize and is disabled: {}", modId, e.toString(), e);
        }
    }

    /** Called by an integration whose runtime calls hit API drift. */
    public static void markFailed(String modId, Throwable t) {
        STATE.put(modId, "FAILED at runtime: " + t);
    }

    public static String describe() {
        return STATE.toString();
    }
}

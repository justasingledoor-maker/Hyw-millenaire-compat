package dev.hywmill;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.CoreEvents;
import dev.hywmill.core.Integrations;
import dev.hywmill.core.HmLog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Entry point. Only core classes are referenced here; foreign-mod integrations are
 * loaded reflectively by {@link Integrations} after checking {@code ModList}.
 */
@Mod(HywMill.MODID)
public final class HywMill {
    public static final String MODID = "hywmill";

    public HywMill(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, HywMillConfig.SPEC);
        NeoForge.EVENT_BUS.register(CoreEvents.class);
        Integrations.loadAll(modBus);
        HmLog.info("Initialized. Integrations: {}", Integrations.describe());
    }
}

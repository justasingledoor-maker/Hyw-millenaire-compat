package dev.hywmill.core;

import net.neoforged.bus.api.IEventBus;

/**
 * An optional foreign-mod integration. Implementations live in {@code dev.hywmill.integration.*}
 * and are the only classes allowed to import foreign-mod types.
 */
public interface Integration {
    String modId();

    /** Called once from the mod constructor, only when the foreign mod is loaded. */
    void init(IEventBus modBus);
}

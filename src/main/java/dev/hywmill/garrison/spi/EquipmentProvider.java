package dev.hywmill.garrison.spi;

import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;

/**
 * Applies equipment to a freshly created garrison unit. M3 ships only the HYW provider, which
 * delegates to HYW's own per-unit equipment files. Equipment is applied once, at spawn.
 */
public interface EquipmentProvider {
    String id();

    boolean available();

    /** The level to request for this unit at this tier (pure; no entity access). */
    int resolveLevel(UnitSpec unit, MilitaryTier tier, int requestedLevel);

    /** Applies {@code level} to {@code entity} and returns the level actually applied. */
    int apply(Object entity, UnitSpec unit, int level);
}

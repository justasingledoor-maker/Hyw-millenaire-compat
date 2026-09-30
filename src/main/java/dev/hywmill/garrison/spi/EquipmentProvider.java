package dev.hywmill.garrison.spi;

import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;

import java.util.UUID;

/**
 * Applies equipment to a garrison unit. M3 ships the HYW provider, which delegates to HYW's own
 * per-unit equipment files and applies once, at spawn. M4 adds an optional profile provider
 * that overlays culture/tier/role equipment and re-applies when a unit's duty role changes
 * ({@link #reequips()}).
 */
public interface EquipmentProvider {
    /**
     * What the unit is equipped for (M4): its village's culture and tier, its duty role
     * ({@code sentry}, {@code patrol}, {@code scout}, {@code reserve}, or empty) and its class role
     * ({@code militia}, {@code line}, {@code ranged}); {@code rosterId} makes item choices deterministic.
     */
    record Context(String culture, MilitaryTier tier, String dutyRole, String classRole, UUID rosterId, int liveryPrimary, int liverySecondary) {
        /** No village livery (-1): per-unit colours, or a squad's own look. */
        public Context(String culture, MilitaryTier tier, String dutyRole, String classRole, UUID rosterId) {
            this(culture, tier, dutyRole, classRole, rosterId, -1, -1);
        }

        /**
         * Post-M5: the same context in a village's livery ({@code livery} = {primary, secondary} dye ids, or null for none):
         * the unit wears the village's two colours and bears its colours on its shield.
         */
        public Context withLivery(@javax.annotation.Nullable int[] livery) {
            return livery == null ? this : new Context(culture, tier, dutyRole, classRole, rosterId, livery[0], livery[1]);
        }

        public boolean hasLivery() {
            return liveryPrimary >= 0 && liverySecondary >= 0;
        }
    }

    String id();

    boolean available();

    /** The level to request for this unit at this tier (pure; no entity access). */
    int resolveLevel(UnitSpec unit, MilitaryTier tier, int requestedLevel);

    /** Applies {@code level} to {@code entity} and returns the level actually applied. */
    int apply(Object entity, UnitSpec unit, int level);

    /** Applies {@code level} for {@code context}; providers without profiles ignore the context. */
    default int apply(Object entity, UnitSpec unit, int level, Context context) {
        return apply(entity, unit, level);
    }

    /**
     * Validation report of the provider's profile data against {@code units} (M4 {@code dev equipcheck}):
     * one line per problem, then a summary line. Providers without profiles return an empty list.
     */
    default java.util.List<String> validate(java.util.Collection<UnitSpec> units) {
        return java.util.List.of();
    }

    /** Whether equipment depends on the duty role, so it must be re-applied when that changes. */
    default boolean reequips() {
        return false;
    }
}

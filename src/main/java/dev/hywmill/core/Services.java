package dev.hywmill.core;

import dev.hywmill.faction.CombatFactionService;
import dev.hywmill.settlement.SettlementSource;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Service registry filled by integrations. Core code only sees these interfaces.
 * A service is null when its mod is absent or its integration failed.
 */
public final class Services {
    @Nullable private static volatile SettlementSource settlements;
    @Nullable private static volatile CombatFactionService factions;
    private static final Map<String, Supplier<String>> DIAGNOSTICS = new LinkedHashMap<>();

    private Services() {}

    @Nullable
    public static SettlementSource settlements() {
        return settlements;
    }

    @Nullable
    public static CombatFactionService factions() {
        return factions;
    }

    public static void registerSettlements(SettlementSource source) {
        settlements = source;
    }

    public static void registerFactions(CombatFactionService service) {
        factions = service;
    }

    /** Disables a service after a runtime LinkageError so we fail soft instead of crashing ticks. */
    public static void disableSettlements(Throwable cause) {
        HmLog.error("Disabling settlement source after runtime failure: {}", cause.toString(), cause);
        settlements = null;
        Integrations.markFailed("millenaire", cause);
    }

    public static void disableFactions(Throwable cause) {
        HmLog.error("Disabling HYW faction service after runtime failure: {}", cause.toString(), cause);
        factions = null;
        Integrations.markFailed("hundred_years_war", cause);
    }

    public static synchronized void putDiagnostic(String key, String value) {
        DIAGNOSTICS.put(key, () -> value);
    }

    /** Live diagnostic, evaluated when /hywmill status runs. */
    public static synchronized void putDiagnosticSupplier(String key, Supplier<String> value) {
        DIAGNOSTICS.put(key, value);
    }

    public static synchronized Map<String, String> diagnostics() {
        Map<String, String> out = new LinkedHashMap<>();
        DIAGNOSTICS.forEach((k, v) -> {
            String value;
            try {
                value = v.get();
            } catch (RuntimeException | LinkageError e) {
                value = "error: " + e;
            }
            out.put(k, value);
        });
        return out;
    }
}

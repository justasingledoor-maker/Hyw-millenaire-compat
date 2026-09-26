package dev.hywmill.core;

import dev.hywmill.garrison.spi.EquipmentProvider;
import dev.hywmill.garrison.spi.UnitProvider;
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
    @Nullable private static volatile UnitProvider units;
    private static final Map<String, EquipmentProvider> EQUIPMENT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Supplier<String>> DIAGNOSTICS = new LinkedHashMap<>();
    /** M5-0 spike command subtrees contributed by integrations (dev only; added under /hywmill dev m5). */
    private static final java.util.List<Supplier<com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>>> SPIKE_COMMANDS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private Services() {}

    @Nullable
    public static SettlementSource settlements() {
        return settlements;
    }

    @Nullable
    public static CombatFactionService factions() {
        return factions;
    }

    /** Garrison unit provider (HYW), null when HYW is absent or failed. */
    @Nullable
    public static UnitProvider units() {
        return units;
    }

    public static void registerUnits(UnitProvider provider) {
        units = provider;
    }

    public static void registerEquipment(EquipmentProvider provider) {
        EQUIPMENT.put(provider.id(), provider);
    }

    /** The equipment provider with this id if registered and available, else null. */
    @Nullable
    public static EquipmentProvider equipment(String id) {
        EquipmentProvider p = EQUIPMENT.get(id);
        return p != null && p.available() ? p : null;
    }

    public static void registerSettlements(SettlementSource source) {
        settlements = source;
    }

    public static void registerSpikeCommands(Supplier<com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>> node) {
        SPIKE_COMMANDS.add(node);
    }

    public static java.util.List<Supplier<com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>>> spikeCommands() {
        return SPIKE_COMMANDS;
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
        units = null;
        EQUIPMENT.clear();
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

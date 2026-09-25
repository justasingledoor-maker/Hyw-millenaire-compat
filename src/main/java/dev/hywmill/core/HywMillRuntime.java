package dev.hywmill.core;

import dev.hywmill.faction.FactionRegistry;
import dev.hywmill.military.DiplomacyPolicy;
import dev.hywmill.military.IncidentLedger;
import dev.hywmill.military.ThreatTracker;
import dev.hywmill.settlement.SettlementSource;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * All per-server runtime state. Created at ServerAboutToStartEvent (before any level or entity
 * loads) and discarded at ServerStoppedEvent, so an integrated server that opens several worlds
 * in one JVM never sees state from a previous world.
 *
 * <p>Mod-lifetime state (registered integrations, config) stays in {@link Services}; persistent
 * state stays in SavedData.
 */
public final class HywMillRuntime {
    @Nullable private static volatile HywMillRuntime current;

    private final MinecraftServer server;
    private final VillageScheduler scheduler = new VillageScheduler();
    private final IncidentLedger incidents = new IncidentLedger();
    private final ThreatTracker threats = new ThreatTracker(incidents, scheduler);
    private final FactionRegistry factions = new FactionRegistry();
    private final DiplomacyPolicy diplomacy = DiplomacyPolicy.ALWAYS_REVERT;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    /** Village list cache, refreshed once per ledger interval by GarrisonUpdater. Server thread only. */
    @Nullable public List<SettlementSource.SettlementRef> cachedVillages;

    private HywMillRuntime(MinecraftServer server) {
        this.server = server;
        incidents.bind(threats);
    }

    static void start(MinecraftServer server) {
        if (current != null) {
            HmLog.warn("Runtime context replaced without a stop event; discarding the previous one.");
        }
        current = new HywMillRuntime(server);
        HmLog.info("Runtime context created for server {}", System.identityHashCode(server));
    }

    static void stop() {
        if (current != null) {
            HmLog.info("Runtime context discarded.");
        }
        current = null;
    }

    /** The current server's runtime, or null outside a running server. */
    @Nullable
    public static HywMillRuntime get() {
        return current;
    }

    /** For command paths, which only run while a server is up. */
    public static HywMillRuntime require() {
        HywMillRuntime rt = current;
        if (rt == null) {
            throw new IllegalStateException("hywmill runtime is not running");
        }
        return rt;
    }

    public MinecraftServer server() {
        return server;
    }

    public VillageScheduler scheduler() {
        return scheduler;
    }

    public IncidentLedger incidents() {
        return incidents;
    }

    public ThreatTracker threats() {
        return threats;
    }

    public FactionRegistry factions() {
        return factions;
    }

    public DiplomacyPolicy diplomacy() {
        return diplomacy;
    }

    public long increment(String counter) {
        return counters.computeIfAbsent(counter, k -> new AtomicLong()).incrementAndGet();
    }

    public long add(String counter, long delta) {
        return counters.computeIfAbsent(counter, k -> new AtomicLong()).addAndGet(delta);
    }

    public long counter(String counter) {
        AtomicLong v = counters.get(counter);
        return v == null ? 0 : v.get();
    }

    public Map<String, Long> counters() {
        Map<String, Long> out = new TreeMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}

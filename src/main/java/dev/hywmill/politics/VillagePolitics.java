package dev.hywmill.politics;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One village's political memory: per-player records (non-default only) and dated truces with other
 * villages. Stored in the village's {@code VillageRecord} (ledger format 5).
 */
public final class VillagePolitics {
    private final Map<UUID, PoliticsRecord> players = new LinkedHashMap<>();
    /** Other village → truce end tick (game time). */
    private final Map<UUID, Long> truces = new LinkedHashMap<>();
    /** HywMill's persisted chronicle (Millénaire's own history is session-only); newest last, bounded. */
    private final java.util.ArrayDeque<ChronicleEntry> chronicle = new java.util.ArrayDeque<>();
    public static final int CHRONICLE_SIZE = 64;

    public record ChronicleEntry(long tick, String text) {}

    public void chronicle(long tick, String text) {
        chronicle.addLast(new ChronicleEntry(tick, text));
        while (chronicle.size() > CHRONICLE_SIZE) {
            chronicle.removeFirst();
        }
    }

    public java.util.List<ChronicleEntry> chronicle() {
        return java.util.List.copyOf(chronicle);
    }

    public PoliticsRecord get(UUID player) {
        return players.computeIfAbsent(player, p -> new PoliticsRecord());
    }

    public PoliticsRecord peek(UUID player) {
        return players.get(player);
    }

    public Map<UUID, PoliticsRecord> players() {
        return players;
    }

    public Map<UUID, Long> truces() {
        return truces;
    }

    public void setTruce(UUID other, long untilTick) {
        truces.merge(other, untilTick, Math::max);
    }

    public boolean truceWith(UUID other, long now) {
        Long until = truces.get(other);
        return until != null && until > now;
    }

    /** Drops default player records and expired truces; returns how many entries were removed. */
    public int prune(long now) {
        int n = 0;
        for (Iterator<PoliticsRecord> it = players.values().iterator(); it.hasNext(); ) {
            if (it.next().isDefault()) {
                it.remove();
                n++;
            }
        }
        for (Iterator<Long> it = truces.values().iterator(); it.hasNext(); ) {
            if (it.next() <= now) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    /** Nothing worth persisting: no non-default player record and no truce. */
    public boolean isEmpty() {
        return truces.isEmpty() && chronicle.isEmpty() && players.values().stream().allMatch(PoliticsRecord::isDefault);
    }
}

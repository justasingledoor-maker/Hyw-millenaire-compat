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
        return truces.isEmpty() && players.values().stream().allMatch(PoliticsRecord::isDefault);
    }
}

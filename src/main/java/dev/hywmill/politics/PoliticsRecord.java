package dev.hywmill.politics;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Everything one village remembers about one player (persisted only when not default). */
public final class PoliticsRecord {
    public Standing status = Standing.STRANGER;
    public long statusSince;
    public Grievances grievances = new Grievances();
    public Favor favor = new Favor();
    /** Last request (escort, detachment, intelligence) granted; -1 never. */
    public long lastRequestTick = -1;
    /** Soldiers of this village lost on this player's errands (Favor cost, M5-5). */
    public int casualtiesOnErrands;
    /** Last envoy proposal per target village (cooldowns, M5-4). */
    public final Map<UUID, Long> lastProposal = new HashMap<>();

    /** A default record carries no information and is pruned. */
    public boolean isDefault() {
        return status == Standing.STRANGER && grievances.isEmpty() && favor.isEmpty() && lastRequestTick < 0
                && casualtiesOnErrands == 0 && lastProposal.isEmpty();
    }

    /**
     * Re-evaluates the status at {@code now} from the combined reputation; returns true if it changed.
     * A pardon clears the peacetime-killing flag.
     */
    public boolean refresh(long now, int reputation, PoliticsTables tables) {
        double g = grievances.decayed(now, tables.grievance());
        Standing.Result r = Standing.evaluate(status, reputation, g, grievances.peacetimeKillPending(),
                favor.points(), favor.earnedTotal(), tables);
        if (r.pardoned()) {
            grievances.clearPeacetimeKill();
        }
        if (r.standing() != status) {
            status = r.standing();
            statusSince = now;
            return true;
        }
        return false;
    }
}

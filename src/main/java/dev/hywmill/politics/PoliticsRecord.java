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
    /** Last request (detachment) granted; -1 never. */
    public long lastRequestTick = -1;
    /** Soldiers of this village lost on this player's errands (Favor cost, M5-5). */
    public int casualtiesOnErrands;
    /** Last envoy proposal per target village (cooldowns, M5-4). */
    public final Map<UUID, Long> lastProposal = new HashMap<>();
    /** M5-4: last sow-discord attempt sponsored through this village (-1 never) and the recent attempt count. */
    public long lastSowDiscord = -1;
    public int sowAttempts;
    /** M5-5: tick a soldier of this village last died on the player's errand (-1 never); an errand ending after it without losses earns Favor. */
    public long lastErrandLoss = -1;
    /** M5-5: last monthly long-standing Favor trickle (-1: not yet started). */
    public long lastTrickle = -1;
    /** Tick of the player's last raid counsel to this village (-1: never; post-M5). */
    public long lastRaidCounsel = -1;

    /** Sow-discord attempts that still count at {@code now} (older than {@code window}: none). */
    public int recentSowAttempts(long now, long window) {
        return lastSowDiscord < 0 || now - lastSowDiscord > window ? 0 : sowAttempts;
    }

    /** A default record carries no information and is pruned. */
    public boolean isDefault() {
        return status == Standing.STRANGER && grievances.isEmpty() && favor.isEmpty() && lastRequestTick < 0
                && casualtiesOnErrands == 0 && lastProposal.isEmpty() && lastSowDiscord < 0 && lastErrandLoss < 0 && lastTrickle < 0
                && lastRaidCounsel < 0;
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

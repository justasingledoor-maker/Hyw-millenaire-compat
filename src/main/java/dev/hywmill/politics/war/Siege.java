package dev.hywmill.politics.war;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One siege (post-M5; docs/siege-design.md): a host from {@code attacker}'s garrison against {@code target}. Pure and
 * mutable; persisted at ledger level. The host's roster slots live in the attacker's roster (duty SIEGE); while marching
 * they are stowed (no entity).
 */
public final class Siege {
    public enum Phase { MUSTER, MARCH, WAIT, BATTLE, RETURN }

    public enum Outcome { NONE, WON, LOST }

    public final UUID id;
    public final UUID attacker;
    public final UUID target;
    /** The player whose counsel launched it; null when the village decided on its own (or an admin launched it). */
    @Nullable public final UUID counsel;
    public final long launched;
    public Phase phase = Phase.MUSTER;
    public long phaseSince;
    /** End of the current timed phase (muster end, arrival, wait end, battle deadline, return arrival). */
    public long phaseEnd;
    public Outcome outcome = Outcome.NONE;
    public final List<UUID> host = new ArrayList<>();
    public int hostStart;
    public int defendersStart;
    public final Set<UUID> attackerHelpers = new LinkedHashSet<>();
    public final Set<UUID> defenderHelpers = new LinkedHashSet<>();
    /** One line on how it ended (for the chronicle, status and the report). */
    public String summary = "";

    public Siege(UUID id, UUID attacker, UUID target, @Nullable UUID counsel, long launched) {
        this.id = id;
        this.attacker = attacker;
        this.target = target;
        this.counsel = counsel;
        this.launched = launched;
        this.phaseSince = launched;
    }

    public void enter(Phase p, long now, long end) {
        phase = p;
        phaseSince = now;
        phaseEnd = end;
    }

    public boolean involves(UUID village) {
        return attacker.equals(village) || target.equals(village);
    }

    /** A seed for this siege's draws (stable across restarts). */
    public long seed(long salt) {
        return id.getMostSignificantBits() ^ id.getLeastSignificantBits() ^ salt;
    }
}

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
    /** PREPARE (post-M5): the build-up of two days after the siege is announced; the host musters on the third and arrives at dawn. */
    /** NIGHT (post-M5): between two waves; the host has withdrawn, the wounded are tended. */
    public enum Phase { PREPARE, MUSTER, MARCH, WAIT, BATTLE, NIGHT, RETURN }

    /** STALEMATE (post-M5): three waves and neither side broke; the attackers go home, nothing is paid. */
    public enum Outcome { NONE, WON, LOST, STALEMATE }

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
    /** Tick a watched battle paused because nobody was near (-1: not paused). */
    public long pausedSince = -1;
    /** DEV/admin test switch: treat the target as unwatched (unloaded) even when it is loaded. */
    public boolean forceUnwatched;
    /** Post-M5: relief forces sent to the target by villages on great terms with it. */
    public final List<Relief> reliefs = new ArrayList<>();
    /**
     * Post-M5 timing: the day time at the announcement, and when (ticks after it) the host arrives, at dawn; its march's length.
     * Time is the further of game time and day time since the announcement, so sleeping through a night moves it on, and it
     * still moves without a daylight cycle. {@code quick} (admin): no build-up, the old muster and march.
     */
    public long startDay;
    public long arriveAt;
    public long march;
    public boolean quick = true;
    /** Men who reached the attacker before it mustered (mercenaries, vassals' men), joining its host at the muster. */
    public final List<String> pendingUnits = new ArrayList<>();
    public final List<Boolean> pendingRegular = new ArrayList<>();
    public final List<String> pendingLook = new ArrayList<>();
    public final List<String> pendingKind = new ArrayList<>();
    /** Post-M5: the mercenary roll before the assault was made. */
    public boolean mercRolled;
    /** The company hired for this siege ("" none) and how many it brought. */
    public String mercCompany = "";
    public int mercCount;
    /** Post-M5: the defenders' help (militia, mercenaries, the lord's household) was rolled; the temporary slots it raised in the target's roster. */
    public boolean aidRolled;
    /** Post-M5: vassals were asked for help; what helped this siege (mercenaries, militia, household, vassals) for its report. */
    public boolean vassalRolled;
    public final List<String> notes = new ArrayList<>();
    /** Defenders fallen, set as the siege is decided (for its report; not persisted). -1: unknown. */
    public int defLost = -1;
    public final List<UUID> extras = new ArrayList<>();
    /**
     * Post-M5 waves ({@link SiegeWaves}): the wave being fought (0: none yet; 1-3); whether this wave is fought in the world
     * (else on paper, far from any witness); the target's Millénaire fighters at the first dawn (they are not on any roster);
     * this wave's toll so far (dead and wounded, each side), and the whole siege's.
     */
    public int wave;
    public boolean field;
    public int milStart;
    public int hostDeadW, hostHurtW, defDeadW, defHurtW;
    public int hostDead, hostHurt, defDead, defHurt;
    /** The target's Millénaire fighters killed in this siege: villagers Millénaire brings back do not fill the ranks again. */
    public int milKilled;
    /**
     * Reserves (fix52) of a wave fought in the world: the host's men held back at its camp and the defenders' held inside (both
     * stowed), fed in as their side's front thins and all by noon; the wave they were picked for; each front's size when it did.
     */
    public final java.util.Set<UUID> hostReserve = new java.util.LinkedHashSet<>(), defReserve = new java.util.LinkedHashSet<>();
    public int reserveWave, hostFront, defFront;

    /** A wave is being fought or the night between two (no help may join any more; no recruiting at the target). */
    public boolean fighting() {
        return phase == Phase.BATTLE || phase == Phase.NIGHT;
    }

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

    /** All relief forces are home (the siege record may go once its own host is home too). */
    public boolean reliefsDone() {
        for (Relief r : reliefs) {
            if (r.phase != Relief.Phase.DONE) {
                return false;
            }
        }
        return true;
    }

    /** Ticks since the announcement: the further of game time and day time (sleep moves day time on). */
    public long elapsed(long gameTime, long dayTime) {
        return Math.max(gameTime - launched, dayTime - startDay);
    }

    /** A seed for this siege's draws (stable across restarts). */
    public long seed(long salt) {
        return id.getMostSignificantBits() ^ id.getLeastSignificantBits() ^ salt;
    }
}

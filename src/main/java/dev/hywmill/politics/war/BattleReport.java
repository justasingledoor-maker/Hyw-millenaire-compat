package dev.hywmill.politics.war;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A finished siege as the Politics screen's History tab shows it (post-M5): who fought whom, how many on each side and how
 * many fell, who helped (mercenaries, militia, the lord's household, relief forces, vassals) and what the loser pays. Names
 * are kept (a village may be gone later). Pure; persisted at ledger level, newest last, at most {@link #KEEP}.
 */
public final class BattleReport {
    public static final int KEEP = 60;

    public final long tick;
    public final UUID attackerId, targetId;
    public final String attacker, target;
    /** WON (the attacker took the village), LOST (it held) or STALEMATE (three days, neither broke). */
    public final String outcome;
    public final int hostStart, hostLost, defStart, defLost;
    /** Fought in sight of a player (else decided far from any witness). */
    public final boolean watched;
    public final List<String> notes = new ArrayList<>();
    public String tribute = "";

    public BattleReport(long tick, UUID attackerId, UUID targetId, String attacker, String target, String outcome, int hostStart, int hostLost,
                        int defStart, int defLost, boolean watched) {
        this.tick = tick;
        this.attackerId = attackerId;
        this.targetId = targetId;
        this.attacker = attacker;
        this.target = target;
        this.outcome = outcome;
        this.hostStart = hostStart;
        this.hostLost = hostLost;
        this.defStart = defStart;
        this.defLost = defLost;
        this.watched = watched;
    }

    public boolean involves(UUID village) {
        return attackerId.equals(village) || targetId.equals(village);
    }

    /** The report as text lines, the first a heading. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add("Day " + (tick / Tribute.DAY + 1) + ": " + attacker + " besieged " + target + " - "
                + (outcome.equals("WON") ? target + " fell" : outcome.equals("STALEMATE") ? "stalemate: neither broke, the attackers went home" : target + " held"));
        out.add("  Attackers " + hostStart + " (" + Math.max(0, hostLost) + " fell), defenders " + (defStart < 0 ? "?" : defStart)
                + " (" + (defLost < 0 ? "?" : defLost) + " fell); " + (watched ? "fought in sight" : "far from any witness"));
        for (String n : notes) {
            out.add("  - " + n);
        }
        if (!tribute.isEmpty()) {
            out.add("  Tribute: " + tribute);
        }
        return out;
    }
}

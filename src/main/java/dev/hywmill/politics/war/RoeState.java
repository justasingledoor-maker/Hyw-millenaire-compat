package dev.hywmill.politics.war;

import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.military.doctrine.MilitiaPolicy;

/**
 * Rules of engagement (M5-5b): who among an enemy village's residents is a combatant. Civilians are
 * never targets; combatant villagers are engaged only through HYW temporary hostility, never by relation.
 */
public final class RoeState {
    private RoeState() {}

    /** SOLDIER and LEADER always; MILITIA unless the target village's doctrine never uses its militia. */
    public static boolean combatant(VillagerRole role, MilitiaPolicy targetPolicy) {
        return switch (role) {
            case SOLDIER, LEADER -> true;
            case MILITIA -> targetPolicy != MilitiaPolicy.NEVER;
            case OUTLAW, CIVILIAN -> false;
        };
    }
}

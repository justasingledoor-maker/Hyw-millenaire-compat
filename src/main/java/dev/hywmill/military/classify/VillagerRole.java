package dev.hywmill.military.classify;

/**
 * Military role of one villager type.
 * <ul>
 *   <li>SOLDIER / LEADER: only from the explicit role table.</li>
 *   <li>OUTLAW: Millénaire {@code hostile} tag (bandits and similar), independent of the table.</li>
 *   <li>MILITIA: fallback for {@code helpInAttacks} types not in the table.</li>
 *   <li>CIVILIAN: everything else, and children.</li>
 * </ul>
 */
public enum VillagerRole {
    SOLDIER, LEADER, MILITIA, OUTLAW, CIVILIAN;

    /** Counts toward the village's defenders (Millénaire helpInAttacks semantics, excluding outlaws). */
    public boolean isDefender() {
        return this == SOLDIER || this == LEADER || this == MILITIA;
    }
}

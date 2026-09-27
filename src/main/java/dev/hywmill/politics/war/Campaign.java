package dev.hywmill.politics.war;

import java.util.UUID;

/**
 * A player fighting for {@code ally} against {@code enemy} in their war (M5-5b), until {@code until}.
 * While it lasts the player is FRIENDLY with the ally's faction and HOSTILE with the enemy's faction
 * (projection), and an enemy combatant of the enemy village. One campaign per player.
 */
public record Campaign(UUID player, UUID ally, UUID enemy, long since, long until) {
    public boolean active(long now) {
        return now < until;
    }
}

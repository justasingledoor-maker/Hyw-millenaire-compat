package dev.hywmill.military.defense;

import dev.hywmill.military.doctrine.Doctrine;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Whether the village's defenders help a player an HYW unit is attacking inside the defense
 * radius. Pure (unit-tested).
 * <ul>
 *   <li>The village controller (player-controlled villages) with {@code assistController=ALWAYS}
 *       is always helped, whatever the reputation or who struck first; {@code NEVER} is never
 *       helped; {@code AS_PLAYER} falls through to the ordinary rules.</li>
 *   <li>A player who struck the unit first is not helped unless {@code assistProvokingPlayer}.</li>
 *   <li>{@code assistPlayers}: NEVER, ALWAYS, or MIN_REPUTATION (Millénaire reputation ≥ threshold).</li>
 * </ul>
 * The controller is matched against {@code controllerPlayerId}, never against the faction identity.
 */
public final class AssistPolicy {
    private AssistPolicy() {}

    public static boolean qualifies(Doctrine d, UUID player, @Nullable UUID controller, int reputation, boolean playerStruckFirst) {
        if (controller != null && controller.equals(player)) {
            switch (d.assistController()) {
                case ALWAYS -> {
                    return true;
                }
                case NEVER -> {
                    return false;
                }
                default -> {
                }
            }
        }
        if (playerStruckFirst && !d.assistProvokingPlayer()) {
            return false;
        }
        return switch (d.assistPlayers()) {
            case NEVER -> false;
            case ALWAYS -> true;
            case MIN_REPUTATION -> reputation >= d.assistMinReputation();
        };
    }
}

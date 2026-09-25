package dev.hywmill.military.doctrine;

/** How the controlling player of a player-controlled village is assisted. */
public enum ControllerAssist {
    NEVER,
    /** Same rules as any other player (reputation, provoking-player rule). */
    AS_PLAYER,
    /** Always assisted, regardless of reputation and of who struck first. */
    ALWAYS
}

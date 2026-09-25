package dev.hywmill.military.doctrine;

/**
 * When MILITIA residents (helpInAttacks, not SOLDIER/LEADER) become assignable to a threat.
 * "Assignable" never overrides {@code proactive=false}: militia only ever engage reactive threats.
 */
public enum MilitiaPolicy {
    /** Never assigned by HywMill (self-defense still applies). */
    NEVER,
    /** Only while residents are actually being attacked (a threat damaged a resident recently). */
    WHEN_ATTACKED,
    /** Once the village is ENGAGED. */
    ON_ENGAGED,
    /** Whenever the village is ALERT or ENGAGED. */
    ALWAYS
}

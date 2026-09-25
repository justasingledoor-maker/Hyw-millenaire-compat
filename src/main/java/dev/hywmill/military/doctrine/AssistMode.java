package dev.hywmill.military.doctrine;

/** Which ordinary players inside the defense radius the village's defenders help. */
public enum AssistMode {
    NEVER,
    /** Players whose Millénaire reputation with the village is at least {@code assistMinReputation}. */
    MIN_REPUTATION,
    ALWAYS
}

package dev.hywmill.military.defense;

/** Village alert level. Runtime only (not persisted). */
public enum AlertState {
    /** Normal village life. */
    CALM,
    /** A threat is inside the defense radius: civilians near it shelter, the reserve holds, no proactive attack. */
    ALERT,
    /** Actual fighting: a threat damaged a resident, or a resident hit an HYW unit. Militia per policy. */
    ENGAGED,
    /** Fighting ended; the reserve keeps holding, militia go back to work, civilians resume. */
    RECOVERY;

    /** States in which the reserve holds Millénaire's defending position. */
    public boolean reserveHolds() {
        return this != CALM;
    }

    /** States in which civilians near a threat shelter. */
    public boolean civiliansShelter() {
        return this == ALERT || this == ENGAGED;
    }
}

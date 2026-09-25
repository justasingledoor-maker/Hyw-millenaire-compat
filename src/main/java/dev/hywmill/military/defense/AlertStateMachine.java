package dev.hywmill.military.defense;

import dev.hywmill.military.doctrine.Doctrine;

/**
 * Deterministic alert lifecycle (pure; unit-tested). Evaluated on every threat scan of the
 * village (every 20 ticks, or immediately after a resident is attacked).
 *
 * <pre>
 *   any state --engage signal--> ENGAGED
 *   CALM     --threat in radius--> ALERT
 *   ALERT    --no threat for alertTicks--> CALM              (a sighting that never came to blows)
 *   ENGAGED  --no threat for engagedTicks--> RECOVERY
 *   RECOVERY --threat in radius--> ALERT
 *   RECOVERY --recoveryTicks elapsed--> CALM
 * </pre>
 * An engage signal is a threat damaging a resident, or a resident hitting an HYW unit.
 */
public final class AlertStateMachine {
    private AlertState state = AlertState.CALM;
    private long since;
    private long lastThreatTick = Long.MIN_VALUE;
    private long lastEngageTick = Long.MIN_VALUE;

    public AlertState state() {
        return state;
    }

    public long since() {
        return since;
    }

    public long lastEngageTick() {
        return lastEngageTick;
    }

    /** @return the previous state if a transition happened, else null. */
    public AlertState update(long now, boolean threatsPresent, boolean engageSignal, Doctrine d) {
        AlertState before = state;
        if (threatsPresent) {
            lastThreatTick = now;
        }
        if (engageSignal) {
            lastEngageTick = now;
            lastThreatTick = now;
            state = AlertState.ENGAGED;
        } else {
            switch (state) {
                case CALM -> {
                    if (threatsPresent) {
                        state = AlertState.ALERT;
                    }
                }
                case ALERT -> {
                    if (!threatsPresent && now - lastThreatTick >= d.alertTicks()) {
                        state = AlertState.CALM;
                    }
                }
                case ENGAGED -> {
                    if (!threatsPresent && now - lastThreatTick >= d.engagedTicks()) {
                        state = AlertState.RECOVERY;
                    }
                }
                case RECOVERY -> {
                    if (threatsPresent) {
                        state = AlertState.ALERT;
                    } else if (now - since >= d.recoveryTicks()) {
                        state = AlertState.CALM;
                    }
                }
            }
        }
        if (state != before) {
            since = now;
            return before;
        }
        return null;
    }
}

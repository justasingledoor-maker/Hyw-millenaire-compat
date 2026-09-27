package dev.hywmill.politics;

/**
 * Observable service that earns Favor (approved, §18.3 Q2). There is deliberately no trade source:
 * trade already earns Millénaire reputation.
 */
public enum FavorSource {
    /** Damaged or killed an M2 threat of the village during an alert (capped per alert by the service). */
    DEFENSE,
    /** Present in the defense radius while an alert was repelled. */
    PRESENT_AT_DEFENSE,
    /** Successful diplomacy the village asked for (M5-4). */
    REQUESTED_DIPLOMACY,
    /** Detachment returned with no losses caused by the player (M5-5). */
    ERRAND_SUCCESS,
    /** Long good standing (monthly, trusted or better, no grievance). */
    LONG_STANDING
}

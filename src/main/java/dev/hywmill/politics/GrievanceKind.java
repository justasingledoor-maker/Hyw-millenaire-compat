package dev.hywmill.politics;

/** What a player did to a village. Weights, and which kinds count as killings, come from {@link PoliticsTables}. */
public enum GrievanceKind {
    KILL_RESIDENT(true),
    KILL_GARRISON(true),
    ASSAULT_RESIDENT(false),
    ASSAULT_GARRISON(false),
    /** Abuse of an escort or detachment (M5-5). */
    ERRAND_ABUSE(false),
    /** A sow-discord plot exposed by its target (M5-4). */
    PLOT_EXPOSED(false);

    private final boolean killing;

    GrievanceKind(boolean killing) {
        this.killing = killing;
    }

    public boolean killing() {
        return killing;
    }
}

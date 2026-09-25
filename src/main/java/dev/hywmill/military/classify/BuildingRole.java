package dev.hywmill.military.classify;

/**
 * Military role of one building. Fortification points apply to operational buildings only.
 * BORDER_MARKER is classified and reported but worth 0 (border posts are not walls).
 * ARMOURY and TRAINING add no fortification but count toward the GARRISON tier.
 */
public enum BuildingRole {
    WALL(1),
    TOWER(3),
    GATE(2),
    BORDER_MARKER(0),
    GUARDHOUSE(3),
    WATCHTOWER(3),
    BARRACKS(4),
    ARMOURY(0),
    TRAINING(0),
    FORT_TOWNHALL(5),
    /** Explicitly not military (lets a table entry override a derived role). Never counted. */
    NONE(0);

    public final int fortification;

    BuildingRole(int fortification) {
        this.fortification = fortification;
    }
}

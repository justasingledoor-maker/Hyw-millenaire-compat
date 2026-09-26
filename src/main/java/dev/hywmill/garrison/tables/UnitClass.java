package dev.hywmill.garrison.tables;

/** Garrison class of a unit type; drives composition balancing and tier gating. */
public enum UnitClass {
    LEVY, LINE, RANGED, GUNPOWDER,
    /** M4: light mounted units, used as scouts where a culture's data allows them. */
    CAVALRY
}

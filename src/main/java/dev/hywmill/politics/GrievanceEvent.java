package dev.hywmill.politics;

/**
 * One offence, with the context that decides its severity (approved rule, design §18.3 Q1).
 *
 * @param insideVillage the offence happened inside the village (its defense radius)
 * @param peacetime     no war or campaign put the player against this village, and the player was
 *                      not already a legitimate threat of it
 * @param selfDefense   the victim attacked first (incident ledger): recorded at reduced severity
 * @param x             position (context only), y, z likewise
 */
public record GrievanceEvent(GrievanceKind kind, long tick, boolean insideVillage, boolean peacetime, boolean selfDefense,
                             int x, int y, int z) {
    /** The approved outlaw exception: a killing inside the village during peacetime (not self-defence). */
    public boolean immediateOutlaw() {
        return kind.killing() && insideVillage && peacetime && !selfDefense;
    }
}

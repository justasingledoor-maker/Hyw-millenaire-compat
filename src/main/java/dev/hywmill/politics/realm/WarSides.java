package dev.hywmill.politics.realm;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * War obligations (docs/realm-design.md §2): when a war starts between X and Y, every village bound to either by a
 * military alliance or a subject tie must act. Bound to one side, it joins it; bound to both, it sides with the stronger
 * bond and breaks with the other. Pure.
 */
public final class WarSides {
    private WarSides() {}

    /** How one village is bound to a principal of the war. */
    public enum Tie { NONE, ALLIANCE, SOVEREIGN, VASSAL, PROVINCE }

    /**
     * @param tieX      how it is bound to X: PROVINCE/VASSAL if it is X's province or vassal, SOVEREIGN if X is its subject,
     *                  ALLIANCE for a military alliance, NONE otherwise (a pact or a defensive pact does not oblige)
     * @param relationX its Millénaire relation with X
     * @param sameCultureX whether it shares X's culture
     */
    public record Bond(UUID village, Tie tieX, int relationX, boolean sameCultureX, Tie tieY, int relationY, boolean sameCultureY) {}

    /** It joins {@code side} against {@code enemy}; {@code brokenWith} is the principal it breaks with (null: none). */
    public record Decision(UUID village, UUID side, UUID enemy, @Nullable UUID brokenWith) {}

    public static double weight(Tie t) {
        return switch (t) {
            case PROVINCE -> 6;
            case VASSAL -> 5;
            case SOVEREIGN -> 4;
            case ALLIANCE -> 3;
            case NONE -> 0;
        };
    }

    public static double bond(Tie t, int relation, boolean sameCulture) {
        return t == Tie.NONE ? 0 : weight(t) + relation / 100.0 + (sameCulture ? 0.3 : 0);
    }

    public static List<Decision> decide(UUID x, UUID y, List<Bond> bonds) {
        List<Decision> out = new ArrayList<>();
        for (Bond b : bonds) {
            boolean ox = b.tieX() != Tie.NONE, oy = b.tieY() != Tie.NONE;
            if (!ox && !oy) {
                continue;
            }
            if (ox && oy) {
                double bx = bond(b.tieX(), b.relationX(), b.sameCultureX()), by = bond(b.tieY(), b.relationY(), b.sameCultureY());
                boolean forX = bx > by || (bx == by && x.compareTo(y) < 0);
                out.add(new Decision(b.village(), forX ? x : y, forX ? y : x, forX ? y : x));
            } else {
                out.add(new Decision(b.village(), ox ? x : y, ox ? y : x, null));
            }
        }
        return out;
    }
}

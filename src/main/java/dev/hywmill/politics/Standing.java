package dev.hywmill.politics;

/**
 * A player's political status with one village, derived from Millénaire's combined reputation (no
 * second reputation meter), the player's grievance, and Favor.
 *
 * <p>Rules (approved, design §18.3 Q1, and §4):
 * <ul>
 *   <li><b>Outlaw</b>: a serious grievance (≥ {@code outlaw}) <em>and</em> combined reputation ≤ the
 *       boycott line; <em>or</em> a killing inside the village in peacetime (immediate, whatever the
 *       reputation). The earlier "reputation ≤ −4096 alone" rule is dropped.</li>
 *   <li>Leaving outlawry (hysteresis): grievance below {@code pardon} <em>and</em> reputation above
 *       the boycott line; that is the pardon, which also clears the peacetime-killing flag.</li>
 *   <li><b>Unwelcome</b>: reputation ≤ boycott, or grievance ≥ {@code warning}.</li>
 *   <li><b>Sworn</b> / <b>Patron</b> / <b>Trusted</b>: reputation thresholds (plus Favor for Patron,
 *       Favor ever earned for Sworn) and no open grievance. A status earned by reputation is kept
 *       while reputation stays above {@code threshold × keepFactor}; any grievance ≥ warning ends it
 *       at once.</li>
 *   <li><b>Stranger</b>: otherwise.</li>
 * </ul>
 */
public enum Standing {
    OUTLAW, UNWELCOME, STRANGER, TRUSTED, PATRON, SWORN;

    /** The evaluation result; {@code pardoned} is true when an outlaw leaves outlawry now. */
    public record Result(Standing standing, boolean pardoned) {}

    public static Result evaluate(Standing previous, int reputation, double grievance, boolean peacetimeKillPending,
                                  int favor, long favorEarned, PoliticsTables tables) {
        PoliticsTables.StandingRule s = tables.standing();
        PoliticsTables.GrievanceRule g = tables.grievance();
        boolean pardoned = false;
        if (previous == OUTLAW) {
            if (!(grievance < g.pardon() && reputation > s.boycott())) {
                return new Result(OUTLAW, false);
            }
            pardoned = true;
        } else if (peacetimeKillPending || (grievance >= g.outlaw() && reputation <= s.boycott())) {
            return new Result(OUTLAW, false);
        }
        if (reputation <= s.boycott() || grievance >= g.warning()) {
            return new Result(UNWELCOME, pardoned);
        }
        return new Result(byReputation(previous, reputation, favor, favorEarned, s), pardoned);
    }

    private static Standing byReputation(Standing previous, int rep, int favor, long earned, PoliticsTables.StandingRule s) {
        if (reaches(previous, SWORN, rep, s.sworn(), s.keepFactor()) && earned >= s.swornFavorEarned()) {
            return SWORN;
        }
        if (reaches(previous, PATRON, rep, s.patron(), s.keepFactor()) && favor >= keep(previous, PATRON, s.patronFavor(), s.keepFactor())) {
            return PATRON;
        }
        if (reaches(previous, TRUSTED, rep, s.trusted(), s.keepFactor())) {
            return TRUSTED;
        }
        return STRANGER;
    }

    /** Gaining needs the full threshold; a player already at or above {@code level} keeps it down to threshold × keepFactor. */
    private static boolean reaches(Standing previous, Standing level, int rep, int threshold, double keepFactor) {
        return rep >= keep(previous, level, threshold, keepFactor);
    }

    private static double keep(Standing previous, Standing level, double threshold, double keepFactor) {
        return previous.compareTo(level) >= 0 && previous.ordinal() >= TRUSTED.ordinal() ? threshold * keepFactor : threshold;
    }

    public static Standing parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return STRANGER;
        }
    }
}

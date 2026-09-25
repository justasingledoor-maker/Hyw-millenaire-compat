package dev.hywmill.military;

import java.util.UUID;

/**
 * Decides whether a permanent HYW HOSTILE relation involving a village faction may stand.
 * M1.1 ships only {@link #ALWAYS_REVERT}: generic HYW escalation and manually set HOSTILE
 * relations are both reset to NEUTRAL. A later milestone can allow declared wars here without
 * touching the guard or the reconciliation pass.
 */
@FunctionalInterface
public interface DiplomacyPolicy {
    /** True if a permanent HOSTILE between these two identities is intended and must be kept. */
    boolean permitsPermanentHostility(UUID villageFaction, UUID other);

    DiplomacyPolicy ALWAYS_REVERT = (villageFaction, other) -> false;
}

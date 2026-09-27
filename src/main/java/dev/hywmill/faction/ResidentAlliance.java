package dev.hywmill.faction;

import java.util.UUID;

/**
 * M5 (Option 1): keeps a village's resident identity and faction identity permanently FRIENDLY in
 * both directions. HYW treats FRIENDLY (either direction) as relation-protected: not a target,
 * friendly damage and collision cancelled, as when both carried one identity (verified in the M5-0
 * follow-up spike). HYW stores FRIENDLY per direction, so both are written. Checked on the escalation
 * guard's staggered reconciliation slot; a write happens only when a direction is not FRIENDLY.
 */
public final class ResidentAlliance {
    public static final String C_REPAIRED = "identity.allianceWritten";

    private ResidentAlliance() {}

    /** Returns the number of directions written (0 when already FRIENDLY both ways). */
    public static int ensure(CombatFactionService factions, UUID villageId) {
        UUID faction = FactionIds.forVillage(villageId);
        UUID residents = FactionIds.residentsOf(villageId);
        int n = 0;
        if (!"FRIENDLY".equals(factions.relation(residents, faction))) {
            factions.setRelation(residents, faction, "FRIENDLY");
            n++;
        }
        if (!"FRIENDLY".equals(factions.relation(faction, residents))) {
            factions.setRelation(faction, residents, "FRIENDLY");
            n++;
        }
        return n;
    }
}

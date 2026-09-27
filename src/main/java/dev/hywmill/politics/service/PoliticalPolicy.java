package dev.hywmill.politics.service;

import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.military.DiplomacyPolicy;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.Standing;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * M5-3: the {@link DiplomacyPolicy} M1.1 left for political hostility. It replaces ALWAYS_REVERT.
 * A permanent HYW HOSTILE involving a village identity stands only where HywMill's political state
 * wants it; everything else is reverted by the escalation guard exactly as before.
 *
 * <p>Permitted (M5-3): the village's <b>faction</b> identity and a player that village has
 * outlawed. Never a resident identity (the guard refuses those before asking), never
 * faction ↔ faction (M5-5b adds wars and campaigns here). The political record is authoritative;
 * the HYW relation is only its projection.
 */
public final class PoliticalPolicy implements DiplomacyPolicy {
    private final HywMillRuntime rt;

    public PoliticalPolicy(HywMillRuntime rt) {
        this.rt = rt;
    }

    @Override
    public boolean permitsPermanentHostility(UUID villageFaction, UUID other) {
        return outlawedBy(villageFaction, other) || outlawedBy(other, villageFaction);
    }

    private boolean outlawedBy(UUID faction, UUID player) {
        if (rt.factions().isResidentIdentity(faction)) {
            return false;
        }
        UUID village = rt.factions().villageOf(faction);
        if (village == null) {
            return false;
        }
        ServerLevel overworld = rt.server().overworld();
        VillageRecord rec = overworld == null ? null : GarrisonLedger.get(overworld).get(village);
        if (rec == null || !faction.equals(rec.factionId)) {
            return false;
        }
        PoliticsRecord r = rec.politics.peek(player);
        return r != null && r.status == Standing.OUTLAW;
    }
}

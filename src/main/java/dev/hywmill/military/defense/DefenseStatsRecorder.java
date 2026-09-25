package dev.hywmill.military.defense;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.faction.CombatFactionService;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.ResidentInfo;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent military statistics from deaths: an HYW unit killed by a resident counts as an HYW
 * kill for the resident's village; a resident killed by an HYW unit counts as a resident loss.
 * Raid clones count for neither (they belong to another village's raid).
 */
public final class DefenseStatsRecorder {
    private DefenseStatsRecorder() {}

    public static void onDeath(ServerLevel level, LivingEntity victim, @Nullable Entity killer) {
        SettlementSource settlements = Services.settlements();
        CombatFactionService factions = Services.factions();
        if (settlements == null || factions == null || killer == null) {
            return;
        }
        long now = level.getGameTime();
        if (factions.isHywUnit(victim)) {
            UUID village = residentVillage(settlements, killer);
            if (village != null) {
                update(level, village, r -> {
                    r.stats.hywKills++;
                    r.stats.lastKillTick = now;
                });
                HmLog.diag("HYW unit {} killed by resident {} of village {}", factions.describe(victim), killer.getUUID(), village);
            }
        } else if (factions.isHywUnit(killer)) {
            UUID village = residentVillage(settlements, victim);
            if (village != null) {
                update(level, village, r -> {
                    r.stats.residentLosses++;
                    r.stats.lastLossTick = now;
                });
                HmLog.info("Resident {} of village {} killed by HYW unit {}", victim.getUUID(), village, factions.describe(killer));
            }
        }
    }

    @Nullable
    private static UUID residentVillage(SettlementSource settlements, Entity e) {
        Optional<ResidentInfo> info = settlements.residentInfo(e);
        return info.filter(i -> !i.raider()).map(ResidentInfo::settlementId).orElse(null);
    }

    private static void update(ServerLevel level, UUID village, java.util.function.Consumer<VillageRecord> change) {
        GarrisonLedger ledger = GarrisonLedger.get(level.getServer().overworld());
        VillageRecord r = ledger.get(village);
        if (r != null) {
            change.accept(r);
            ledger.setDirty();
        }
    }
}

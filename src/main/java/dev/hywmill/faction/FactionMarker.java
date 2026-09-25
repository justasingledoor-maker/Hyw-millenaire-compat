package dev.hywmill.faction;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.settlement.ResidentInfo;
import dev.hywmill.settlement.SettlementSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gives settlement residents their village's synthetic HYW relation identity through HYW's own
 * per-entity identity marker. The villager's real entity UUID is never changed.
 *
 * <p>Raid clones are deliberately left UNMARKED: a Millénaire raid clone is registered to the
 * TARGET village, and marking it with the target's faction would make HYW cancel damage between
 * raiders and defenders (HYW treats same-identity entities as relation-protected).
 */
public final class FactionMarker {
    public static final AtomicLong MARKED_ON_JOIN = new AtomicLong();
    public static final AtomicLong FIXED_BY_SWEEP = new AtomicLong();
    public static final AtomicLong RAIDERS_SKIPPED = new AtomicLong();

    private FactionMarker() {}

    public enum Outcome { NOT_RESIDENT, ALREADY_MARKED, MARKED, RAIDER_UNMARKED, DISABLED }

    public static Outcome ensure(Entity entity) {
        SettlementSource source = Services.settlements();
        CombatFactionService factions = Services.factions();
        if (source == null || factions == null || !HywMillConfig.MARK_VILLAGERS.get()) {
            return Outcome.DISABLED;
        }
        Optional<ResidentInfo> info = source.residentInfo(entity);
        if (info.isEmpty()) {
            return Outcome.NOT_RESIDENT;
        }
        ResidentInfo r = info.get();
        if (r.raider()) {
            if (factions.hasIdentityMarker(entity)) {
                factions.clearIdentity(entity);
            }
            RAIDERS_SKIPPED.incrementAndGet();
            HmLog.diagThrottled("raider-" + entity.getUUID(), 300_000L, "Raid clone {} ({}) attacking village {} left without faction identity", entity.getUUID(), r.typeId(), r.settlementId());
            return Outcome.RAIDER_UNMARKED;
        }
        UUID faction = FactionIds.forVillage(r.settlementId());
        UUID current = factions.markedIdentity(entity);
        if (faction.equals(current)) {
            return Outcome.ALREADY_MARKED;
        }
        factions.markIdentity(entity, faction);
        HmLog.diag("Villager faction identity assigned: {} ({}) -> faction {} of village {} (previous marker: {})",
                entity.getUUID(), r.typeId(), faction, r.settlementId(), current);
        return Outcome.MARKED;
    }

    public static void onJoin(Entity entity) {
        if (ensure(entity) == Outcome.MARKED) {
            long n = MARKED_ON_JOIN.incrementAndGet();
            HmLog.infoThrottled("mark-summary", 30_000L, "Villager faction identities assigned on join so far: {}", n);
        }
    }

    public record SweepResult(int loaded, int marked, int fixed) {}

    /** Re-verifies every loaded resident of one village; any fix means a join path was missed. */
    public static SweepResult sweep(ServerLevel level, UUID villageId) {
        SettlementSource source = Services.settlements();
        if (source == null || Services.factions() == null) {
            return new SweepResult(0, 0, 0);
        }
        List<Entity> residents = source.loadedResidents(level, villageId);
        int marked = 0;
        int fixed = 0;
        for (Entity e : residents) {
            Outcome o = ensure(e);
            if (o == Outcome.MARKED) {
                fixed++;
            }
            if (o == Outcome.MARKED || o == Outcome.ALREADY_MARKED) {
                marked++;
            }
        }
        if (fixed > 0) {
            FIXED_BY_SWEEP.addAndGet(fixed);
            HmLog.info("Identity sweep re-marked {} resident(s) of village {} that were missing their faction identity", fixed, villageId);
        }
        return new SweepResult(residents.size(), marked, fixed);
    }
}

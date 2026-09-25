package dev.hywmill.faction;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.Services;
import dev.hywmill.settlement.ResidentInfo;
import dev.hywmill.settlement.SettlementSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gives settlement residents their village's synthetic HYW relation identity through HYW's own
 * per-entity identity marker. The villager's real entity UUID is never changed.
 *
 * <p>Raid clones are deliberately left UNMARKED: a Millénaire raid clone is registered to the
 * TARGET village, and marking it with the target's faction would make HYW cancel damage between
 * raiders and defenders (HYW treats same-identity entities as relation-protected).
 */
public final class FactionMarker {
    public static final String C_MARKED_ON_JOIN = "identity.markedOnJoin";
    public static final String C_FIXED_BY_SWEEP = "identity.fixedBySweep";
    public static final String C_RAIDERS_SKIPPED = "identity.raidersSkipped";

    private FactionMarker() {}

    public enum Outcome { NOT_RESIDENT, ALREADY_MARKED, MARKED, RAIDER_UNMARKED, DISABLED }

    public static Outcome ensure(Entity entity) {
        SettlementSource source = Services.settlements();
        CombatFactionService factions = Services.factions();
        HywMillRuntime rt = HywMillRuntime.get();
        if (rt == null || source == null || factions == null || !HywMillConfig.MARK_VILLAGERS.get()) {
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
            rt.increment(C_RAIDERS_SKIPPED);
            HmLog.diagThrottled("raider-" + entity.getUUID(), 300_000L, "Raid clone {} ({}) attacking village {} left without faction identity", entity.getUUID(), r.typeId(), r.settlementId());
            return Outcome.RAIDER_UNMARKED;
        }
        UUID faction = rt.factions().register(r.settlementId());
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
        HywMillRuntime rt = HywMillRuntime.get();
        if (ensure(entity) == Outcome.MARKED && rt != null) {
            long n = rt.increment(C_MARKED_ON_JOIN);
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
        HywMillRuntime rt = HywMillRuntime.get();
        if (fixed > 0 && rt != null) {
            rt.add(C_FIXED_BY_SWEEP, fixed);
            HmLog.info("Identity sweep re-marked {} resident(s) of village {} that were missing their faction identity", fixed, villageId);
        }
        return new SweepResult(residents.size(), marked, fixed);
    }
}

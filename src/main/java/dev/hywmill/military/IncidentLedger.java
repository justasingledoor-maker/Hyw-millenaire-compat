package dev.hywmill.military;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.faction.CombatFactionService;
import dev.hywmill.settlement.ResidentInfo;
import dev.hywmill.settlement.SettlementSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runtime-only (not persisted) record of damage between Millénaire residents, HYW units and
 * players. Used to answer "who struck first" and "did this unit recently attack the village".
 * One instance per server, owned by {@link dev.hywmill.core.HywMillRuntime}; server-thread only.
 */
public final class IncidentLedger {
    private static final int CAPACITY = 512;
    /** An encounter between two entities is considered over after this many ticks without damage. */
    private static final long ENCOUNTER_GAP = 600L;

    public record Incident(
            long tick,
            UUID attacker, String attackerType, @Nullable UUID attackerFaction,
            UUID victim, String victimType, @Nullable UUID victimFaction,
            boolean attackerInherentlyHostile,
            @Nullable UUID victimResidentOf,
            @Nullable UUID insideVillage,
            float amount
    ) {}

    private record Pair(UUID a, UUID b) {
        static Pair of(UUID x, UUID y) {
            return x.compareTo(y) <= 0 ? new Pair(x, y) : new Pair(y, x);
        }
    }

    private record Encounter(UUID firstStriker, long start, long last) {}

    private final Deque<Incident> recent = new ArrayDeque<>();
    private final Map<UUID, Map<UUID, Long>> lastAttackOnVillage = new HashMap<>();
    private final Map<Pair, Encounter> encounters = new HashMap<>();
    private ThreatTracker threats;

    /** The tracker is only used to answer "which village contains this position". */
    public void bind(ThreatTracker threats) {
        this.threats = threats;
    }

    /** Returns the incident if it was relevant and recorded. */
    public Optional<Incident> record(LivingEntity victim, DamageSource source, float amount) {
        if (!(source.getEntity() instanceof LivingEntity attacker) || attacker == victim) {
            return Optional.empty();
        }
        SettlementSource settlements = Services.settlements();
        CombatFactionService factions = Services.factions();
        Optional<ResidentInfo> victimRes = settlements != null ? settlements.residentInfo(victim) : Optional.empty();
        Optional<ResidentInfo> attackerRes = settlements != null ? settlements.residentInfo(attacker) : Optional.empty();
        boolean attackerUnit = factions != null && factions.isCombatUnit(attacker);
        boolean victimUnit = factions != null && factions.isCombatUnit(victim);
        if (victimRes.isEmpty() && attackerRes.isEmpty() && !attackerUnit && !victimUnit) {
            return Optional.empty();
        }

        long tick = victim.level().getGameTime();
        UUID attackerFaction = factions != null ? factions.relationIdentity(attacker) : null;
        UUID victimFaction = factions != null ? factions.relationIdentity(victim) : null;
        boolean inherent = attackerUnit && factions.ownerOf(attacker) == null;
        UUID residentOf = victimRes.filter(r -> !r.raider()).map(ResidentInfo::settlementId).orElse(null);
        UUID inside = threats != null ? threats.villageContaining(victim.blockPosition()) : null;

        Incident inc = new Incident(tick,
                attacker.getUUID(), typeOf(attacker), attackerFaction,
                victim.getUUID(), typeOf(victim), victimFaction,
                inherent, residentOf, inside, amount);
        recent.addLast(inc);
        while (recent.size() > CAPACITY) {
            recent.removeFirst();
        }
        if (residentOf != null) {
            lastAttackOnVillage.computeIfAbsent(attacker.getUUID(), k -> new HashMap<>()).put(residentOf, tick);
        }
        Pair pair = Pair.of(attacker.getUUID(), victim.getUUID());
        Encounter enc = encounters.get(pair);
        if (enc == null || tick - enc.last() > ENCOUNTER_GAP) {
            encounters.put(pair, new Encounter(attacker.getUUID(), tick, tick));
        } else {
            encounters.put(pair, new Encounter(enc.firstStriker(), enc.start(), tick));
        }

        if (attackerUnit && residentOf != null) {
            HmLog.infoThrottled("hyw-hits-" + attacker.getUUID(), 5_000L,
                    "HYW entity attacks Millénaire villager: {} -> {} ({}) of village {} (inherentlyHostile={}, dmg={})",
                    factions.describe(attacker), victim.getUUID(), victimRes.get().typeId(), residentOf, inherent, amount);
        } else if (attackerRes.isPresent() && victimUnit) {
            HmLog.infoThrottled("mill-hits-" + attacker.getUUID(), 5_000L,
                    "Millénaire villager hits HYW entity: {} ({}) -> {} (dmg={})",
                    attacker.getUUID(), attackerRes.get().typeId(), factions.describe(victim), amount);
        } else if (attacker instanceof Player && victimUnit) {
            HmLog.infoThrottled("player-hits-" + attacker.getUUID(), 5_000L,
                    "Player {} hits HYW entity {} (dmg={})", attacker.getName().getString(), factions.describe(victim), amount);
        }
        HmLog.diag("Combat incident recorded: {}", inc);
        return Optional.of(inc);
    }

    private static String typeOf(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    public boolean recentlyAttackedVillage(UUID attacker, UUID village, long now) {
        Map<UUID, Long> m = lastAttackOnVillage.get(attacker);
        if (m == null) {
            return false;
        }
        Long t = m.get(village);
        return t != null && now - t <= HywMillConfig.RECENT_ATTACK_WINDOW.get();
    }

    /** Who opened the current encounter between two entities, or null if they are not in one. */
    @Nullable
    public UUID firstStriker(UUID a, UUID b, long now) {
        Encounter enc = encounters.get(Pair.of(a, b));
        if (enc == null || now - enc.last() > ENCOUNTER_GAP) {
            return null;
        }
        return enc.firstStriker();
    }

    public List<Incident> recent(int max) {
        List<Incident> out = new ArrayList<>(recent);
        return out.subList(Math.max(0, out.size() - max), out.size());
    }

    /** Drops expired index entries. Called from the server tick every scan. */
    public void prune(long now) {
        long window = Math.max(HywMillConfig.RECENT_ATTACK_WINDOW.get(), ENCOUNTER_GAP);
        lastAttackOnVillage.values().forEach(m -> m.values().removeIf(t -> now - t > window));
        lastAttackOnVillage.values().removeIf(Map::isEmpty);
        encounters.values().removeIf(e -> now - e.last() > ENCOUNTER_GAP);
    }
}

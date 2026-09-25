package dev.hywmill.military;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.faction.CombatFactionService;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.faction.FactionRegistry;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * HYW (LivingEntityHurtMixin) permanently sets two NEUTRAL identities HOSTILE after the third
 * damage event between them. Once villagers carry a village identity, that includes
 * player -> villager and villager -> player-owned-unit damage.
 *
 * <p>This guard runs after every damage event that involves a village faction, and as a periodic
 * reconciliation pass. It never creates hostility; it only detects permanent HOSTILE relations
 * involving a village faction (HYW escalation or set manually) and, unless the
 * {@link DiplomacyPolicy} permits them and if configured, resets that pair to NEUTRAL.
 * HYW's own temporary (600-tick) retaliation is untouched.
 */
public final class EscalationGuard {
    public static final String C_DETECTED = "escalation.detected";
    public static final String C_REVERTED = "escalation.reverted";
    public static final int RECONCILE_INTERVAL = 200;

    private EscalationGuard() {}

    /** Fast path: checks the pair right after a damage event we observed. */
    public static void afterDamage(Entity attacker, Entity victim) {
        CombatFactionService factions = Services.factions();
        HywMillRuntime rt = HywMillRuntime.get();
        if (factions == null || rt == null) {
            return;
        }
        FactionRegistry registry = rt.factions();
        UUID a = factions.relationIdentity(attacker);
        UUID v = factions.relationIdentity(victim);
        if (a == null || v == null || a.equals(v)) {
            return;
        }
        UUID village = registry.isVillageFaction(a) ? a : registry.isVillageFaction(v) ? v : null;
        if (village == null || !factions.isHostileEitherWay(a, v)) {
            return;
        }
        handle(rt, factions, village, village.equals(a) ? v : a, "after repeated damage");
    }

    /**
     * Reconciliation pass. Damage we never see (HYW escalates at the head of hurt(), before
     * invulnerability frames and before LivingDamageEvent) and relations set by commands or other
     * mods are caught here: every registered village faction is checked once per
     * {@link #RECONCILE_INTERVAL} ticks on its own staggered slot.
     */
    public static void reconcile(HywMillRuntime rt, long tick) {
        CombatFactionService factions = Services.factions();
        if (factions == null) {
            return;
        }
        FactionRegistry registry = rt.factions();
        for (UUID faction : registry.factions()) {
            UUID village = registry.villageOf(faction);
            if (village == null || !rt.scheduler().isDue(village, tick, RECONCILE_INTERVAL)) {
                continue;
            }
            for (UUID other : factions.permanentHostilesOf(faction)) {
                if (!other.equals(faction)) {
                    handle(rt, factions, faction, other, "found by reconciliation");
                }
            }
        }
    }

    private static void handle(HywMillRuntime rt, CombatFactionService factions, UUID villageFaction, UUID other, String how) {
        rt.increment(C_DETECTED);
        if (rt.diplomacy().permitsPermanentHostility(villageFaction, other)) {
            return;
        }
        if (HywMillConfig.PREVENT_PERMANENT_ESCALATION.get()) {
            factions.resetHostileToNeutral(villageFaction, other);
            rt.increment(C_REVERTED);
            HmLog.warnThrottled("escalation-" + villageFaction + other, 30_000L,
                    "Permanent HYW HOSTILE between village faction {} and {} ({}); reset to NEUTRAL (preventPermanentEscalation=true). "
                            + "HYW temporary retaliation is unaffected.", villageFaction, other, how);
        } else {
            HmLog.warnThrottled("escalation-" + villageFaction + other, 30_000L,
                    "Permanent HYW HOSTILE between village faction {} and {} ({}); left as is (preventPermanentEscalation=false).",
                    villageFaction, other, how);
        }
    }
}

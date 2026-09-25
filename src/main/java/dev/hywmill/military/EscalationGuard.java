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
 * <p>This guard runs after every damage event that involves a village faction. It never creates
 * hostility; it only detects HYW's escalation and, if configured, resets that pair to NEUTRAL.
 * HYW's own temporary (600-tick) retaliation is untouched.
 */
public final class EscalationGuard {
    public static final String C_DETECTED = "escalation.detected";
    public static final String C_REVERTED = "escalation.reverted";

    private EscalationGuard() {}

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
        if (!registry.isVillageFaction(a) && !registry.isVillageFaction(v)) {
            return;
        }
        if (!factions.isHostileEitherWay(a, v)) {
            return;
        }
        rt.increment(C_DETECTED);
        if (HywMillConfig.PREVENT_PERMANENT_ESCALATION.get()) {
            factions.resetHostileToNeutral(a, v);
            rt.increment(C_REVERTED);
            HmLog.warnThrottled("escalation-" + a + v, 30_000L,
                    "HYW escalated {} <-> {} to permanent HOSTILE after repeated damage; reset to NEUTRAL (preventPermanentEscalation=true). "
                            + "HYW temporary retaliation is unaffected.", a, v);
        } else {
            HmLog.warnThrottled("escalation-" + a + v, 30_000L,
                    "HYW escalated {} <-> {} to permanent HOSTILE after repeated damage; left as is (preventPermanentEscalation=false).", a, v);
        }
    }
}

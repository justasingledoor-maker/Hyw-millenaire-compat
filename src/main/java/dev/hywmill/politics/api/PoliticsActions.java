package dev.hywmill.politics.api;

import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.politics.DiplomacyOdds;
import dev.hywmill.politics.EnvoyKind;
import dev.hywmill.politics.Pardon;
import dev.hywmill.politics.service.EnvoyService;
import dev.hywmill.politics.service.PoliticsService;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * The write side of the politics API. Commands and the future Politics screen both call these and
 * only these; every rule lives behind them (in the services and the pure model), none in the callers.
 */
public final class PoliticsActions {
    private PoliticsActions() {}

    /** Result of an action: {@code code} is machine-readable (an enum name), {@code message} is for the player. */
    public record ActionResult(boolean ok, String code, String message) {}

    public static ActionResult propose(ServerLevel overworld, UUID player, UUID from, UUID to, EnvoyKind kind) {
        HywMillRuntime rt = HywMillRuntime.require();
        EnvoyService.Proposal p = rt.envoys().propose(overworld, player, from, to, kind);
        return new ActionResult(p.ok(), p.refusal().name(), p.ok()
                ? "Envoy sent (" + EnvoyService.label(kind) + "): " + p.detail()
                : "Refused (" + refusal(p.refusal()) + "): " + p.detail());
    }

    public static ActionResult cancelEnvoys(ServerLevel overworld, UUID player) {
        int n = HywMillRuntime.require().envoys().cancel(overworld, player);
        return new ActionResult(n > 0, n > 0 ? "OK" : "NONE", n + " envoy(s) recalled (diplomacy points and Favor are not refunded)");
    }

    public static ActionResult pardon(ServerLevel overworld, UUID player, UUID village, boolean pay) {
        VillageRecord rec = GarrisonLedger.get(overworld).get(village);
        if (rec == null) {
            return new ActionResult(false, "UNKNOWN_VILLAGE", "Unknown village");
        }
        PoliticsService.PardonResult res = HywMillRuntime.require().politics().pardon(overworld, rec, player, pay);
        Pardon.Quote q = res.quote();
        String place = rec.name.isEmpty() ? "this village" : rec.name;
        String msg = switch (q.outcome()) {
            case NOT_OUTLAW -> "You are not an outlaw in " + place + " (" + res.status() + ")";
            case DISABLED -> place + " grants no formal pardons; wait for the grievance to fade";
            case TOO_POOR -> "Pardon refused: the weregild is " + q.price() + " reputation and you have " + (q.repAfter() + q.price())
                    + "; donate goods to " + place + " until your reputation stays above the boycott line after paying";
            case OK -> res.paid()
                    ? "Pardon paid: " + q.price() + " reputation; now " + res.status() + " in " + place + ", reputation " + res.reputationAfter()
                    : "Pardon quote: " + q.price() + " reputation (grievance " + String.format("%.1f", q.grievance()) + ")";
        };
        return new ActionResult(q.ok(), q.outcome().name() + (res.paid() ? "_PAID" : ""), msg);
    }

    static String refusal(DiplomacyOdds.Refusal r) {
        return switch (r) {
            case OK -> "ok";
            case SAME_VILLAGE -> "same village";
            case NOT_DISCOVERED -> "village not discovered";
            case STANDING_TOO_LOW -> "your standing with the sponsoring village is too low";
            case UNKNOWN_TO_OTHER -> "the other village does not know you well enough";
            case NOT_IN_CONFLICT -> "a truce needs open conflict or a raid under way";
            case TRUSTED_BY_TARGET -> "the target trusts you; it would not believe the rumours";
            case PAIR_COOLDOWN -> "you proposed this recently";
            case PLAYER_COOLDOWN -> "you plotted recently";
            case TARGET_COOLDOWN -> "this pair was plotted against recently";
            case PENDING_LIMIT -> "too many envoys under way";
            case NO_FAVOR -> "not enough Favor";
            case NO_DIPLOMACY_POINT -> "no diplomacy point left";
            case NO_RELATION -> "the villages have no relation";
        };
    }
}

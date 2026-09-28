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

    /** Post-M5: an apology in Millénaire money (the payer must be online: the money is taken from their inventory). */
    public static ActionResult apology(ServerLevel overworld, UUID player, UUID village, boolean pay) {
        VillageRecord rec = GarrisonLedger.get(overworld).get(village);
        if (rec == null) {
            return new ActionResult(false, "UNKNOWN_VILLAGE", "Unknown village");
        }
        net.minecraft.server.level.ServerPlayer payer = overworld.getServer().getPlayerList().getPlayer(player);
        if (payer == null) {
            return new ActionResult(false, "OFFLINE", "The player must be online to pay");
        }
        PoliticsService.ApologyResult res = HywMillRuntime.require().politics().apology(overworld, rec, payer, pay);
        dev.hywmill.politics.Apology.Quote q = res.quote();
        String place = rec.name.isEmpty() ? "this village" : rec.name;
        String price = dev.hywmill.recruit.RecruitOffers.money(q.price());
        String msg = switch (q.outcome()) {
            case OUTLAW -> place + " will not take your money: outlaws must seek a pardon";
            case DISABLED -> place + " accepts no apologies; wait for the grievance to fade";
            case NOTHING_TO_SETTLE -> place + " holds no grievance against you";
            case TOO_POOR -> "The apology costs " + price + " and you carry " + dev.hywmill.recruit.RecruitOffers.money(Math.max(0, res.moneyLeft()));
            case OK -> res.paid()
                    ? "Apology accepted: " + price + " paid; the grievance is settled and you are " + res.status() + " in " + place
                    : "Apology quote: " + price + " (grievance " + String.format("%.1f", q.grievance()) + ")";
        };
        return new ActionResult(q.ok(), q.outcome().name() + (res.paid() ? "_PAID" : ""), msg);
    }

    /** M5-5: asks the village for a detachment holding {@code point} for {@code days} (escorts are deferred). */
    public static ActionResult request(ServerLevel overworld, UUID player, UUID village, dev.hywmill.politics.Requests.Kind kind, int asked,
                                       int days, @javax.annotation.Nullable net.minecraft.core.BlockPos point) {
        HywMillRuntime rt = HywMillRuntime.require();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord rec = ledger.get(village);
        dev.hywmill.settlement.SettlementSource source = dev.hywmill.core.Services.settlements();
        if (rec == null || source == null) {
            return new ActionResult(false, "UNKNOWN_VILLAGE", "Unknown village");
        }
        dev.hywmill.politics.PoliticsRecord pr = rec.politics.peek(player);
        dev.hywmill.politics.Standing own = pr == null ? dev.hywmill.politics.Standing.STRANGER : pr.status;
        dev.hywmill.politics.Standing eff = PoliticsView.effective(overworld, ledger, source, rec, player, own, null);
        var g = dev.hywmill.garrison.service.ErrandService.request(overworld, rec, player, eff, kind, asked, days, point,
                rt.defense().state(village), PoliticsService.tables(rec));
        var o = g.offer();
        String what = "detachment";
        if (!o.ok()) {
            return new ActionResult(false, o.refusal().name(), rec.name + " refuses the " + what + ": " + o.reason());
        }
        return new ActionResult(true, "OK", rec.name + " lends " + o.units() + " soldier(s) as your " + what
                + (o.favorCost() > 0 ? " for " + o.favorCost() + " Favor" : "") + (o.reason().isEmpty() ? "" : " (" + o.reason() + ")")
                + ": " + g.units().stream().map(e -> e.unitKey).toList());
    }

    /** M5-5: sends every soldier lent to the player home. */
    public static ActionResult dismiss(ServerLevel overworld, UUID player) {
        int n = dev.hywmill.garrison.service.ErrandService.dismiss(overworld, player);
        return new ActionResult(n > 0, n > 0 ? "OK" : "NONE", n + " soldier(s) sent home");
    }

    /** M5-5b: {@code /hywmill war join <ally> against <enemy>}. */
    public static ActionResult joinWar(ServerLevel overworld, UUID player, UUID ally, UUID enemy) {
        HywMillRuntime rt = HywMillRuntime.require();
        var r = rt.relations().join(overworld, rt, player, ally, enemy);
        String msg = switch (r) {
            case OK -> "You are on campaign for 7 days: FRIENDLY with your ally's soldiers, HOSTILE with the enemy's; the enemy holds a grievance";
            case NO_WAR -> "These villages are not at war";
            case STANDING_TOO_LOW -> "You need to be trusted by the village you fight for";
            case ALREADY_IN_CAMPAIGN -> "You are already on another campaign; leave it first";
            case SAME_VILLAGE -> "A village cannot fight itself";
            case UNKNOWN_VILLAGE -> "Unknown village";
        };
        return new ActionResult(r == dev.hywmill.politics.service.RelationProjector.JoinResult.OK, r.name(), msg);
    }

    /**
     * Post-M5: the player, on campaign with {@code ally} against {@code enemy}, suggests that the ally raid the enemy.
     * {@code forcedDraw} replaces the random draw (dev commands only; null in play).
     */
    public static ActionResult suggestRaid(ServerLevel overworld, UUID player, UUID ally, UUID enemy, @javax.annotation.Nullable Double forcedDraw) {
        var r = dev.hywmill.politics.service.RaidCounselService.suggest(overworld, player, ally, enemy, false, forcedDraw);
        String code = !r.ok() ? r.refusal().name() : r.agreed() ? "AGREED" : "REFUSED";
        return new ActionResult(r.agreed(), code, r.ok() ? r.detail() : "You cannot suggest a raid: " + r.detail());
    }

    public static ActionResult leaveWar(ServerLevel overworld, UUID player) {
        HywMillRuntime rt = HywMillRuntime.require();
        boolean ok = rt.relations().leave(overworld, rt, player);
        return new ActionResult(ok, ok ? "OK" : "NONE", ok ? "You left the campaign; the projections are restored" : "You are not on campaign");
    }

    /**
     * The Politics screen's single intent entry point: {@code action} is one offered by {@link PoliticsView#actions};
     * every rule is re-validated by the action it maps to.
     */
    public static ActionResult submit(ServerLevel overworld, UUID player, String action, UUID home, UUID target, int amount) {
        switch (action) {
            case "RECONCILE", "TRUCE", "ENCOURAGE", "SOW_DISCORD" -> {
                return propose(overworld, player, home, target, EnvoyKind.valueOf(action));
            }
            case "PARDON" -> {
                return pardon(overworld, player, home, false);
            }
            case "PARDON_PAY" -> {
                return pardon(overworld, player, home, true);
            }
            case "APOLOGY" -> {
                return apology(overworld, player, home, false);
            }
            case "APOLOGY_PAY" -> {
                return apology(overworld, player, home, true);
            }
            case "DISMISS" -> {
                return dismiss(overworld, player);
            }
            case "CANCEL_ENVOYS" -> {
                return cancelEnvoys(overworld, player);
            }
            case "WAR_JOIN" -> {
                return joinWar(overworld, player, home, target);
            }
            case "SUGGEST_RAID" -> {
                return suggestRaid(overworld, player, home, target, null);
            }
            case "WAR_LEAVE" -> {
                return leaveWar(overworld, player);
            }
            default -> {
                return new ActionResult(false, "UNKNOWN_ACTION", "Unknown action");
            }
        }
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

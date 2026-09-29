package dev.hywmill.politics.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.Services;
import dev.hywmill.politics.DiplomacyOdds;
import dev.hywmill.politics.EnvoyKind;
import dev.hywmill.politics.EnvoyMission;
import dev.hywmill.politics.GrievanceEvent;
import dev.hywmill.politics.GrievanceKind;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.Standing;
import dev.hywmill.politics.api.PoliticsView;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.PoliticsNbt;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * M5-4: envoy diplomacy on top of Millénaire's. A proposal spends one Millénaire diplomacy point of
 * the sponsoring village (and Favor for sow discord), then an envoy travels; the outcome resolves on
 * the sponsoring village's staggered slot after a distance-based delay, from pure data, so it works
 * while the villages are unloaded. Effects go through Millénaire's own relation API (Millénaire stays
 * the relation model). Truces hold the relation at the floor while they last. One instance per server.
 */
public final class EnvoyService {
    public static final int SLOT_INTERVAL = 200;
    /** Slot offset: never on the same tick as the politics refresh (+97), the ledger refresh (0) or the garrison slot. */
    public static final int SLOT_OFFSET = 131;
    public static final String C_RESOLVED = "diplomacy.resolved";

    /** Outcome of a proposal before the envoy leaves. */
    public record Proposal(DiplomacyOdds.Refusal refusal, @Nullable EnvoyMission mission, String detail) {
        public boolean ok() {
            return refusal == DiplomacyOdds.Refusal.OK;
        }
    }

    public Proposal propose(ServerLevel overworld, UUID player, UUID from, UUID to, EnvoyKind kind) {
        return propose(overworld, player, from, to, kind, false);
    }

    /** With {@code dryRun} every check runs and nothing is spent or sent (the Politics screen's verdict). */
    public Proposal propose(ServerLevel overworld, UUID player, UUID from, UUID to, EnvoyKind kind, boolean dryRun) {
        SettlementSource source = Services.settlements();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord a = ledger.get(from);
        VillageRecord b = ledger.get(to);
        long now = overworld.getGameTime();
        if (source == null || a == null || b == null) {
            return new Proposal(DiplomacyOdds.Refusal.NOT_DISCOVERED, null, "unknown village");
        }
        if (from.equals(to)) {
            return new Proposal(DiplomacyOdds.Refusal.SAME_VILLAGE, null, "an envoy needs two different villages");
        }
        if (a.loneBuilding || b.loneBuilding) {
            return new Proposal(DiplomacyOdds.Refusal.NO_RELATION, null, "bandits and lone buildings take no part in diplomacy");
        }
        if (!source.discovered(overworld, player, from) || !source.discovered(overworld, player, to)) {
            return new Proposal(DiplomacyOdds.Refusal.NOT_DISCOVERED, null, "you have not discovered both villages");
        }
        OptionalInt relation = source.villageRelation(overworld, from, to);
        if (relation.isEmpty()) {
            return new Proposal(DiplomacyOdds.Refusal.NO_RELATION, null, "the villages have no relation");
        }
        PoliticsTables t = PoliticsService.tables(a);
        PoliticsTables.DiplomacyRule r = t.diplomacy();
        Standing withA = effective(overworld, ledger, source, a, player);
        Standing withB = effective(overworld, ledger, source, b, player);
        int repB = source.playerReputation(overworld, to, player);
        boolean conflict = recentConflict(overworld, source, from, to);
        DiplomacyOdds.Refusal refusal = DiplomacyOdds.check(kind, withA, withB, repB, relation.getAsInt(), conflict, r);
        if (refusal != DiplomacyOdds.Refusal.OK) {
            return new Proposal(refusal, null, "standing with " + a.name + " " + withA + ", with " + b.name + " " + withB
                    + ", relation " + relation.getAsInt() + (conflict ? ", raid under way" : ""));
        }
        PoliticsRecord rec = dryRun ? java.util.Objects.requireNonNullElseGet(a.politics.peek(player), PoliticsRecord::new) : a.politics.get(player);
        Long last = rec.lastProposal.get(to);
        if (last != null && now - last < r.pairCooldown()) {
            return new Proposal(DiplomacyOdds.Refusal.PAIR_COOLDOWN, null, "wait " + (r.pairCooldown() - (now - last)) + " ticks");
        }
        long pending = ledger.envoys().stream().filter(m -> m.player().equals(player)).count();
        if (pending >= EnvoyMission.MAX_PER_PLAYER || (kind == EnvoyKind.SOW_DISCORD
                && ledger.envoys().stream().anyMatch(m -> m.player().equals(player) && m.kind() == EnvoyKind.SOW_DISCORD))) {
            return new Proposal(DiplomacyOdds.Refusal.PENDING_LIMIT, null, pending + " envoy(s) already under way");
        }
        int favorCost = 0;
        if (kind == EnvoyKind.SOW_DISCORD) {
            for (VillageRecord v : ledger.all()) {
                PoliticsRecord pr = v.politics.peek(player);
                if (pr != null && pr.lastSowDiscord >= 0 && now - pr.lastSowDiscord < r.sowPlayerCooldown()) {
                    return new Proposal(DiplomacyOdds.Refusal.PLAYER_COOLDOWN, null, "wait " + (r.sowPlayerCooldown() - (now - pr.lastSowDiscord)) + " ticks");
                }
            }
            Long until = a.politics.discordCooldown().get(to);
            if (until != null && until > now) {
                return new Proposal(DiplomacyOdds.Refusal.TARGET_COOLDOWN, null, "this pair was plotted against recently");
            }
            favorCost = DiplomacyOdds.sowFavorCost(rec.recentSowAttempts(now, r.sowRecent()), r);
            if (rec.favor.points() < favorCost) {
                return new Proposal(DiplomacyOdds.Refusal.NO_FAVOR, null, "needs " + favorCost + " Favor with " + a.name + ", you have " + rec.favor.points());
            }
        }
        if (dryRun) {
            OptionalInt points = source.diplomacyPoints(overworld, from, player);
            if (points.isPresent() && points.getAsInt() <= 0) {
                return new Proposal(DiplomacyOdds.Refusal.NO_DIPLOMACY_POINT, null, "no Millénaire diplomacy point left with " + a.name);
            }
            return new Proposal(DiplomacyOdds.Refusal.OK, null, favorCost > 0 ? "costs 1 diplomacy point and " + favorCost + " Favor" : "costs 1 diplomacy point");
        }
        if (!source.consumeDiplomacyPoint(overworld, from, player)) {
            return new Proposal(DiplomacyOdds.Refusal.NO_DIPLOMACY_POINT, null, "no Millénaire diplomacy point left with " + a.name);
        }
        if (favorCost > 0) {
            rec.favor.spend(favorCost);
        }
        if (kind == EnvoyKind.SOW_DISCORD) {
            rec.sowAttempts = rec.recentSowAttempts(now, r.sowRecent()) + 1;
            rec.lastSowDiscord = now;
            a.politics.discordCooldown().put(to, now + r.sowPairCooldown());
        }
        int attempts = last != null && now - last < r.truceTicks() ? 1 : 0;
        rec.lastProposal.put(to, now);
        double dist = Math.sqrt(a.center.distSqr(b.center));
        UUID id = UUID.nameUUIDFromBytes((player + ">" + from + ">" + to + ">" + kind + ">" + now).getBytes(StandardCharsets.UTF_8));
        EnvoyMission m = new EnvoyMission(id, player, from, to, kind, now, now + DiplomacyOdds.travelTicks(dist, r),
                id.getMostSignificantBits() ^ id.getLeastSignificantBits(), attempts);
        ledger.envoys().add(m);
        ledger.setDirty();
        if (kind != EnvoyKind.SOW_DISCORD) { // a plot is not announced
            PoliticsService.chronicle(overworld, source, a, now, PoliticsService.playerName(overworld, player) + " sends an envoy from "
                    + a.name + " to " + b.name + " (" + label(kind) + ")");
        }
        HmLog.info("Diplomacy: {} proposes {} {} -> {} (arrives in {} ticks{})", player, kind, a.name, b.name, m.arriveTick() - now,
                favorCost > 0 ? ", Favor " + favorCost : "");
        return new Proposal(DiplomacyOdds.Refusal.OK, m, "the envoy arrives in about " + (m.arriveTick() - now) / 20 + " s");
    }

    /** Recalls the player's pending envoys (the diplomacy point and Favor are not refunded). Returns how many. */
    public int cancel(ServerLevel overworld, UUID player) {
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        int n = 0;
        for (Iterator<EnvoyMission> it = ledger.envoys().iterator(); it.hasNext(); ) {
            if (it.next().player().equals(player)) {
                it.remove();
                n++;
            }
        }
        if (n > 0) {
            ledger.setDirty();
        }
        return n;
    }

    // ------------------------------------------------------------------ slot

    public void tick(ServerLevel overworld, HywMillRuntime rt, long tick) {
        SettlementSource source = Services.settlements();
        if (source == null) {
            return;
        }
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        for (VillageRecord rec : ledger.all()) {
            if (!rt.scheduler().isDue(rec.villageId, tick + SLOT_OFFSET, SLOT_INTERVAL)) {
                continue;
            }
            long t0 = rt.perf().start();
            boolean dirty = false;
            List<EnvoyMission> due = new ArrayList<>();
            for (EnvoyMission m : ledger.envoys()) {
                if (m.from().equals(rec.villageId) && m.arriveTick() <= tick) {
                    due.add(m);
                }
            }
            for (EnvoyMission m : due) {
                ledger.envoys().remove(m);
                resolve(overworld, rt, ledger, source, m, tick);
                dirty = true;
            }
            dirty |= holdTruceFloors(overworld, source, rec, tick);
            if (dirty) {
                ledger.setDirty();
            }
            rt.perf().stop("diplomacy.slot", t0);
        }
    }

    /** While a truce lasts the relation is held at the floor (above Millénaire's raid line). */
    private boolean holdTruceFloors(ServerLevel overworld, SettlementSource source, VillageRecord rec, long now) {
        boolean changed = false;
        int floor = PoliticsService.tables(rec).diplomacy().truceFloor();
        for (Map.Entry<UUID, Long> e : rec.politics.truces().entrySet()) {
            if (e.getValue() <= now) {
                continue;
            }
            OptionalInt rel = source.villageRelation(overworld, rec.villageId, e.getKey());
            if (rel.isPresent() && rel.getAsInt() < floor) {
                source.setVillageRelation(overworld, rec.villageId, e.getKey(), floor);
                HmLog.info("Diplomacy: truce between '{}' and {} holds the relation at {} (was {})", rec.name, e.getKey(), floor, rel.getAsInt());
                changed = true;
            }
        }
        return changed;
    }

    /** The context the envoy finds on arrival (also used for the displayed odds). */
    public static Optional<DiplomacyOdds.Context> context(ServerLevel overworld, UUID player, UUID from, UUID to, EnvoyKind kind, int attempts) {
        SettlementSource source = Services.settlements();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord a = ledger.get(from);
        VillageRecord b = ledger.get(to);
        if (source == null || a == null || b == null) {
            return Optional.empty();
        }
        OptionalInt rel = source.villageRelation(overworld, from, to);
        if (rel.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DiplomacyOdds.Context(kind, effective(overworld, ledger, source, a, player), effective(overworld, ledger, source, b, player),
                rel.getAsInt(), recentConflict(overworld, source, from, to), a.culture.equals(b.culture), Math.sqrt(a.center.distSqr(b.center)),
                a.defendingStrength, b.defendingStrength, attempts));
    }

    private void resolve(ServerLevel overworld, HywMillRuntime rt, GarrisonLedger ledger, SettlementSource source, EnvoyMission m, long now) {
        VillageRecord a = ledger.get(m.from());
        VillageRecord b = ledger.get(m.to());
        Optional<DiplomacyOdds.Context> oc = context(overworld, m.player(), m.from(), m.to(), m.kind(), m.attempts());
        if (a == null || b == null || oc.isEmpty()) {
            report(overworld, ledger, m.player(), now, "Your envoy found no one to talk to: a village is gone");
            return;
        }
        DiplomacyOdds.Context c = oc.get();
        PoliticsTables.DiplomacyRule r = PoliticsService.tables(a).diplomacy();
        double p = DiplomacyOdds.chance(c, r);
        DiplomacyOdds.Draws d = DiplomacyOdds.Draws.of(m.seed());
        DiplomacyOdds.Outcome o = DiplomacyOdds.roll(p, d, r);
        int delta = DiplomacyOdds.delta(c, d, r);
        String who = PoliticsService.playerName(overworld, m.player());
        String text;
        switch (m.kind()) {
            case RECONCILE, ENCOURAGE -> {
                if (o == DiplomacyOdds.Outcome.SUCCESS) {
                    source.adjustVillageRelation(overworld, m.from(), m.to(), delta);
                    text = who + "'s envoy brought " + a.name + " and " + b.name + " closer (relation +" + delta + ")";
                } else if (o == DiplomacyOdds.Outcome.BACKFIRE && m.kind() == EnvoyKind.RECONCILE) {
                    source.adjustVillageRelation(overworld, m.from(), m.to(), -Math.max(1, delta / 2));
                    source.adjustReputation(overworld, m.to(), m.player(), -r.backfireRep());
                    text = who + "'s envoy offended " + b.name + " (relation -" + Math.max(1, delta / 2) + ", reputation there -" + r.backfireRep() + ")";
                } else {
                    text = who + "'s envoy from " + a.name + " was politely turned away by " + b.name;
                }
            }
            case TRUCE -> {
                if (o == DiplomacyOdds.Outcome.SUCCESS) {
                    int floor = r.truceFloor();
                    int now2 = Math.max(c.relation() + delta, floor);
                    source.setVillageRelation(overworld, m.from(), m.to(), now2);
                    a.politics.setTruce(m.to(), now + r.truceTicks());
                    b.politics.setTruce(m.from(), now + r.truceTicks());
                    text = who + " brokered a truce between " + a.name + " and " + b.name + " (relation " + c.relation() + " -> " + now2
                            + ", for " + r.truceTicks() / PoliticsTables.DAY + " days)";
                } else if (o == DiplomacyOdds.Outcome.BACKFIRE) {
                    source.adjustVillageRelation(overworld, m.from(), m.to(), -delta);
                    text = "The truce offered by " + who + "'s envoy was scorned by " + b.name + " (relation -" + delta + ")";
                } else {
                    text = b.name + " refused the truce " + who + "'s envoy offered on behalf of " + a.name;
                }
            }
            case SOW_DISCORD -> {
                boolean exposed = DiplomacyOdds.exposed(Math.max(0, a.politics.get(m.player()).sowAttempts - 1), d, r);
                if (o == DiplomacyOdds.Outcome.SUCCESS) {
                    source.adjustVillageRelation(overworld, m.from(), m.to(), -delta);
                }
                if (exposed) {
                    // the plot is exposed: a grievance with the target, and the sponsor loses face
                    rt.politics().applyGrievance(overworld, b, m.player(), new GrievanceEvent(GrievanceKind.PLOT_EXPOSED, now, false, true, false,
                            b.center.getX(), b.center.getY(), b.center.getZ()));
                    source.adjustReputation(overworld, m.from(), m.player(), -r.exposedRep());
                    text = who + " was exposed plotting between " + a.name + " and " + b.name
                            + (o == DiplomacyOdds.Outcome.SUCCESS ? " (relation -" + delta + ")" : "");
                } else {
                    text = o == DiplomacyOdds.Outcome.SUCCESS ? "Rumours soured " + a.name + "'s view of " + b.name + " (relation -" + delta + ")"
                            : "Rumours about " + b.name + " found no ear in " + a.name;
                }
            }
            default -> text = "";
        }
        rt.increment(C_RESOLVED);
        boolean secret = m.kind() == EnvoyKind.SOW_DISCORD && !text.contains("exposed");
        if (!secret) {
            PoliticsService.chronicle(overworld, source, a, now, text);
            PoliticsService.chronicle(overworld, source, b, now, text);
        }
        HmLog.info("Diplomacy: {} {} {} -> {}: chance {} roll {} -> {} ({})", who, m.kind(), a.name, b.name, String.format("%.2f", p),
                String.format("%.3f", d.outcome()), o, text);
        report(overworld, ledger, m.player(), now, text + " [chance " + Math.round(p * 100) + "%]");
    }

    private static void report(ServerLevel overworld, GarrisonLedger ledger, UUID player, long now, String text) {
        ServerPlayer p = overworld.getServer().getPlayerList().getPlayer(player);
        if (p != null) {
            p.sendSystemMessage(Component.literal("[Diplomacy] " + text));
            return;
        }
        long mine = ledger.reports().stream().filter(x -> x.player().equals(player)).count();
        if (mine >= 16 || ledger.reports().size() >= GarrisonLedger.MAX_REPORTS) {
            ledger.reports().stream().filter(x -> x.player().equals(player)).findFirst().ifPresent(ledger.reports()::remove);
        }
        ledger.reports().add(new PoliticsNbt.EnvoyReport(player, now, text));
        ledger.setDirty();
    }

    /** Delivers envoy results that arrived while the player was offline. */
    public void onLogin(ServerPlayer player) {
        ServerLevel overworld = player.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        boolean any = false;
        for (Iterator<PoliticsNbt.EnvoyReport> it = ledger.reports().iterator(); it.hasNext(); ) {
            PoliticsNbt.EnvoyReport r = it.next();
            if (r.player().equals(player.getUUID())) {
                player.sendSystemMessage(Component.literal("[Diplomacy] While you were away: " + r.text()));
                it.remove();
                any = true;
            }
        }
        if (any) {
            ledger.setDirty();
        }
    }

    static Standing effective(ServerLevel overworld, GarrisonLedger ledger, SettlementSource source, VillageRecord rec, UUID player) {
        PoliticsRecord r = rec.politics.peek(player);
        Standing own = r == null ? Standing.STRANGER : r.status;
        return PoliticsView.effective(overworld, ledger, source, rec, player, own, null);
    }

    static boolean recentConflict(ServerLevel overworld, SettlementSource source, UUID a, UUID b) {
        return source.raidInfo(overworld, a).map(i -> b.equals(i.target())).orElse(false)
                || source.raidInfo(overworld, b).map(i -> a.equals(i.target())).orElse(false);
    }

    public static String label(EnvoyKind k) {
        return switch (k) {
            case RECONCILE -> "reconciliation";
            case TRUCE -> "truce";
            case ENCOURAGE -> "friendship";
            case SOW_DISCORD -> "sow discord";
        };
    }
}

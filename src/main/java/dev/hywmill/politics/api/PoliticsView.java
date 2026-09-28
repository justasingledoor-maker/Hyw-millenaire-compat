package dev.hywmill.politics.api;

import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.military.defense.AlertState;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.Standing;
import dev.hywmill.politics.VillagePolitics;
import dev.hywmill.politics.service.PoliticsService;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The read side of the politics API (M5). Commands and the future Politics screen both build their
 * output from these immutable snapshots; nothing here changes state.
 */
public final class PoliticsView {
    /** Villages of the same culture within this distance, at relation ≥ GOOD, share word of a player. */
    public static final int WORD_RADIUS = 1024;
    public static final int GOOD = 50;
    public static final int BAD = -30;

    private PoliticsView() {}

    public record Home(UUID village, String name, String culture, Standing status, Standing effective, List<String> wordTravels,
                       int reputation, double grievance, boolean peacetimeKillPending, int favor, long favorEarned,
                       Map<UUID, Long> truces, List<VillagePolitics.ChronicleEntry> recent) {}

    public record Summary(UUID village, String name, String culture, int x, int z, Standing status, Standing effective, int reputation) {}

    /** Intelligence: the level of detail depends on the player's effective standing. */
    public record Intel(Standing level, List<String> lines) {}

    public static Optional<Home> home(ServerLevel overworld, UUID player, UUID village) {
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord rec = ledger.get(village);
        SettlementSource source = Services.settlements();
        if (rec == null || source == null) {
            return Optional.empty();
        }
        PoliticsTables t = PoliticsService.tables(rec);
        long now = overworld.getGameTime();
        PoliticsRecord r = rec.politics.peek(player);
        Standing status = r == null ? Standing.STRANGER : r.status;
        List<String> notes = new ArrayList<>();
        Standing eff = effective(overworld, ledger, source, rec, player, status, notes);
        int rep = source.playerReputation(overworld, village, player);
        double g = r == null ? 0 : r.grievances.decayed(now, t.grievance());
        List<VillagePolitics.ChronicleEntry> all = rec.politics.chronicle();
        return Optional.of(new Home(village, rec.name, rec.culture, status, eff, List.copyOf(notes), rep, g,
                r != null && r.grievances.peacetimeKillPending(), r == null ? 0 : r.favor.points(), r == null ? 0 : r.favor.earnedTotal(),
                Map.copyOf(rec.politics.truces()), all.subList(Math.max(0, all.size() - 5), all.size())));
    }

    /**
     * Word travels (derived, never stored): same-culture villages within {@link #WORD_RADIUS} on good
     * terms (relation ≥ GOOD) with this one share what they know. An outlaw there is unwelcome here;
     * sworn there is a recommendation; outlawed by this village's enemy is a mild recommendation.
     * Village opinions otherwise stay distinct.
     */
    public static Standing effective(ServerLevel overworld, GarrisonLedger ledger, SettlementSource source, VillageRecord rec,
                                     UUID player, Standing own, @Nullable List<String> notes) {
        Standing eff = own;
        dev.hywmill.politics.war.Campaign c = dev.hywmill.politics.service.RelationProjector.campaignOf(ledger, player);
        if (c != null && c.enemy().equals(rec.villageId) && c.active(overworld.getGameTime())) {
            // M5-5b: an enemy combatant for the campaign's duration (ends with it, no pardon needed)
            if (eff.ordinal() > Standing.UNWELCOME.ordinal()) {
                eff = Standing.UNWELCOME;
            }
            VillageRecord ally = ledger.get(c.ally());
            note(notes, "enemy combatant (on campaign for " + (ally == null ? "?" : ally.name) + ")");
        }
        for (VillageRecord other : ledger.all()) {
            if (other == rec || !other.culture.equals(rec.culture)) {
                continue;
            }
            PoliticsRecord o = other.politics.peek(player);
            if (o == null || (o.status != Standing.OUTLAW && o.status != Standing.SWORN)) {
                continue;
            }
            double dx = other.center.getX() - rec.center.getX();
            double dz = other.center.getZ() - rec.center.getZ();
            if (dx * dx + dz * dz > (double) WORD_RADIUS * WORD_RADIUS) {
                continue;
            }
            OptionalInt relation = source.villageRelation(overworld, rec.villageId, other.villageId);
            if (relation.isEmpty()) {
                continue;
            }
            int rel = relation.getAsInt();
            if (o.status == Standing.OUTLAW && rel >= GOOD && eff.ordinal() > Standing.UNWELCOME.ordinal()) {
                eff = Standing.UNWELCOME;
                note(notes, "outlawed by " + other.name + ", a friend of this village");
            } else if (o.status == Standing.SWORN && rel >= GOOD) {
                note(notes, "sworn to " + other.name + ", a friend of this village (willingness bonus)");
            } else if (o.status == Standing.OUTLAW && rel <= BAD) {
                note(notes, "outlawed by " + other.name + ", an enemy of this village (mild recommendation)");
            }
        }
        return eff;
    }

    private static void note(@Nullable List<String> notes, String s) {
        if (notes != null) {
            notes.add(s);
        }
    }

    /** Villages the player has discovered or has a record with. */
    public static List<Summary> list(ServerLevel overworld, UUID player) {
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        SettlementSource source = Services.settlements();
        List<Summary> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        for (VillageRecord rec : ledger.all()) {
            PoliticsRecord r = rec.politics.peek(player);
            if (r == null && !source.discovered(overworld, player, rec.villageId)) {
                continue;
            }
            Standing own = r == null ? Standing.STRANGER : r.status;
            out.add(new Summary(rec.villageId, rec.name, rec.culture, rec.center.getX(), rec.center.getZ(), own,
                    effective(overworld, ledger, source, rec, player, own, null), source.playerReputation(overworld, rec.villageId, player)));
        }
        return out;
    }

    /** Intelligence about a village: refused below Trusted; coarse at Trusted; exact at Patron; intentions at Sworn. */
    public static Optional<Intel> intel(ServerLevel overworld, UUID player, UUID village) {
        Optional<Home> h = home(overworld, player, village);
        HywMillRuntime rt = HywMillRuntime.get();
        SettlementSource source = Services.settlements();
        if (h.isEmpty() || rt == null || source == null) {
            return Optional.empty();
        }
        Standing lvl = h.get().effective();
        List<String> lines = new ArrayList<>();
        if (lvl.ordinal() < Standing.TRUSTED.ordinal()) {
            lines.add("The village shares nothing with you (" + lvl.name().toLowerCase() + ").");
            return Optional.of(new Intel(lvl, lines));
        }
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord rec = ledger.get(village);
        AlertState alert = rt.defense().state(village);
        GarrisonRoster roster = rec.hywRoster;
        int live = roster == null ? 0 : roster.live();
        if (lvl == Standing.TRUSTED) {
            lines.add("Mood: " + (alert == null || alert == AlertState.CALM ? "at peace" : alert == AlertState.ENGAGED ? "at war" : "troubled"));
            lines.add("Garrison: " + (live == 0 ? "none" : live < 8 ? "a few soldiers" : live < 32 ? "a company" : live < 72 ? "a strong garrison" : "a great host"));
            for (VillageRecord other : ledger.all()) {
                if (other != rec) {
                    source.villageRelation(overworld, village, other.villageId).ifPresent(r ->
                            lines.add("Relations with " + other.name + ": " + band(r)));
                }
            }
            return Optional.of(new Intel(lvl, lines));
        }
        lines.add("Alert: " + (alert == null ? "CALM" : alert.name()) + " | threats " + rt.threats().threats(village).size());
        Map<Duty, Integer> duties = new EnumMap<>(Duty.class);
        Map<String, Integer> units = new java.util.TreeMap<>();
        if (roster != null) {
            for (RosterEntry e : roster.entries()) {
                if (!e.state().terminal()) {
                    duties.merge(e.assignedDuty, 1, Integer::sum);
                    units.merge(e.unitKey, 1, Integer::sum);
                }
            }
        }
        lines.add("Garrison: " + live + " " + units + " | duties " + duties);
        source.raidInfo(overworld, village).ifPresent(ri -> {
            lines.add("Raids performed " + ri.performed() + ", suffered " + ri.suffered());
            if (lvl == Standing.SWORN && ri.target() != null) {
                VillageRecord t = ledger.get(ri.target());
                lines.add("Intentions: preparing a raid on " + (t == null ? ri.target() : t.name));
            }
        });
        for (VillageRecord other : ledger.all()) {
            if (other != rec) {
                source.villageRelation(overworld, village, other.villageId).ifPresent(r -> lines.add("Relation with " + other.name + ": " + r));
            }
        }
        return Optional.of(new Intel(lvl, lines));
    }

    static String band(int r) {
        return r >= 90 ? "excellent" : r >= 50 ? "good" : r >= 10 ? "fair" : r > -30 ? "neutral" : r > -90 ? "bad" : "open conflict";
    }

    /** A pending envoy as the player sees it (the arrival tick is approximate by design; sow discord is shown too, to its sponsor only). */
    public record Envoy(UUID id, String from, String to, dev.hywmill.politics.EnvoyKind kind, long arrivesIn) {}

    public static List<Envoy> envoys(ServerLevel overworld, UUID player) {
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        long now = overworld.getGameTime();
        List<Envoy> out = new ArrayList<>();
        for (dev.hywmill.politics.EnvoyMission m : ledger.envoys()) {
            if (m.player().equals(player)) {
                VillageRecord a = ledger.get(m.from());
                VillageRecord b = ledger.get(m.to());
                out.add(new Envoy(m.id(), a == null ? "?" : a.name, b == null ? "?" : b.name, m.kind(), Math.max(0, m.arriveTick() - now)));
            }
        }
        return out;
    }

    /** Diplomacy view of one village: its relations with every other known village, truces, and the player's diplomacy points there. */
    public record Relations(String name, OptionalInt diplomacyPoints, List<String> relations, Map<UUID, Long> truces) {}

    public static Optional<Relations> relations(ServerLevel overworld, UUID player, UUID village) {
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord rec = ledger.get(village);
        SettlementSource source = Services.settlements();
        if (rec == null || source == null) {
            return Optional.empty();
        }
        List<String> lines = new ArrayList<>();
        for (VillageRecord other : ledger.all()) {
            if (other != rec && source.discovered(overworld, player, other.villageId)) {
                source.villageRelation(overworld, village, other.villageId).ifPresent(r ->
                        lines.add(other.name + " (" + other.center.getX() + ", " + other.center.getZ() + "): " + r + " " + band(r)
                                + (rec.politics.truceWith(other.villageId, overworld.getGameTime()) ? " [truce]" : "")));
            }
        }
        return Optional.of(new Relations(rec.name, source.diplomacyPoints(overworld, village, player), lines, Map.copyOf(rec.politics.truces())));
    }

    /** Soldiers of the village currently lent to the player (M5-5), as "key (duty)". */
    public static List<String> lent(ServerLevel overworld, UUID player, UUID village) {
        VillageRecord rec = GarrisonLedger.get(overworld).get(village);
        List<String> out = new ArrayList<>();
        if (rec != null) {
            for (dev.hywmill.garrison.RosterEntry e : dev.hywmill.garrison.service.ErrandService.lentTo(rec, player)) {
                out.add(e.unitKey + " (" + e.duty.name().toLowerCase() + ", " + Math.max(0, e.errandUntil - overworld.getGameTime()) / 20 + " s left)");
            }
        }
        return out;
    }

    /** M5-5b: wars among villages the player knows, and the player's campaign. */
    public record Wars(List<String> wars, @Nullable String campaign) {}

    public static Wars wars(ServerLevel overworld, UUID player) {
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        SettlementSource source = Services.settlements();
        long now = overworld.getGameTime();
        List<String> out = new ArrayList<>();
        for (dev.hywmill.politics.war.WarRecord w : ledger.wars().values()) {
            VillageRecord a = ledger.get(w.a), b = ledger.get(w.b);
            if (a == null || b == null || source == null
                    || !(source.discovered(overworld, player, w.a) || source.discovered(overworld, player, w.b))) {
                continue;
            }
            out.add(a.name + " vs " + b.name + ": " + (w.atWar() ? "at war since tick " + w.warSince
                    : "open conflict for " + (now - w.conflictSince) / 20 + " s (not yet war)"));
        }
        dev.hywmill.politics.war.Campaign c = dev.hywmill.politics.service.RelationProjector.campaignOf(ledger, player);
        String camp = null;
        if (c != null) {
            VillageRecord a = ledger.get(c.ally()), b = ledger.get(c.enemy());
            camp = "for " + (a == null ? "?" : a.name) + " against " + (b == null ? "?" : b.name) + ", " + Math.max(0, c.until() - now) / 20 + " s left";
        }
        return new Wars(out, camp);
    }

    /** M5-6: the player's honours: every village where their own standing is Trusted, Patron or Sworn. */
    public static List<String> honours(ServerLevel overworld, UUID player) {
        List<String> out = new ArrayList<>();
        for (VillageRecord rec : GarrisonLedger.get(overworld).all()) {
            PoliticsRecord r = rec.politics.peek(player);
            String h = r == null ? null : PoliticsService.honour(r.status);
            if (h != null) {
                out.add((r.status == Standing.SWORN ? h + " (" + rec.name + ")" : h + " of " + rec.name) + ", since tick " + r.statusSince);
            }
        }
        return out;
    }

    /** An action the Politics screen may offer: the server's verdict (available / why not), what it needs and costs, and a coarse outcome band. */
    public record ActionOption(String action, String label, boolean available, String requirement, String outcome) {}

    /**
     * Options for the player dealing with {@code home}, with {@code target} selected. Everything is evaluated by
     * the same services the actions use, in dry-run: nothing is spent.
     */
    public static List<ActionOption> actions(ServerLevel overworld, UUID player, UUID home, UUID target) {
        HywMillRuntime rt = HywMillRuntime.get();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord h = ledger.get(home);
        VillageRecord t = ledger.get(target);
        SettlementSource source = Services.settlements();
        List<ActionOption> out = new ArrayList<>();
        if (rt == null || h == null || t == null || source == null) {
            return out;
        }
        if (home.equals(target)) {
            PoliticsRecord r = h.politics.peek(player);
            PoliticsTables tb = PoliticsService.tables(h);
            if (r != null && r.status == Standing.OUTLAW) {
                dev.hywmill.politics.Pardon.Quote q = dev.hywmill.politics.Pardon.quote(r, overworld.getGameTime(),
                        source.playerReputation(overworld, home, player), tb);
                out.add(new ActionOption("PARDON_PAY", "Pay the weregild", q.ok(), "costs " + q.price() + " reputation",
                        q.ok() ? "pardon at once" : q.outcome().name().toLowerCase()));
            } else if (r != null) {
                ServerPlayer payer = overworld.getServer().getPlayerList().getPlayer(player);
                dev.hywmill.politics.Apology.Quote q = dev.hywmill.politics.Apology.quote(r, overworld.getGameTime(),
                        payer == null ? 0 : source.playerMoney(payer), tb);
                if (q.outcome() == dev.hywmill.politics.Apology.Outcome.OK || q.outcome() == dev.hywmill.politics.Apology.Outcome.TOO_POOR) {
                    out.add(new ActionOption("APOLOGY_PAY", "Pay an apology", q.ok(), "costs " + dev.hywmill.recruit.RecruitOffers.money(q.price()),
                            q.ok() ? "grievance settled" : "too_poor"));
                }
            }
            boolean lent = !dev.hywmill.garrison.service.ErrandService.lentTo(h, player).isEmpty();
            out.add(new ActionOption("DISMISS", "Send lent soldiers home", lent, lent ? "" : "no soldiers lent to you", ""));
            boolean envoys = !envoys(overworld, player).isEmpty();
            out.add(new ActionOption("CANCEL_ENVOYS", "Recall your envoys", envoys, envoys ? "points and Favor are not refunded" : "no envoy under way", ""));
            boolean onCampaign = dev.hywmill.politics.service.RelationProjector.campaignOf(ledger, player) != null;
            out.add(new ActionOption("WAR_LEAVE", "Leave your campaign", onCampaign, onCampaign ? "" : "you are not on campaign", ""));
            return out;
        }
        for (dev.hywmill.politics.EnvoyKind k : dev.hywmill.politics.EnvoyKind.values()) {
            var p = rt.envoys().propose(overworld, player, home, target, k, true);
            String band = "";
            if (p.ok()) {
                double c = dev.hywmill.politics.service.EnvoyService.context(overworld, player, home, target, k, 0)
                        .map(ctx -> dev.hywmill.politics.DiplomacyOdds.chance(ctx, PoliticsService.tables(h).diplomacy())).orElse(0.0);
                band = c >= 0.66 ? "likely" : c >= 0.33 ? "uncertain" : "unlikely";
            }
            out.add(new ActionOption(k.name(), "Envoy: " + dev.hywmill.politics.service.EnvoyService.label(k), p.ok(),
                    "needs " + dev.hywmill.politics.DiplomacyOdds.required(k).name().toLowerCase() + " with " + h.name + "; " + p.detail(), band));
        }
        boolean war = dev.hywmill.politics.service.RelationProjector.atWar(ledger, home, target);
        out.add(new ActionOption("WAR_JOIN", "Join " + h.name + "'s war against " + t.name, war,
                war ? "needs trusted with " + h.name + "; " + t.name + " will hold a grievance" : "these villages are not at war", war ? "7-day campaign" : ""));
        var rc = dev.hywmill.politics.service.RaidCounselService.suggest(overworld, player, home, target, true, null);
        out.add(new ActionOption("SUGGEST_RAID", "Suggest a raid on " + t.name, rc.ok(), rc.detail(),
                rc.ok() ? dev.hywmill.politics.RaidCounsel.band(rc.chance()) : ""));
        var sc = rt.sieges().suggest(overworld, player, home, target, true, null);
        out.add(new ActionOption("SUGGEST_SIEGE", "Suggest a siege of " + t.name, sc.ok(), sc.detail(),
                sc.ok() ? dev.hywmill.politics.RaidCounsel.band(sc.chance()) : ""));
        return out;
    }
}

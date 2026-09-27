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
}

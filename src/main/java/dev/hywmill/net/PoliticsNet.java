package dev.hywmill.net;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.Services;
import dev.hywmill.politics.api.PoliticsActions;
import dev.hywmill.politics.api.PoliticsView;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M5-UI networking. The payload protocol is versioned ({@link #VERSION}). The server builds snapshots only
 * from {@link PoliticsView}, maps intents only onto {@link PoliticsActions}, re-validates everything, sends
 * each player only what that player may see, and rate-limits requests per player. The client decides nothing.
 * The same {@link #snapshot}/{@link #submit} are driven headless by {@code /hywmill dev ui}.
 */
public final class PoliticsNet {
    public static final String VERSION = "7"; // 2: Muster Roll squads; 3: liveries and distances; 4: player colours; 5: History tab; 6: War tab; 7: Realm tab
    /** At most one request per player per this many ticks. */
    public static final long RATE_TICKS = 5;
    /** Local requests (pardon, apology) and the home view need the player near the home village. */
    public static final double HOME_RANGE = 256;

    /** Per-server rate limiter (owned by the runtime; not static state of HywMill). */
    public static final class Limiter {
        private final Map<UUID, Long> last = new ConcurrentHashMap<>();

        public boolean allow(UUID player, long now) {
            Long l = last.get(player);
            if (l != null && now - l < RATE_TICKS && now >= l) {
                return false;
            }
            last.put(player, now);
            return true;
        }
    }

    private PoliticsNet() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(VERSION).optional();
        r.playToServer(PoliticsPayloads.OpenPolitics.TYPE, PoliticsPayloads.OpenPolitics.CODEC, (p, ctx) -> onServer(ctx, sp -> snapshot(sp, null, null)));
        r.playToServer(PoliticsPayloads.SelectVillage.TYPE, PoliticsPayloads.SelectVillage.CODEC,
                (p, ctx) -> onServer(ctx, sp -> snapshot(sp, p.home(), p.village())));
        r.playToServer(PoliticsPayloads.SubmitAction.TYPE, PoliticsPayloads.SubmitAction.CODEC, (p, ctx) -> onServer(ctx, sp -> {
            PoliticsPayloads.ActionResult res = submit(sp, p.action(), p.home(), p.target(), p.amount());
            PacketDistributor.sendToPlayer(sp, res);
            return snapshot(sp, p.home(), p.target());
        }));
        // client-bound: handled by client-only code, loaded only when a client runs the handler
        r.playToClient(PoliticsSnapshot.TYPE, PoliticsSnapshot.CODEC, (p, ctx) -> ctx.enqueueWork(() -> dev.hywmill.client.PoliticsClient.onSnapshot(p)));
        r.playToClient(PoliticsPayloads.ActionResult.TYPE, PoliticsPayloads.ActionResult.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> dev.hywmill.client.PoliticsClient.onResult(p)));
        // Muster Roll (post-M5 recruitment): same registrar and version; the server re-validates every intent
        r.playToServer(RecruitPayloads.Hire.TYPE, RecruitPayloads.Hire.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp && limiter(sp)) {
                dev.hywmill.recruit.RecruitService.hire(sp, p.pos(), p.key(), p.count());
            }
        }));
        r.playToServer(RecruitPayloads.SetRadius.TYPE, RecruitPayloads.SetRadius.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp && limiter(sp)) {
                dev.hywmill.recruit.RecruitService.setRadius(sp, p.pos(), p.radius());
            }
        }));
        r.playToServer(RecruitPayloads.SetColours.TYPE, RecruitPayloads.SetColours.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp && limiter(sp)) {
                dev.hywmill.recruit.RecruitService.setColours(sp, p.pos(), p.colour1(), p.colour2());
            }
        }));
        r.playToClient(RecruitPayloads.View.TYPE, RecruitPayloads.View.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> dev.hywmill.client.RecruitClient.onView(p)));
        r.playToClient(RecruitPayloads.Squads.TYPE, RecruitPayloads.Squads.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> dev.hywmill.client.RecruitClient.onSquads(p)));
        r.playToClient(RecruitPayloads.Result.TYPE, RecruitPayloads.Result.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> dev.hywmill.client.RecruitClient.onResult(p)));
    }

    private static boolean limiter(ServerPlayer sp) {
        dev.hywmill.core.HywMillRuntime rt = dev.hywmill.core.HywMillRuntime.get();
        return rt == null || rt.uiLimiter().allow(sp.getUUID(), sp.serverLevel().getGameTime());
    }

    private interface Reply {
        @Nullable
        PoliticsSnapshot build(ServerPlayer player);
    }

    private static void onServer(IPayloadContext ctx, Reply reply) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            HywMillRuntime rt = HywMillRuntime.get();
            if (rt == null || !rt.uiLimiter().allow(sp.getUUID(), sp.serverLevel().getGameTime())) {
                return;
            }
            try {
                PoliticsSnapshot s = reply.build(sp);
                if (s != null) {
                    PacketDistributor.sendToPlayer(sp, s);
                }
            } catch (RuntimeException e) {
                HmLog.warn("Politics screen request of {} failed: {}", sp.getGameProfile().getName(), e.toString());
            }
        });
    }

    /** Builds the snapshot for a player; {@code home} null: the nearest known village. Null if HywMill is inactive. */
    @Nullable
    public static PoliticsSnapshot snapshot(ServerPlayer player, @Nullable UUID home, @Nullable UUID selected) {
        SettlementSource source = Services.settlements();
        ServerLevel ow = player.getServer().overworld();
        if (source == null) {
            return null;
        }
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        UUID me = player.getUUID();
        if (home == null || ledger.get(home) == null || !near(player, ledger.get(home))) {
            home = source.nearest(ow, player.blockPosition(), HOME_RANGE).map(SettlementSource.SettlementRef::id)
                    .filter(id -> ledger.get(id) != null && !ledger.get(id).loneBuilding).orElse(null);
        }
        // post-M5: lone buildings (bandit camps, inns, lone farms) take no part in diplomacy and are not listed
        List<PoliticsView.Summary> known = PoliticsView.list(ow, me).stream()
                .filter(v -> ledger.get(v.village()) == null || !ledger.get(v.village()).loneBuilding).toList();
        final UUID sel = selected;
        if (sel != null && !sel.equals(home) && known.stream().noneMatch(v -> v.village().equals(sel))) {
            selected = null; // only villages the player knows
        }
        if (selected == null) {
            selected = home;
        }
        List<PoliticsSnapshot.VillageRow> rows = new ArrayList<>();
        for (PoliticsView.Summary v : known) {
            OptionalInt rel = home == null || v.village().equals(home) ? OptionalInt.empty() : source.villageRelation(ow, home, v.village());
            VillageRecord hr = home == null ? null : ledger.get(home);
            VillageRecord vr = ledger.get(v.village());
            int[] liv = vr == null ? null : dev.hywmill.garrison.service.LiveryService.of(ow, vr);
            int dist = hr == null || vr == null || vr == hr ? -1 : (int) Math.round(Math.sqrt(hr.center.distSqr(vr.center)));
            rows.add(new PoliticsSnapshot.VillageRow(v.village(), v.name(), v.effective().name(),
                    rel.isPresent() ? rel.getAsInt() : PoliticsSnapshot.VillageRow.NO_RELATION,
                    hr != null && hr.politics.truceWith(v.village(), ow.getGameTime()),
                    liv == null ? -1 : dev.hywmill.garrison.equip.VillageLivery.rgb(liv[0]), liv == null ? -1 : dev.hywmill.garrison.equip.VillageLivery.rgb(liv[1]),
                    dist, tag(ledger, home, v.village(), ow.getGameTime())));
        }
        List<String> envoys = new ArrayList<>();
        PoliticsView.envoys(ow, me).forEach(e -> envoys.add(e.kind().name().toLowerCase() + " " + e.from() + " -> " + e.to() + ", about "
                + e.arrivesIn() / 20 + " s"));
        List<String> honours = PoliticsView.honours(ow, me);
        if (home == null) {
            return new PoliticsSnapshot(null, "", "", "", "", 0, 0, 0, 0, List.of("No village nearby. Select a village you know."), List.of(),
                    rows, null, List.of(), envoys, List.of(), honours, history(ow, ledger, null), intel(player, ledger), realm(ow, ledger, selected));
        }
        PoliticsView.Home h = PoliticsView.home(ow, me, home).orElseThrow();
        List<String> chron = new ArrayList<>();
        h.recent().forEach(c -> chron.add(c.text()));
        List<PoliticsSnapshot.ActionRow> actions = new ArrayList<>();
        for (PoliticsView.ActionOption o : PoliticsView.actions(ow, me, home, selected)) {
            actions.add(new PoliticsSnapshot.ActionRow(o.action(), o.label(), o.available(), o.requirement(), o.outcome()));
        }
        OptionalInt dp = source.diplomacyPoints(ow, home, me);
        return new PoliticsSnapshot(home, h.name(), h.culture(), h.status().name(), h.effective().name(), h.reputation(), h.grievance(),
                h.favor(), dp.isPresent() ? dp.getAsInt() : -1, h.wordTravels(), chron, rows, selected, actions, envoys,
                PoliticsView.lent(ow, me, home), honours, history(ow, ledger, home), intel(player, ledger), realm(ow, ledger, selected));
    }

    /** Post-M5 realms: a village's place in the list: its subject tie, else home's treaty with it, else its realm's size. */
    static String tag(GarrisonLedger ledger, @Nullable UUID home, UUID v, long now) {
        dev.hywmill.politics.war.Vassalage t = dev.hywmill.politics.service.RealmService.tieOf(ledger, v, now);
        if (t != null && t.indefinite()) {
            return t.kindLabel() + " of " + name(ledger, t.overlord);
        }
        if (home != null && !home.equals(v)) {
            dev.hywmill.politics.realm.Treaty tr = dev.hywmill.politics.service.RealmService.realmTreaty(ledger, home, v, now);
            if (tr != null) {
                return switch (tr.kind) {
                    case ALLIANCE -> "ally";
                    case DEFENSIVE -> "defensive";
                    case PACT -> "pact";
                };
            }
        }
        int n = dev.hywmill.politics.service.RealmService.subjectsOf(ledger, v, now).size();
        return n > 0 ? "realm, " + n + " subject" + (n == 1 ? "" : "s") : "";
    }

    static String name(GarrisonLedger ledger, UUID v) {
        VillageRecord r = ledger.get(v);
        return r == null ? "?" : r.name;
    }

    /**
     * Post-M5 realms, the Realm tab: the selected village's realm (its sovereign, or its subjects with their loyalty), its
     * treaties (a subject's are its sovereign's), its wars and why it fights them; then the realms of the known world.
     */
    public static List<String> realm(ServerLevel ow, GarrisonLedger ledger, @Nullable UUID village) {
        List<String> out = new ArrayList<>();
        long now = ow.getGameTime();
        VillageRecord v = village == null ? null : ledger.get(village);
        if (v != null) {
            dev.hywmill.politics.war.Vassalage tie = dev.hywmill.politics.service.RealmService.tieOf(ledger, village, now);
            UUID head = dev.hywmill.politics.service.RealmService.sovereignOf(ledger, village, now);
            out.add(v.name + (v.hywRoster == null ? "" : ", " + v.hywRoster.live() + " soldiers"));
            if (tie != null) {
                out.add(" Is " + tie.describe(name(ledger, tie.overlord), now) + ".");
                out.add(tie.province ? " Its army is its sovereign's: 70% of its recruits are the sovereign's people, in the sovereign's colours."
                        : " It keeps its own army and colours, and answers its lord's call.");
            }
            List<dev.hywmill.politics.war.Vassalage> subs = dev.hywmill.politics.service.RealmService.subjectsOf(ledger, village, now);
            for (dev.hywmill.politics.war.Vassalage s : subs) {
                VillageRecord sr = ledger.get(s.vassal);
                out.add(" " + (s.province ? "Province " : "Vassal ") + name(ledger, s.vassal) + ": "
                        + (s.indefinite() ? "loyalty " + Math.round(s.loyalty) : s.daysLeft(now) + " day(s) left")
                        + (sr == null || sr.hywRoster == null ? "" : ", " + sr.hywRoster.live() + " soldiers"));
            }
            if (tie == null && subs.isEmpty()) {
                out.add(" Independent, with no subjects.");
            }
            out.add("");
            out.add("Treaties" + (head.equals(village) ? "" : " (its sovereign " + name(ledger, head) + "'s)") + ":");
            int nt = 0;
            for (dev.hywmill.politics.realm.Treaty t : ledger.treaties().values()) {
                if (t.a.equals(head) || t.b.equals(head)) {
                    out.add(" " + capital(t.kind.label()) + " with " + name(ledger, t.a.equals(head) ? t.b : t.a)
                            + ", since day " + t.since / dev.hywmill.politics.PoliticsTables.DAY);
                    nt++;
                }
            }
            if (nt == 0) {
                out.add(" None.");
            }
            out.add("");
            out.add("Wars:");
            int nw = 0;
            for (dev.hywmill.politics.war.WarRecord w : ledger.wars().values()) {
                if (!w.atWar() || !w.involves(village)) {
                    continue;
                }
                UUID enemy = w.other(village);
                String why = "";
                if (w.joinedFor != null && village.equals(w.joiner)) {
                    why = ", joined for " + name(ledger, w.joinedFor);
                } else if (w.joinedFor != null && enemy.equals(w.joiner)) {
                    why = ", who joined for " + name(ledger, w.joinedFor);
                }
                out.add(" At war with " + name(ledger, enemy) + why + ", since day " + w.warSince / dev.hywmill.politics.PoliticsTables.DAY);
                nw++;
            }
            if (nw == 0) {
                out.add(" At peace.");
            }
            out.add("");
        }
        out.addAll(realms(ow, ledger));
        return out;
    }

    /** Post-M5 realms: every village with subjects, the largest first, and its subjects with their loyalty. */
    public static List<String> realms(ServerLevel ow, GarrisonLedger ledger) {
        long now = ow.getGameTime();
        Map<UUID, List<dev.hywmill.politics.war.Vassalage>> by = new java.util.LinkedHashMap<>();
        for (dev.hywmill.politics.war.Vassalage t : ledger.vassalages()) {
            if (!t.over(now)) {
                by.computeIfAbsent(t.overlord, k -> new ArrayList<>()).add(t);
            }
        }
        List<String> out = new ArrayList<>();
        out.add("Realms of the known world:");
        if (by.isEmpty()) {
            out.add(" None: every village stands alone.");
            return out;
        }
        List<UUID> heads = new ArrayList<>(by.keySet());
        heads.sort((x, y) -> Integer.compare(by.get(y).size(), by.get(x).size()));
        for (UUID h : heads) {
            List<dev.hywmill.politics.war.Vassalage> subs = by.get(h);
            subs.sort((p, q) -> Boolean.compare(q.province, p.province));
            int men = live(ledger, h);
            StringBuilder b = new StringBuilder();
            for (dev.hywmill.politics.war.Vassalage s : subs) {
                men += live(ledger, s.vassal);
                b.append(b.isEmpty() ? "" : ", ").append(name(ledger, s.vassal)).append(" (").append(s.kindLabel())
                        .append(s.indefinite() ? ", loyalty " + Math.round(s.loyalty) : ", " + s.daysLeft(now) + " d").append(")");
            }
            out.add(" " + name(ledger, h) + ", " + men + " soldiers: " + b);
        }
        return out;
    }

    static int live(GarrisonLedger ledger, UUID v) {
        VillageRecord r = ledger.get(v);
        return r == null || r.hywRoster == null ? 0 : r.hywRoster.live();
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Post-M5, the War tab: what the player's side found on the roads, the open ones first. */
    public static List<PoliticsSnapshot.IntelRow> intel(ServerPlayer player, GarrisonLedger ledger) {
        ServerLevel ow = player.getServer().overworld();
        long now = ow.getGameTime();
        List<PoliticsSnapshot.IntelRow> open = new ArrayList<>(), past = new ArrayList<>();
        for (dev.hywmill.politics.war.Column c : dev.hywmill.garrison.service.ColumnService.visible(ledger, player.getUUID())) {
            boolean o = c.open(now);
            boolean take = o && dev.hywmill.garrison.service.ColumnService.mayAct(ledger, player.getUUID(), c);
            boolean bribe = dev.hywmill.garrison.service.ColumnService.bribable(ledger, player.getUUID(), c, now);
            String text = dev.hywmill.garrison.service.ColumnService.describe(ow, ledger, c, player);
            (c.onRoad() ? open : past).add(new PoliticsSnapshot.IntelRow(c.id, text, o, take, bribe,
                    bribe ? dev.hywmill.garrison.service.ColumnService.price(c) : 0));
        }
        java.util.Collections.reverse(past);
        open.addAll(past);
        return open;
    }

    /**
     * Post-M5, the History tab: the home village's vassal ties (with days left), then the reports of the sieges it fought,
     * newest first; with no home village, the latest sieges anywhere.
     */
    public static List<String> history(ServerLevel ow, GarrisonLedger ledger, @Nullable UUID home) {
        List<String> out = new ArrayList<>();
        long now = ow.getGameTime();
        for (dev.hywmill.politics.war.Vassalage v : ledger.vassalages()) {
            if (home != null && !v.vassal.equals(home) && !v.overlord.equals(home)) {
                continue;
            }
            VillageRecord vr = ledger.get(v.vassal), or = ledger.get(v.overlord);
            out.add((v.province ? "Province: " : "Vassalage: ") + (vr == null ? "?" : vr.name) + " is " + v.describe(or == null ? "?" : or.name, now));
        }
        List<dev.hywmill.politics.war.BattleReport> rs = ledger.battles();
        int shown = 0;
        for (int i = rs.size() - 1; i >= 0 && shown < 15; i--) {
            dev.hywmill.politics.war.BattleReport r = rs.get(i);
            if (home != null && !r.involves(home)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.add("");
            }
            out.addAll(r.lines());
            shown++;
        }
        if (out.isEmpty()) {
            out.add(home == null ? "No sieges have been fought yet." : "No sieges in this village's history yet.");
        }
        return out;
    }

    /** Validates and performs one intent through {@link PoliticsActions#submit}. */
    public static PoliticsPayloads.ActionResult submit(ServerPlayer player, String action, UUID home, UUID target, int amount) {
        ServerLevel ow = player.getServer().overworld();
        if (action.equals("INTERCEPT") || action.equals("BRIBE")) {
            // post-M5, the War tab: the target is a column on the road
            GarrisonLedger ledger = GarrisonLedger.get(ow);
            dev.hywmill.politics.war.Column c = dev.hywmill.garrison.service.ColumnService.find(ledger, target.toString());
            if (c == null) {
                return new PoliticsPayloads.ActionResult(false, "UNKNOWN_COLUMN", "That column is gone");
            }
            String msg = action.equals("BRIBE") ? dev.hywmill.garrison.service.ColumnService.bribe(ow, ledger, player, c)
                    : dev.hywmill.garrison.service.ColumnService.take(ow, ledger, player, c);
            boolean ok = msg.startsWith("Paid") || msg.startsWith("You take");
            return new PoliticsPayloads.ActionResult(ok, ok ? "OK" : "REFUSED", msg);
        }
        VillageRecord h = GarrisonLedger.get(ow).get(home);
        if (h == null) {
            return new PoliticsPayloads.ActionResult(false, "UNKNOWN_VILLAGE", "Unknown village");
        }
        boolean local = switch (action) {
            case "PARDON", "PARDON_PAY", "APOLOGY", "APOLOGY_PAY" -> true;
            default -> false;
        };
        if (local && !near(player, h)) {
            return new PoliticsPayloads.ActionResult(false, "TOO_FAR", "You must be in " + h.name + "'s lands to ask that");
        }
        return PoliticsPayloads.of(PoliticsActions.submit(ow, player.getUUID(), action, home, target, amount));
    }

    static boolean near(ServerPlayer player, VillageRecord rec) {
        return player.level() == player.getServer().overworld() && player.blockPosition().distSqr(rec.center) <= HOME_RANGE * HOME_RANGE;
    }
}

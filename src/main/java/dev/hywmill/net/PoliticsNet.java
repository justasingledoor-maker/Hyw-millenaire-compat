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
    public static final String VERSION = "2"; // 2: post-M5 Muster Roll squads
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
            rows.add(new PoliticsSnapshot.VillageRow(v.village(), v.name(), v.effective().name(),
                    rel.isPresent() ? rel.getAsInt() : PoliticsSnapshot.VillageRow.NO_RELATION,
                    hr != null && hr.politics.truceWith(v.village(), ow.getGameTime())));
        }
        List<String> envoys = new ArrayList<>();
        PoliticsView.envoys(ow, me).forEach(e -> envoys.add(e.kind().name().toLowerCase() + " " + e.from() + " -> " + e.to() + ", about "
                + e.arrivesIn() / 20 + " s"));
        List<String> honours = PoliticsView.honours(ow, me);
        if (home == null) {
            return new PoliticsSnapshot(null, "", "", "", "", 0, 0, 0, 0, List.of("No village nearby. Select a village you know."), List.of(),
                    rows, null, List.of(), envoys, List.of(), honours);
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
                PoliticsView.lent(ow, me, home), honours);
    }

    /** Validates and performs one intent through {@link PoliticsActions#submit}. */
    public static PoliticsPayloads.ActionResult submit(ServerPlayer player, String action, UUID home, UUID target, int amount) {
        ServerLevel ow = player.getServer().overworld();
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

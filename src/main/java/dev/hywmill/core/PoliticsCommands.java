package dev.hywmill.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.hywmill.politics.FavorSource;
import dev.hywmill.politics.GrievanceEvent;
import dev.hywmill.politics.GrievanceKind;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.VillagePolitics;
import dev.hywmill.politics.api.PoliticsView;
import dev.hywmill.politics.service.PoliticsService;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /hywmill politics ...} (M5-2). Player commands read through {@link PoliticsView}, the same
 * read API the Politics screen will use; admin commands (op 3) exist for testing and moderation.
 * Every player command can be run for another player UUID by an operator ({@code ... for <uuid>}).
 */
final class PoliticsCommands {
    private PoliticsCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("hywmill").then(Commands.literal("politics")
                .then(Commands.literal("status").executes(ctx -> status(ctx, null))
                        .then(Commands.literal("for").requires(s -> s.hasPermission(2))
                                .then(Commands.argument("player", UuidArgument.uuid()).executes(ctx -> status(ctx, UuidArgument.getUuid(ctx, "player"))))))
                .then(Commands.literal("list").executes(ctx -> list(ctx, null))
                        .then(Commands.literal("for").requires(s -> s.hasPermission(2))
                                .then(Commands.argument("player", UuidArgument.uuid()).executes(ctx -> list(ctx, UuidArgument.getUuid(ctx, "player"))))))
                .then(Commands.literal("intel").executes(ctx -> intel(ctx, null))
                        .then(Commands.literal("for").requires(s -> s.hasPermission(2))
                                .then(Commands.argument("player", UuidArgument.uuid()).executes(ctx -> intel(ctx, UuidArgument.getUuid(ctx, "player"))))))
                .then(Commands.literal("pardon").executes(ctx -> pardon(ctx, null, false))
                        .then(Commands.literal("pay").executes(ctx -> pardon(ctx, null, true)))
                        .then(Commands.literal("for").requires(s -> s.hasPermission(2))
                                .then(Commands.argument("player", UuidArgument.uuid()).executes(ctx -> pardon(ctx, UuidArgument.getUuid(ctx, "player"), false))
                                        .then(Commands.literal("pay").executes(ctx -> pardon(ctx, UuidArgument.getUuid(ctx, "player"), true))))))
                .then(Commands.literal("admin").requires(s -> s.hasPermission(3))
                        .then(Commands.literal("grievance").then(Commands.argument("player", UuidArgument.uuid())
                                .then(Commands.argument("kind", StringArgumentType.word())
                                        .then(Commands.argument("inside", BoolArgumentType.bool())
                                                .then(Commands.argument("peacetime", BoolArgumentType.bool())
                                                        .then(Commands.argument("selfDefense", BoolArgumentType.bool()).executes(PoliticsCommands::adminGrievance)))))))
                        .then(Commands.literal("favor").then(Commands.argument("player", UuidArgument.uuid())
                                .then(Commands.argument("source", StringArgumentType.word()).executes(PoliticsCommands::adminFavor))))
                        .then(Commands.literal("show").then(Commands.argument("player", UuidArgument.uuid()).executes(PoliticsCommands::adminShow)))
                        .then(Commands.literal("clear").then(Commands.argument("player", UuidArgument.uuid()).executes(PoliticsCommands::adminClear)))));
        d.register(root);
    }

    private static void send(CommandSourceStack src, String s) {
        src.sendSuccess(() -> Component.literal(s), false);
    }

    @Nullable
    private static UUID who(CommandContext<CommandSourceStack> ctx, @Nullable UUID explicit) {
        if (explicit != null) {
            return explicit;
        }
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("Run as a player, or use '... for <player uuid>'."));
            return null;
        }
        return p.getUUID();
    }

    @Nullable
    private static VillageRecord nearest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        SettlementSource source = Services.settlements();
        if (source == null) {
            src.sendFailure(Component.literal("Millénaire integration inactive."));
            return null;
        }
        ServerLevel ow = src.getServer().overworld();
        Optional<SettlementSource.SettlementRef> ref = source.nearest(ow, BlockPos.containing(src.getPosition()), 256);
        VillageRecord rec = ref.map(r -> GarrisonLedger.get(ow).get(r.id())).orElse(null);
        if (rec == null) {
            src.sendFailure(Component.literal("No known village within 256 blocks."));
        }
        return rec;
    }

    private static int status(CommandContext<CommandSourceStack> ctx, @Nullable UUID explicit) {
        UUID player = who(ctx, explicit);
        VillageRecord rec = player == null ? null : nearest(ctx);
        if (rec == null) {
            return 0;
        }
        ServerLevel ow = ctx.getSource().getServer().overworld();
        Optional<PoliticsView.Home> h = PoliticsView.home(ow, player, rec.villageId);
        if (h.isEmpty()) {
            return 0;
        }
        PoliticsView.Home v = h.get();
        CommandSourceStack src = ctx.getSource();
        send(src, "== Politics: " + v.name() + " (" + v.culture() + ")");
        send(src, "Standing: " + v.status() + (v.effective() != v.status() ? " (treated as " + v.effective() + ")" : "")
                + " | reputation " + v.reputation() + " | grievance " + String.format("%.1f", v.grievance())
                + (v.peacetimeKillPending() ? " (peacetime killing: pardon needed)" : "")
                + " | favor " + v.favor() + " (earned " + v.favorEarned() + ")");
        for (String w : v.wordTravels()) {
            send(src, "Word travels: " + w);
        }
        if (!v.truces().isEmpty()) {
            send(src, "Truces: " + v.truces());
        }
        for (VillagePolitics.ChronicleEntry e : v.recent()) {
            send(src, " chronicle t=" + e.tick() + " " + e.text());
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx, @Nullable UUID explicit) {
        UUID player = who(ctx, explicit);
        if (player == null) {
            return 0;
        }
        ServerLevel ow = ctx.getSource().getServer().overworld();
        var rows = PoliticsView.list(ow, player);
        send(ctx.getSource(), "Known villages: " + rows.size());
        for (PoliticsView.Summary s : rows) {
            send(ctx.getSource(), " " + s.name() + " (" + s.culture() + ") at " + s.x() + ", " + s.z() + ": " + s.status()
                    + (s.effective() != s.status() ? " -> " + s.effective() : "") + ", reputation " + s.reputation());
        }
        return rows.size();
    }

    private static int intel(CommandContext<CommandSourceStack> ctx, @Nullable UUID explicit) {
        UUID player = who(ctx, explicit);
        VillageRecord rec = player == null ? null : nearest(ctx);
        if (rec == null) {
            return 0;
        }
        Optional<PoliticsView.Intel> i = PoliticsView.intel(ctx.getSource().getServer().overworld(), player, rec.villageId);
        if (i.isEmpty()) {
            return 0;
        }
        send(ctx.getSource(), "== Intelligence on " + rec.name + " (as " + i.get().level() + ")");
        i.get().lines().forEach(l -> send(ctx.getSource(), " " + l));
        return 1;
    }

    /** M5-3 formal pardon: without {@code pay} only the price is quoted (through the shared PoliticsActions API). */
    private static int pardon(CommandContext<CommandSourceStack> ctx, @Nullable UUID explicit, boolean pay) {
        UUID player = who(ctx, explicit);
        VillageRecord rec = player == null ? null : nearest(ctx);
        if (rec == null) {
            return 0;
        }
        dev.hywmill.politics.api.PoliticsActions.ActionResult r = dev.hywmill.politics.api.PoliticsActions.pardon(
                ctx.getSource().getServer().overworld(), player, rec.villageId, pay);
        send(ctx.getSource(), "politics pardon " + r.code() + ": " + r.message()
                + (r.ok() && !pay ? "; run '/hywmill politics pardon pay' to pay" : ""));
        return r.ok() ? 1 : 0;
    }

    private static int adminGrievance(CommandContext<CommandSourceStack> ctx) {
        VillageRecord rec = nearest(ctx);
        if (rec == null) {
            return 0;
        }
        GrievanceKind kind;
        try {
            kind = GrievanceKind.valueOf(StringArgumentType.getString(ctx, "kind"));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Kinds: " + java.util.Arrays.toString(GrievanceKind.values())));
            return 0;
        }
        UUID player = UuidArgument.getUuid(ctx, "player");
        ServerLevel ow = ctx.getSource().getServer().overworld();
        BlockPos p = BlockPos.containing(ctx.getSource().getPosition());
        GrievanceEvent e = new GrievanceEvent(kind, ow.getGameTime(), BoolArgumentType.getBool(ctx, "inside"),
                BoolArgumentType.getBool(ctx, "peacetime"), BoolArgumentType.getBool(ctx, "selfDefense"), p.getX(), p.getY(), p.getZ());
        double added = HywMillRuntime.require().politics().applyGrievance(ow, rec, player, e);
        GarrisonLedger.get(ow).setDirty();
        PoliticsRecord r = rec.politics.get(player);
        send(ctx.getSource(), "politics grievance +" + added + " -> status " + r.status + " grievance "
                + String.format("%.1f", r.grievances.decayed(ow.getGameTime(), PoliticsService.tables(rec).grievance())));
        return 1;
    }

    private static int adminFavor(CommandContext<CommandSourceStack> ctx) {
        VillageRecord rec = nearest(ctx);
        if (rec == null) {
            return 0;
        }
        FavorSource src;
        try {
            src = FavorSource.valueOf(StringArgumentType.getString(ctx, "source"));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Sources: " + java.util.Arrays.toString(FavorSource.values())));
            return 0;
        }
        ServerLevel ow = ctx.getSource().getServer().overworld();
        PoliticsRecord r = rec.politics.get(UuidArgument.getUuid(ctx, "player"));
        int n = r.favor.earn(src, PoliticsService.tables(rec).favor());
        GarrisonLedger.get(ow).setDirty();
        send(ctx.getSource(), "politics favor +" + n + " -> " + r.favor.points() + " (earned " + r.favor.earnedTotal() + ")");
        return 1;
    }

    private static int adminShow(CommandContext<CommandSourceStack> ctx) {
        VillageRecord rec = nearest(ctx);
        if (rec == null) {
            return 0;
        }
        ServerLevel ow = ctx.getSource().getServer().overworld();
        PoliticsRecord r = rec.politics.peek(UuidArgument.getUuid(ctx, "player"));
        if (r == null) {
            send(ctx.getSource(), "politics record none");
            return 0;
        }
        send(ctx.getSource(), "politics record status=" + r.status + " since=" + r.statusSince + " grievance="
                + String.format("%.2f", r.grievances.decayed(ow.getGameTime(), PoliticsService.tables(rec).grievance()))
                + " peacetimeKill=" + r.grievances.peacetimeKillPending() + " lastKind=" + r.grievances.lastKind()
                + " inside=" + r.grievances.lastInside() + " favor=" + r.favor.points() + " earned=" + r.favor.earnedTotal()
                + " outlawProjection=" + HywMillRuntime.require().counter(PoliticsService.C_PROJECTED) + "/" + HywMillRuntime.require().counter(PoliticsService.C_CLEARED));
        return 1;
    }

    private static int adminClear(CommandContext<CommandSourceStack> ctx) {
        VillageRecord rec = nearest(ctx);
        if (rec == null) {
            return 0;
        }
        UUID player = UuidArgument.getUuid(ctx, "player");
        PoliticsRecord old = rec.politics.players().remove(player);
        boolean removed = old != null;
        if (old != null && old.status == dev.hywmill.politics.Standing.OUTLAW) {
            PoliticsService.project(HywMillRuntime.require(), rec, player, false);
        }
        GarrisonLedger.get(ctx.getSource().getServer().overworld()).setDirty();
        send(ctx.getSource(), "politics record cleared=" + removed);
        return removed ? 1 : 0;
    }
}

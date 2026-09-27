package dev.hywmill.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.hywmill.politics.EnvoyKind;
import dev.hywmill.politics.api.PoliticsActions;
import dev.hywmill.politics.api.PoliticsView;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /hywmill diplomacy status|list|propose <kind> <to> [<from>]|cancel} (M5-4), through the
 * shared {@link PoliticsActions} / {@link PoliticsView} API. Villages are named by a position (the
 * nearest known village within 256 blocks of it); {@code from} defaults to the village nearest to the
 * command source. Operators can act for another player with {@code /hywmill diplomacy for <uuid> ...}.
 */
final class DiplomacyCommands {
    private DiplomacyCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> dip = Commands.literal("diplomacy");
        subtree(dip, ctx -> self(ctx));
        dip.then(Commands.literal("for").requires(s -> s.hasPermission(2))
                .then(subtree(Commands.argument("player", UuidArgument.uuid()), ctx -> UuidArgument.getUuid(ctx, "player"))));
        d.register(Commands.literal("hywmill").then(dip));
    }

    private interface Who {
        @Nullable
        UUID get(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T subtree(T node, Who who) {
        node.then(Commands.literal("status").executes(ctx -> status(ctx, who.get(ctx))));
        node.then(Commands.literal("list").executes(ctx -> list(ctx, who.get(ctx))));
        node.then(Commands.literal("cancel").executes(ctx -> cancel(ctx, who.get(ctx))));
        LiteralArgumentBuilder<CommandSourceStack> propose = Commands.literal("propose");
        for (EnvoyKind k : EnvoyKind.values()) {
            propose.then(Commands.literal(k.name().toLowerCase())
                    .then(Commands.argument("to", BlockPosArgument.blockPos())
                            .executes(ctx -> propose(ctx, who.get(ctx), k, null))
                            .then(Commands.argument("from", BlockPosArgument.blockPos())
                                    .executes(ctx -> propose(ctx, who.get(ctx), k, BlockPosArgument.getBlockPos(ctx, "from"))))));
        }
        node.then(propose);
        return node;
    }

    @Nullable
    private static UUID self(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("Run as a player, or use '/hywmill diplomacy for <player uuid> ...'."));
            return null;
        }
        return p.getUUID();
    }

    private static void send(CommandSourceStack src, String s) {
        src.sendSuccess(() -> Component.literal(s), false);
    }

    @Nullable
    private static VillageRecord villageAt(CommandSourceStack src, BlockPos pos) {
        SettlementSource source = Services.settlements();
        if (source == null) {
            src.sendFailure(Component.literal("Millénaire integration inactive."));
            return null;
        }
        ServerLevel ow = src.getServer().overworld();
        Optional<SettlementSource.SettlementRef> ref = source.nearest(ow, pos, 256);
        VillageRecord rec = ref.map(r -> GarrisonLedger.get(ow).get(r.id())).orElse(null);
        if (rec == null) {
            src.sendFailure(Component.literal("No known village within 256 blocks of " + pos.toShortString() + "."));
        }
        return rec;
    }

    private static int status(CommandContext<CommandSourceStack> ctx, @Nullable UUID player) {
        if (player == null) {
            return 0;
        }
        CommandSourceStack src = ctx.getSource();
        ServerLevel ow = src.getServer().overworld();
        var envoys = PoliticsView.envoys(ow, player);
        send(src, "diplomacy envoys under way: " + envoys.size());
        for (PoliticsView.Envoy e : envoys) {
            send(src, " " + e.kind().name().toLowerCase() + " " + e.from() + " -> " + e.to() + ", arrives in about " + e.arrivesIn() / 20 + " s");
        }
        VillageRecord here = villageAt(src, BlockPos.containing(src.getPosition()));
        if (here != null) {
            PoliticsView.relations(ow, player, here.villageId).ifPresent(r -> {
                send(src, "diplomacy " + r.name() + ": diplomacy points " + (r.diplomacyPoints().isPresent() ? r.diplomacyPoints().getAsInt() : "?")
                        + ", truces " + r.truces().size());
            });
        }
        return envoys.size();
    }

    private static int list(CommandContext<CommandSourceStack> ctx, @Nullable UUID player) {
        CommandSourceStack src = ctx.getSource();
        VillageRecord here = player == null ? null : villageAt(src, BlockPos.containing(src.getPosition()));
        if (here == null) {
            return 0;
        }
        Optional<PoliticsView.Relations> r = PoliticsView.relations(src.getServer().overworld(), player, here.villageId);
        if (r.isEmpty()) {
            return 0;
        }
        send(src, "diplomacy relations of " + r.get().name() + " with the villages you know: " + r.get().relations().size());
        r.get().relations().forEach(l -> send(src, " " + l));
        return r.get().relations().size();
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx, @Nullable UUID player) {
        if (player == null) {
            return 0;
        }
        PoliticsActions.ActionResult r = PoliticsActions.cancelEnvoys(ctx.getSource().getServer().overworld(), player);
        send(ctx.getSource(), "diplomacy cancel " + r.code() + ": " + r.message());
        return r.ok() ? 1 : 0;
    }

    private static int propose(CommandContext<CommandSourceStack> ctx, @Nullable UUID player, EnvoyKind kind, @Nullable BlockPos fromPos)
            throws CommandSyntaxException {
        if (player == null) {
            return 0;
        }
        CommandSourceStack src = ctx.getSource();
        VillageRecord to = villageAt(src, BlockPosArgument.getBlockPos(ctx, "to"));
        VillageRecord from = villageAt(src, fromPos != null ? fromPos : BlockPos.containing(src.getPosition()));
        if (to == null || from == null) {
            return 0;
        }
        PoliticsActions.ActionResult r = PoliticsActions.propose(src.getServer().overworld(), player, from.villageId, to.villageId, kind);
        send(src, "diplomacy propose " + r.code() + ": " + r.message());
        return r.ok() ? 1 : 0;
    }
}

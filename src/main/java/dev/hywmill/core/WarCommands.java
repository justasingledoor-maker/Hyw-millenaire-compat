package dev.hywmill.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
import java.util.UUID;

/**
 * {@code /hywmill war status|join <ally> against <enemy>|leave} (M5-5b) through the shared API;
 * villages are named by a position. Operators: {@code /hywmill war for <uuid> ...};
 * admin (op 3): {@code /hywmill war admin clear-projections} (uninstall hygiene).
 */
final class WarCommands {
    private WarCommands() {}

    private interface Who {
        @Nullable
        UUID get(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> war = Commands.literal("war");
        subtree(war, WarCommands::self);
        war.then(Commands.literal("for").requires(s -> s.hasPermission(2))
                .then(subtree(Commands.argument("player", UuidArgument.uuid()), ctx -> UuidArgument.getUuid(ctx, "player"))));
        war.then(Commands.literal("admin").requires(s -> s.hasPermission(3))
                .then(Commands.literal("clear-projections").executes(ctx -> {
                    HywMillRuntime rt = HywMillRuntime.require();
                    int n = rt.relations().clearAll(ctx.getSource().getServer().overworld(), rt);
                    send(ctx.getSource(), "war projections cleared: " + n + " edge(s) restored (with politics.autoWar on, active wars project again)");
                    return n;
                })));
        d.register(Commands.literal("hywmill").then(war));
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T subtree(T node, Who who) {
        node.then(Commands.literal("status").executes(ctx -> status(ctx, who.get(ctx))));
        node.then(Commands.literal("leave").executes(ctx -> {
            UUID p = who.get(ctx);
            if (p == null) {
                return 0;
            }
            var r = PoliticsActions.leaveWar(ctx.getSource().getServer().overworld(), p);
            send(ctx.getSource(), "war leave " + r.code() + ": " + r.message());
            return r.ok() ? 1 : 0;
        }));
        node.then(Commands.literal("join").then(Commands.argument("ally", BlockPosArgument.blockPos())
                .then(Commands.literal("against").then(Commands.argument("enemy", BlockPosArgument.blockPos())
                        .executes(ctx -> join(ctx, who.get(ctx)))))));
        return node;
    }

    @Nullable
    private static UUID self(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("Run as a player, or use '/hywmill war for <player uuid> ...'."));
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
            return null;
        }
        ServerLevel ow = src.getServer().overworld();
        VillageRecord rec = source.nearest(ow, pos, 256).map(r -> GarrisonLedger.get(ow).get(r.id())).orElse(null);
        if (rec == null) {
            src.sendFailure(Component.literal("No known village within 256 blocks of " + pos.toShortString() + "."));
        }
        return rec;
    }

    private static int status(CommandContext<CommandSourceStack> ctx, @Nullable UUID player) {
        if (player == null) {
            return 0;
        }
        PoliticsView.Wars w = PoliticsView.wars(ctx.getSource().getServer().overworld(), player);
        send(ctx.getSource(), "war known conflicts: " + w.wars().size());
        w.wars().forEach(l -> send(ctx.getSource(), " " + l));
        send(ctx.getSource(), "war campaign: " + (w.campaign() == null ? "none" : w.campaign()));
        return w.wars().size();
    }

    private static int join(CommandContext<CommandSourceStack> ctx, @Nullable UUID player) throws CommandSyntaxException {
        if (player == null) {
            return 0;
        }
        VillageRecord ally = villageAt(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "ally"));
        VillageRecord enemy = villageAt(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "enemy"));
        if (ally == null || enemy == null) {
            return 0;
        }
        var r = PoliticsActions.joinWar(ctx.getSource().getServer().overworld(), player, ally.villageId, enemy.villageId);
        send(ctx.getSource(), "war join " + r.code() + ": " + r.message());
        return r.ok() ? 1 : 0;
    }
}

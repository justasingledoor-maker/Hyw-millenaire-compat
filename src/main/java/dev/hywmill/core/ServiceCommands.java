package dev.hywmill.core;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.hywmill.politics.realm.Service;
import dev.hywmill.politics.service.ServiceRoles;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * {@code /hywmill service ...} (1.7, docs/realm-design.md §11): enter or leave a realm's service, see its orders, counsel its
 * council, bribe its officials. {@code service admin ...} (operators; dev): the same for a stand-in player, and marking where
 * he was seen during an order.
 */
final class ServiceCommands {
    private ServiceCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> sv = Commands.literal("service");
        for (Service.Role role : Service.Role.values()) {
            sv.then(Commands.literal(role.name().toLowerCase()).executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                VillageRecord v = here(ctx.getSource(), p.blockPosition());
                if (v == null) {
                    send(ctx.getSource(), "service: no village here");
                    return 0;
                }
                return result(ctx.getSource(), ServiceRoles.enlist(p.serverLevel().getServer().overworld(), p, v, role));
            }));
        }
        sv.then(Commands.literal("leave").executes(ctx -> result(ctx.getSource(),
                ServiceRoles.leave(ctx.getSource().getServer().overworld(), ctx.getSource().getPlayerOrException().getUUID()))));
        sv.then(Commands.literal("status").executes(ctx -> status(ctx.getSource(), ctx.getSource().getPlayerOrException().getUUID())));
        sv.then(Commands.literal("counsel").then(Commands.argument("kind", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("war", "peace"), b))
                .then(Commands.argument("village", BlockPosArgument.blockPos()).executes(ctx -> counsel(ctx, ctx.getSource().getPlayerOrException().getUUID())))));
        sv.then(Commands.literal("bribe").then(Commands.argument("official", IntegerArgumentType.integer(0, 3)).executes(ctx -> result(ctx.getSource(),
                ServiceRoles.bribe(ctx.getSource().getServer().overworld(), ctx.getSource().getPlayerOrException(),
                        IntegerArgumentType.getInteger(ctx, "official"), 4096)))));
        // operators: the same for a stand-in (a fake player of that id), and marking where he was seen
        sv.then(Commands.literal("admin").requires(s -> s.hasPermission(2))
                .then(Commands.literal("enlist").then(Commands.argument("player", UuidArgument.uuid())
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("volunteer", "mercenary", "knight"), b))
                                .then(Commands.argument("village", BlockPosArgument.blockPos()).executes(ctx -> {
                                    ServerLevel ow = ctx.getSource().getServer().overworld();
                                    VillageRecord v = here(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "village"));
                                    Service.Role role;
                                    try {
                                        role = Service.Role.valueOf(StringArgumentType.getString(ctx, "role").toUpperCase());
                                    } catch (IllegalArgumentException e) {
                                        send(ctx.getSource(), "service admin enlist: volunteer, mercenary or knight");
                                        return 0;
                                    }
                                    if (v == null) {
                                        send(ctx.getSource(), "service admin enlist: no village there");
                                        return 0;
                                    }
                                    ServerPlayer p = standIn(ow, UuidArgument.getUuid(ctx, "player"), v.center);
                                    return result(ctx.getSource(), ServiceRoles.enlist(ow, p, v, role));
                                })))))
                .then(Commands.literal("leave").then(Commands.argument("player", UuidArgument.uuid()).executes(ctx -> result(ctx.getSource(),
                        ServiceRoles.leave(ctx.getSource().getServer().overworld(), UuidArgument.getUuid(ctx, "player"))))))
                .then(Commands.literal("status").then(Commands.argument("player", UuidArgument.uuid())
                        .executes(ctx -> status(ctx.getSource(), UuidArgument.getUuid(ctx, "player")))))
                .then(Commands.literal("counsel").then(Commands.argument("player", UuidArgument.uuid()).then(Commands.argument("kind", StringArgumentType.word())
                        .then(Commands.argument("village", BlockPosArgument.blockPos()).executes(ctx -> counsel(ctx, UuidArgument.getUuid(ctx, "player")))))))
                .then(Commands.literal("mark").then(Commands.argument("player", UuidArgument.uuid()).then(Commands.argument("where", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("rally", "near", "away"), b))
                        .executes(ctx -> {
                            // dev: the stand-in was seen at the rally point (or at the fight) during his current orders
                            Service s = GarrisonLedger.get(ctx.getSource().getServer().overworld()).services().get(UuidArgument.getUuid(ctx, "player"));
                            String w = StringArgumentType.getString(ctx, "where");
                            if (s == null) {
                                send(ctx.getSource(), "service admin mark: not in service");
                                return 0;
                            }
                            for (Service.Order o : s.orders) {
                                o.atRally = w.equals("rally");
                                o.near = !w.equals("away");
                            }
                            send(ctx.getSource(), "service admin mark OK: " + s.orders.size() + " order(s) " + w);
                            return s.orders.size();
                        })))));
        d.register(Commands.literal("hywmill").then(sv));
    }

    static ServerPlayer standIn(ServerLevel ow, UUID id, BlockPos at) {
        ServerPlayer p = FakePlayerFactory.get(ow, new GameProfile(id, "StandIn"));
        p.moveTo(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
        return p;
    }

    static int counsel(CommandContext<CommandSourceStack> ctx, UUID player) {
        VillageRecord v = here(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "village"));
        if (v == null) {
            send(ctx.getSource(), "service counsel: no village there");
            return 0;
        }
        return result(ctx.getSource(), ServiceRoles.propose(ctx.getSource().getServer().overworld(), player, v,
                StringArgumentType.getString(ctx, "kind").equals("war")));
    }

    static int status(CommandSourceStack src, UUID player) {
        ServerLevel ow = src.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        Service s = ledger.services().get(player);
        if (s == null) {
            send(src, "service status: none");
            return 0;
        }
        VillageRecord head = ledger.get(s.realm);
        send(src, "service status: " + s.role + " of " + (head == null ? "?" : head.name) + ", obeyed " + s.obeyed + ", present " + s.present
                + ", ignored " + s.ignored + ", orders " + s.orders.size());
        ServiceRoles.hudLines(ow, ledger, player, ow.getGameTime()).forEach(l -> send(src, " hud: " + l));
        if (head != null) {
            ServiceRoles.realmLines(ow, ledger, head, player).forEach(l -> send(src, " realm:" + l));
        }
        return 1;
    }

    static int result(CommandSourceStack src, ServiceRoles.Result r) {
        send(src, "service " + (r.ok() ? "OK" : "REFUSED") + ": " + r.message());
        return r.ok() ? 1 : 0;
    }

    @Nullable
    static VillageRecord here(CommandSourceStack src, BlockPos pos) {
        ServerLevel ow = src.getServer().overworld();
        VillageRecord v = dev.hywmill.recruit.RecruitService.villageAt(ow, pos);
        if (v != null) {
            return v;
        }
        var source = Services.settlements();
        return source == null ? null : source.nearest(ow, pos, 256).map(r -> GarrisonLedger.get(ow).get(r.id())).orElse(null);
    }

    static void send(CommandSourceStack src, String s) {
        src.sendSuccess(() -> Component.literal(s), false);
    }
}

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
        war.then(Commands.literal("arsenals").executes(ctx -> {
            var lines = dev.hywmill.garrison.service.ArsenalService.describe(GarrisonLedger.get(ctx.getSource().getServer().overworld()));
            send(ctx.getSource(), "war arsenals: " + lines.size());
            lines.forEach(l -> send(ctx.getSource(), " " + l));
            return lines.size();
        }));
        war.then(Commands.literal("sieges").executes(ctx -> {
            var lines = dev.hywmill.garrison.service.SiegeService.describe(ctx.getSource().getServer().overworld(),
                    GarrisonLedger.get(ctx.getSource().getServer().overworld()));
            send(ctx.getSource(), "war sieges: " + lines.size());
            lines.forEach(l -> send(ctx.getSource(), " " + l));
            return lines.size();
        }));
        war.then(Commands.literal("tributes").executes(ctx -> {
            ServerLevel ow = ctx.getSource().getServer().overworld();
            var lines = dev.hywmill.garrison.service.SiegeService.describeTributes(ow, GarrisonLedger.get(ow));
            send(ctx.getSource(), "war tributes: " + lines.size());
            lines.forEach(l -> send(ctx.getSource(), " " + l));
            return lines.size();
        }));
        war.then(Commands.literal("admin").requires(s -> s.hasPermission(3))
                .then(Commands.literal("recall-all").executes(ctx -> recallAll(ctx, false))
                        .then(Commands.literal("stowed").requires(s -> dev.hywmill.config.HywMillConfig.DEV_COMMANDS.get())
                                .executes(ctx -> recallAll(ctx, true))))
                .then(Commands.literal("siege-forget").requires(s -> dev.hywmill.config.HywMillConfig.DEV_COMMANDS.get()).executes(ctx -> {
                    // DEV: drops every siege record without bringing its host home (as a record lost to a mod update)
                    ServerLevel ow = ctx.getSource().getServer().overworld();
                    GarrisonLedger ledger = GarrisonLedger.get(ow);
                    int n = ledger.sieges().size();
                    ledger.sieges().clear();
                    ledger.setDirty();
                    send(ctx.getSource(), "war siege-forget " + n);
                    return n;
                }))
                .then(Commands.literal("tribute-due").requires(s -> dev.hywmill.config.HywMillConfig.DEV_COMMANDS.get()).executes(ctx -> {
                    // DEV: every tribute's next installment falls due now (instead of waiting a Minecraft day)
                    ServerLevel ow = ctx.getSource().getServer().overworld();
                    GarrisonLedger ledger = GarrisonLedger.get(ow);
                    ledger.tributes().forEach(t -> t.nextTick = ow.getGameTime());
                    ledger.setDirty();
                    send(ctx.getSource(), "war tribute-due " + ledger.tributes().size());
                    return ledger.tributes().size();
                }))
                .then(Commands.literal("siege-unwatched").requires(s -> dev.hywmill.config.HywMillConfig.DEV_COMMANDS.get())
                        .then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(ctx -> {
                            ServerLevel ow = ctx.getSource().getServer().overworld();
                            boolean on = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "on");
                            GarrisonLedger ledger = GarrisonLedger.get(ow);
                            ledger.sieges().forEach(g -> g.forceUnwatched = on);
                            ledger.setDirty();
                            send(ctx.getSource(), "war siege-unwatched " + on + " for " + ledger.sieges().size() + " siege(s)");
                            return ledger.sieges().size();
                        })))
                .then(Commands.literal("siege").then(Commands.argument("attacker", BlockPosArgument.blockPos())
                        .then(Commands.argument("target", BlockPosArgument.blockPos()).executes(ctx -> adminSiege(ctx, false))
                                .then(Commands.literal("unwatched").requires(s -> dev.hywmill.config.HywMillConfig.DEV_COMMANDS.get())
                                        .executes(ctx -> adminSiege(ctx, true))))))
                .then(Commands.literal("aid").then(Commands.argument("attacker", BlockPosArgument.blockPos()).executes(ctx -> {
                    // the target of that village's host gets every kind of help now (militia, mercenaries, household), before the battle
                    VillageRecord a = villageAt(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "attacker"));
                    if (a == null) {
                        return 0;
                    }
                    ServerLevel ow = ctx.getSource().getServer().overworld();
                    GarrisonLedger ledger = GarrisonLedger.get(ow);
                    var s = dev.hywmill.garrison.service.SiegeService.byAttacker(ledger, a.villageId);
                    VillageRecord t = s == null ? null : ledger.get(s.target);
                    if (s == null || t == null || s.phase == dev.hywmill.politics.war.Siege.Phase.BATTLE || s.phase == dev.hywmill.politics.war.Siege.Phase.RETURN) {
                        send(ctx.getSource(), "war aid NONE: " + a.name + " has no host on the way to a siege");
                        return 0;
                    }
                    int n = HywMillRuntime.require().sieges().defenderAid(ow, ledger, s, a, t, ow.getGameTime(), true);
                    send(ctx.getSource(), "war aid RAISED " + n + " for " + t.name);
                    return n;
                })))
                .then(Commands.literal("mercs").then(Commands.argument("attacker", BlockPosArgument.blockPos()).executes(WarCommands::adminMercs)))
                .then(Commands.literal("relief").then(Commands.argument("helper", BlockPosArgument.blockPos())
                        .then(Commands.argument("target", BlockPosArgument.blockPos()).executes(ctx -> adminRelief(ctx, "NONE"))
                                .then(Commands.argument("fate", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                java.util.List.of("clean", "ambushed", "routed", "straggled", "lost"), b))
                                        .executes(ctx -> adminRelief(ctx, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "fate")))))))
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
        node.then(Commands.literal("raid").executes(ctx -> raid(ctx, who.get(ctx), null))
                .then(Commands.literal("roll").requires(s -> dev.hywmill.config.HywMillConfig.DEV_COMMANDS.get() && s.hasPermission(2))
                        .then(Commands.argument("draw", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 1))
                                .executes(ctx -> raid(ctx, who.get(ctx), com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "draw"))))));
        node.then(Commands.literal("siege").executes(ctx -> siege(ctx, who.get(ctx), null, false))
                .then(Commands.literal("force").requires(s -> s.hasPermission(2)).executes(ctx -> siege(ctx, who.get(ctx), null, true)))
                .then(Commands.literal("roll").requires(s -> dev.hywmill.config.HywMillConfig.DEV_COMMANDS.get() && s.hasPermission(2))
                        .then(Commands.argument("draw", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 1))
                                .executes(ctx -> siege(ctx, who.get(ctx), com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "draw"), false)))));
        for (dev.hywmill.politics.WarCounsel.Kind kind : dev.hywmill.politics.WarCounsel.Kind.values()) {
            // war declare <village> on <other> | war peace <village> with <other>  [force | roll <draw>]
            String word = kind == dev.hywmill.politics.WarCounsel.Kind.WAR ? "declare" : "peace";
            String link = kind == dev.hywmill.politics.WarCounsel.Kind.WAR ? "on" : "with";
            node.then(Commands.literal(word).then(Commands.argument("village", BlockPosArgument.blockPos())
                    .then(Commands.literal(link).then(Commands.argument("other", BlockPosArgument.blockPos())
                            .executes(ctx -> counsel(ctx, who.get(ctx), kind, null, false))
                            .then(Commands.literal("force").requires(s -> s.hasPermission(2)).executes(ctx -> counsel(ctx, who.get(ctx), kind, null, true)))
                            .then(Commands.literal("roll").requires(s -> dev.hywmill.config.HywMillConfig.DEV_COMMANDS.get() && s.hasPermission(2))
                                    .then(Commands.argument("draw", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 1))
                                            .executes(ctx -> counsel(ctx, who.get(ctx), kind,
                                                    com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "draw"), false))))))));
        }
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

    /** Post-M5: suggest a raid to the village of the player's campaign against its enemy ({@code roll}: dev, forced draw). */
    private static int raid(CommandContext<CommandSourceStack> ctx, @Nullable UUID player, @Nullable Double draw) {
        if (player == null) {
            return 0;
        }
        ServerLevel ow = ctx.getSource().getServer().overworld();
        dev.hywmill.politics.war.Campaign c = dev.hywmill.politics.service.RelationProjector.campaignOf(GarrisonLedger.get(ow), player);
        if (c == null) {
            send(ctx.getSource(), "war raid NOT_ON_CAMPAIGN: You are not on campaign; join a war first ('/hywmill war join <ally> against <enemy>')");
            return 0;
        }
        var r = PoliticsActions.suggestRaid(ow, player, c.ally(), c.enemy(), draw);
        send(ctx.getSource(), "war raid " + r.code() + ": " + r.message());
        return r.ok() ? 1 : 0;
    }

    /** Post-M5: {@code /hywmill war admin siege <attacker> <target> [unwatched]} launches a siege now (no war needed). */
    private static int adminSiege(CommandContext<CommandSourceStack> ctx, boolean unwatched) {
        VillageRecord a = villageAt(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "attacker"));
        VillageRecord t = villageAt(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "target"));
        if (a == null || t == null) {
            return 0;
        }
        ServerLevel ow = ctx.getSource().getServer().overworld();
        var l = HywMillRuntime.require().sieges().launch(ow, a.villageId, t.villageId, null, ow.getGameTime(), false);
        if (l.ok() && unwatched) {
            l.siege().forceUnwatched = true;
            GarrisonLedger.get(ow).setDirty();
        }
        send(ctx.getSource(), "war siege " + l.refusal() + ": " + l.detail() + (l.ok() ? " id " + l.siege().id.toString().substring(0, 8)
                + " host " + l.siege().hostStart + (unwatched ? " (unwatched)" : "") : ""));
        return l.ok() ? 1 : 0;
    }

    /**
     * Post-M5: {@code /hywmill war admin recall-all}: wipes every siege (nothing decided); every host comes home at once.
     * {@code stowed} (DEV): as if every village were unloaded.
     */
    private static int recallAll(CommandContext<CommandSourceStack> ctx, boolean stowed) {
        ServerLevel ow = ctx.getSource().getServer().overworld();
        var lines = HywMillRuntime.require().sieges().recallAll(ow, GarrisonLedger.get(ow), ow.getGameTime(), stowed);
        send(ctx.getSource(), "war recall-all: " + lines.size() + " siege(s) wiped");
        lines.forEach(l -> send(ctx.getSource(), " " + l));
        return lines.size();
    }

    /** Post-M5: {@code /hywmill war admin mercs <attacker>}: the host of that village hires a free company now (before it deploys). */
    private static int adminMercs(CommandContext<CommandSourceStack> ctx) {
        VillageRecord a = villageAt(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "attacker"));
        if (a == null) {
            return 0;
        }
        ServerLevel ow = ctx.getSource().getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        var s = dev.hywmill.garrison.service.SiegeService.byAttacker(ledger, a.villageId);
        VillageRecord t = s == null ? null : ledger.get(s.target);
        if (s == null || t == null || s.phase == dev.hywmill.politics.war.Siege.Phase.BATTLE || s.phase == dev.hywmill.politics.war.Siege.Phase.RETURN) {
            send(ctx.getSource(), "war mercs NONE: " + a.name + " has no host on the way to a siege");
            return 0;
        }
        int n = HywMillRuntime.require().sieges().hireMercs(ow, ledger, s, a, t, ow.getGameTime(), true);
        send(ctx.getSource(), "war mercs " + (n > 0 ? "HIRED " + n + " " + s.mercCompany : "NONE: a company is already hired") + " host " + s.hostStart);
        return n;
    }

    /** Post-M5: suggest a siege to the village of the player's campaign against its enemy ({@code roll}: dev, forced draw). */
    private static int siege(CommandContext<CommandSourceStack> ctx, @Nullable UUID player, @Nullable Double draw, boolean free) {
        if (player == null) {
            return 0;
        }
        ServerLevel ow = ctx.getSource().getServer().overworld();
        dev.hywmill.politics.war.Campaign c = dev.hywmill.politics.service.RelationProjector.campaignOf(GarrisonLedger.get(ow), player);
        if (c == null) {
            send(ctx.getSource(), "war siege NOT_ON_CAMPAIGN: You are not on campaign; join a war first ('/hywmill war join <ally> against <enemy>')");
            return 0;
        }
        var r = PoliticsActions.suggestSiege(ow, player, c.ally(), c.enemy(), draw, free);
        send(ctx.getSource(), "war siege " + r.code() + ": " + r.message());
        return r.ok() ? 1 : 0;
    }

    /** Post-M5 admin: the village at {@code helper} promises relief to the siege of the village at {@code target}. */
    private static int adminRelief(CommandContext<CommandSourceStack> ctx, String fate) {
        VillageRecord h = villageAt(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "helper"));
        VillageRecord t = villageAt(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "target"));
        if (h == null || t == null) {
            return 0;
        }
        dev.hywmill.politics.war.Relief.Fate f;
        try {
            f = dev.hywmill.politics.war.Relief.Fate.valueOf(fate.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            send(ctx.getSource(), "war relief BAD_FATE: use clean, ambushed, routed, straggled or lost");
            return 0;
        }
        GarrisonLedger ledger = GarrisonLedger.get(ctx.getSource().getServer().overworld());
        dev.hywmill.politics.war.Siege s = dev.hywmill.garrison.service.SiegeService.against(ledger, t.villageId);
        if (s == null) {
            send(ctx.getSource(), "war relief NO_SIEGE: " + t.name + " is not besieged");
            return 0;
        }
        send(ctx.getSource(), "war relief OK: " + dev.hywmill.garrison.service.ReliefService.promise(ledger, s, h, f));
        return 1;
    }

    /** Post-M5: war or peace counsel to the village at {@code village} about the one at {@code other}. */
    private static int counsel(CommandContext<CommandSourceStack> ctx, @Nullable UUID player, dev.hywmill.politics.WarCounsel.Kind kind,
                               @Nullable Double draw, boolean force) {
        if (player == null) {
            return 0;
        }
        VillageRecord home = villageAt(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "village"));
        VillageRecord other = villageAt(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "other"));
        if (home == null || other == null) {
            return 0;
        }
        var r = PoliticsActions.suggestWarOrPeace(ctx.getSource().getServer().overworld(), player, home.villageId, other.villageId, kind, draw, force);
        send(ctx.getSource(), "war " + (kind == dev.hywmill.politics.WarCounsel.Kind.WAR ? "declare" : "peace") + " " + r.code() + ": " + r.message());
        return r.ok() ? 1 : 0;
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

package dev.villageroads;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.villageroads.integration.millenaire.MillVillages;
import dev.villageroads.world.RoadJob;
import dev.villageroads.world.RoadManager;
import dev.villageroads.world.Styles;
import dev.villageroads.world.RoadsData;
import dev.villageroads.world.VillageInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.Comparator;
import java.util.List;

/** /villageroads status | scan | link <near> <near> | lay <a> <b> [mode road bridge width] | undo | cancel | forget (operators). */
final class RoadCommands {
    private RoadCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("villageroads").requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> {
                    RoadManager m = VillageRoads.manager();
                    if (m == null) {
                        return 0;
                    }
                    m.status().forEach(l -> send(ctx, l));
                    return 1;
                }))
                .then(Commands.literal("scan").executes(ctx -> {
                    RoadManager m = VillageRoads.manager();
                    if (m == null) {
                        return 0;
                    }
                    int n = m.scan(ctx.getSource().getServer().overworld(), true);
                    send(ctx, "villageroads scan: " + n + " village link(s) queued" + (MillVillages.available() ? "" : " (Millénaire is not installed)"));
                    return n;
                }))
                .then(Commands.literal("link").then(Commands.argument("a", BlockPosArgument.blockPos()).then(Commands.argument("b", BlockPosArgument.blockPos())
                        .executes(RoadCommands::link))))
                .then(Commands.literal("lay").then(Commands.argument("a", BlockPosArgument.blockPos()).then(Commands.argument("b", BlockPosArgument.blockPos())
                        .executes(ctx -> lay(ctx, "road", "COBBLESTONE", "AUTO", 3))
                        .then(Commands.argument("mode", StringArgumentType.word()).then(Commands.argument("road", StringArgumentType.word())
                                .then(Commands.argument("bridge", StringArgumentType.word()).then(Commands.argument("width", IntegerArgumentType.integer(1, 7))
                                        .executes(ctx -> lay(ctx, StringArgumentType.getString(ctx, "mode"), StringArgumentType.getString(ctx, "road"),
                                                StringArgumentType.getString(ctx, "bridge"), IntegerArgumentType.getInteger(ctx, "width"))))))))))
                .then(Commands.literal("undo").executes(ctx -> {
                    RoadManager m = VillageRoads.manager();
                    if (m == null) {
                        return 0;
                    }
                    send(ctx, "villageroads undo: " + m.undo(owner(ctx)));
                    return 1;
                }))
                .then(Commands.literal("cancel").executes(ctx -> {
                    RoadManager m = VillageRoads.manager();
                    if (m != null) {
                        m.cancelAll();
                    }
                    send(ctx, "villageroads: every road job cancelled");
                    return 1;
                }))
                .then(Commands.literal("forget").executes(ctx -> {
                    RoadsData data = RoadsData.get(ctx.getSource().getServer().overworld());
                    RoadsData.forget(data);
                    send(ctx, "villageroads: the village links are forgotten (they will be looked at again; roads built stay)");
                    return 1;
                })));
    }

    /** Who a command's road belongs to: the player giving it, else the console. */
    static java.util.UUID owner(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getPlayer() != null ? ctx.getSource().getPlayer().getUUID() : RoadManager.CONSOLE;
    }

    /** A road (or bridge) between two points, as the staff lays it (it can be taken up with undo). */
    static int lay(CommandContext<CommandSourceStack> ctx, String mode, String road, String bridge, int width) {
        RoadManager m = VillageRoads.manager();
        if (m == null) {
            return 0;
        }
        RoadJob.Spec spec;
        try {
            spec = new RoadJob.Spec("bridge".equalsIgnoreCase(mode) ? RoadJob.Mode.BRIDGE : RoadJob.Mode.ROAD,
                    Styles.RoadStyle.valueOf(road.toUpperCase(java.util.Locale.ROOT)), Styles.BridgeStyle.valueOf(bridge.toUpperCase(java.util.Locale.ROOT)),
                    width % 2 == 0 ? width + 1 : width);
        } catch (IllegalArgumentException e) {
            send(ctx, "villageroads lay: road is one of " + java.util.Arrays.toString(Styles.RoadStyle.values()) + ", bridge one of "
                    + java.util.Arrays.toString(Styles.BridgeStyle.values()));
            return 0;
        }
        BlockPos a = BlockPosArgument.getBlockPos(ctx, "a"), b = BlockPosArgument.getBlockPos(ctx, "b");
        boolean ok = m.lay(owner(ctx), ctx.getSource().getLevel(), a, b, spec);
        send(ctx, ok ? "villageroads lay OK: " + spec : "villageroads lay: your last road is still being built");
        return ok ? 1 : 0;
    }

    /** A road between the villages nearest two places, at once. */
    static int link(CommandContext<CommandSourceStack> ctx) {
        ServerLevel ow = ctx.getSource().getServer().overworld();
        RoadManager m = VillageRoads.manager();
        List<VillageInfo> all = MillVillages.list(ow);
        if (m == null || all.size() < 2) {
            send(ctx, "villageroads link: no villages (is Millénaire installed?)");
            return 0;
        }
        BlockPos a = BlockPosArgument.getBlockPos(ctx, "a"), b = BlockPosArgument.getBlockPos(ctx, "b");
        VillageInfo va = all.stream().min(Comparator.comparingDouble(v -> v.center().distSqr(a))).orElseThrow();
        VillageInfo vb = all.stream().min(Comparator.comparingDouble(v -> v.center().distSqr(b))).orElseThrow();
        if (va == vb) {
            send(ctx, "villageroads link: both places are nearest " + va.name());
            return 0;
        }
        m.link(va, vb, all);
        send(ctx, "villageroads link OK: " + va.name() + " (" + va.center().toShortString() + ", edge " + va.radius() + ") -> " + vb.name()
                + " (" + vb.center().toShortString() + ", edge " + vb.radius() + ")");
        return 1;
    }

    static void send(CommandContext<CommandSourceStack> ctx, String s) {
        ctx.getSource().sendSuccess(() -> Component.literal(s), true);
    }
}

package dev.hywmill.recruit;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.Services;
import dev.hywmill.politics.Standing;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.UUID;

/**
 * DEV ONLY ({@code /hywmill dev recruit ...}): drives the Muster Roll without a client, for the server harness. A stand-in
 * player (fixed UUID) stands at the block, holds the given money and the given standing with the block's village.
 */
public final class RecruitCommands {
    private RecruitCommands() {}

    public static final UUID STANDIN = UUID.fromString("33333333-4444-4555-8666-777777777777");

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("recruit").requires(s -> HywMillConfig.DEV_COMMANDS.get())
                .then(Commands.literal("offers").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("standing", StringArgumentType.word()).executes(RecruitCommands::offers))))
                .then(Commands.literal("hire").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("key", StringArgumentType.string())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .then(Commands.argument("deniers", IntegerArgumentType.integer(0))
                                                .then(Commands.argument("standing", StringArgumentType.word()).executes(RecruitCommands::hire)))))));
    }

    private static FakePlayer standin(CommandContext<CommandSourceStack> c, BlockPos pos, int deniers) {
        ServerLevel level = c.getSource().getServer().overworld();
        FakePlayer p = FakePlayerFactory.get(level, new GameProfile(STANDIN, "hywmill_recruiter"));
        p.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 1.5);
        p.getInventory().clearContent();
        SettlementSource source = Services.settlements();
        if (source != null && deniers > 0) {
            source.giveMoney(p, deniers);
        }
        return p;
    }

    private static boolean setStanding(ServerLevel overworld, BlockPos pos, Standing st) {
        VillageRecord rec = RecruitService.villageAt(overworld, pos);
        if (rec == null) {
            return false;
        }
        rec.politics.get(STANDIN).status = st;
        return true;
    }

    private static int offers(CommandContext<CommandSourceStack> c) {
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        ServerLevel overworld = c.getSource().getServer().overworld();
        Standing st = Standing.valueOf(StringArgumentType.getString(c, "standing"));
        VillageRecord rec = RecruitService.villageAt(overworld, pos);
        if (rec == null || !setStanding(overworld, pos, st)) {
            c.getSource().sendSuccess(() -> Component.literal("recruit: no village at " + pos.toShortString()), false);
            return 0;
        }
        var list = RecruitService.offers(overworld, rec, STANDIN);
        c.getSource().sendSuccess(() -> Component.literal("recruit: village " + rec.name + " " + rec.culture + " " + rec.tier + " standing " + st
                + " gear " + RecruitOffers.gearTier(st, rec.tier, RecruitTables.current()) + " offers " + list.size()), false);
        for (RecruitOffers.Offer o : list) {
            c.getSource().sendSuccess(() -> Component.literal("recruit offer " + o.key() + " " + o.gearTier() + " " + o.price()), false);
        }
        return list.size();
    }

    private static int hire(CommandContext<CommandSourceStack> c) {
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        ServerLevel overworld = c.getSource().getServer().overworld();
        Standing st = Standing.valueOf(StringArgumentType.getString(c, "standing"));
        FakePlayer p = standin(c, pos, IntegerArgumentType.getInteger(c, "deniers"));
        setStanding(overworld, pos, st);
        RecruitService.Outcome o = RecruitService.doHire(p, p, pos, StringArgumentType.getString(c, "key"), IntegerArgumentType.getInteger(c, "count"));
        SettlementSource source = Services.settlements();
        int left = source == null ? -1 : source.playerMoney(p);
        c.getSource().sendSuccess(() -> Component.literal("recruit: ok=" + o.ok() + " hired=" + o.hired() + " left=" + left + " owner=" + STANDIN
                + " msg=" + o.message()), false);
        return o.hired();
    }
}

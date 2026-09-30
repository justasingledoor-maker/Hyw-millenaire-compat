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
                                                .then(Commands.argument("standing", StringArgumentType.word()).executes(RecruitCommands::hire)))))))
                .then(Commands.literal("colours").then(Commands.argument("first", IntegerArgumentType.integer(-1, 15))
                        .then(Commands.argument("second", IntegerArgumentType.integer(-1, 15)).executes(c -> {
                            // the stand-in's colours for the soldiers it hires (dye ids; -1 clears)
                            int a = IntegerArgumentType.getInteger(c, "first"), b = IntegerArgumentType.getInteger(c, "second");
                            ServerLevel ow = c.getSource().getServer().overworld();
                            var ledger = dev.hywmill.settlement.GarrisonLedger.get(ow);
                            if (a < 0 || b < 0) {
                                ledger.playerColours().remove(STANDIN);
                            } else {
                                ledger.playerColours().put(STANDIN, new int[]{a, b});
                            }
                            ledger.setDirty();
                            c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("recruit: colours " + a + " " + b), false);
                            return 1;
                        }))))
                .then(Commands.literal("apologize").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("hits", IntegerArgumentType.integer(0, 50))
                                .then(Commands.argument("deniers", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("pay", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                                .executes(RecruitCommands::apologize))))));
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

    /**
     * The apology through the stand-in: {@code hits} garrison assaults inside the village are added to its grievance, then it
     * asks for (and with {@code pay}, pays) an apology with {@code deniers} in hand.
     */
    private static int apologize(CommandContext<CommandSourceStack> c) {
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        ServerLevel overworld = c.getSource().getServer().overworld();
        VillageRecord rec = RecruitService.villageAt(overworld, pos);
        dev.hywmill.core.HywMillRuntime rt = dev.hywmill.core.HywMillRuntime.get();
        if (rec == null || rt == null) {
            c.getSource().sendSuccess(() -> Component.literal("apology: no village at " + pos.toShortString()), false);
            return 0;
        }
        FakePlayer p = standin(c, pos, IntegerArgumentType.getInteger(c, "deniers"));
        for (int i = IntegerArgumentType.getInteger(c, "hits"); i > 0; i--) {
            rt.politics().applyGrievance(overworld, rec, STANDIN, new dev.hywmill.politics.GrievanceEvent(dev.hywmill.politics.GrievanceKind.ASSAULT_GARRISON,
                    overworld.getGameTime(), true, true, false, pos.getX(), pos.getY(), pos.getZ()));
        }
        Standing before = rec.politics.get(STANDIN).status;
        var res = rt.politics().apology(overworld, rec, p, com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "pay"));
        SettlementSource source = Services.settlements();
        int left = source == null ? -1 : source.playerMoney(p);
        c.getSource().sendSuccess(() -> Component.literal("apology: before=" + before + " grievance=" + String.format("%.1f", res.quote().grievance())
                + " outcome=" + res.quote().outcome() + " price=" + res.quote().price() + " paid=" + res.paid() + " after=" + res.status() + " left=" + left), false);
        return res.paid() ? 1 : 0;
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

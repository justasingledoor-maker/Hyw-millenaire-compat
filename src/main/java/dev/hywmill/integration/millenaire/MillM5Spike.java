package dev.hywmill.integration.millenaire;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.combat.raid.RaidManager;
import org.millenaire.commerce.ShopProfile;
import org.millenaire.commerce.ShopProfileLoader;
import org.millenaire.commerce.TradeGood;
import org.millenaire.commerce.TradeGoodsLoader;
import org.millenaire.village.PlayerCultureReputation;
import org.millenaire.village.Village;
import org.millenaire.village.VillageDiplomacyHelper;
import org.millenaire.village.VillageHistoryEntry;
import org.millenaire.village.VillageSavedData;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M5-0 spike subtree ({@code /hywmill dev m5 mill ...}; dev only). Public Millénaire 9.0.2 calls
 * only (checked with javap): Village.getRelation/setRelation/adjustRelationSymmetric/getRelations/
 * recordEvent/getHistory/getHistoryStartTick/getReputation/getCombinedReputation/adjustReputation/
 * getRaidTarget/getRaidPlanningStart/getRaidStart, RaidManager.planRaid,
 * VillageDiplomacyHelper.performNightlyDiplomacyDrift, PlayerCultureReputation.get/
 * getDiplomacyPoints/consumeDiplomacyPoint/regenerateDiplomacyPoints/hasDiscoveredVillage/
 * markVillageDiscovered, TradeGoodsLoader.getGoods/getGoodById, ShopProfileLoader.getProfile.
 */
public final class MillM5Spike {
    private MillM5Spike() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("mill")
                .then(Commands.literal("mrel").then(Commands.argument("a", BlockPosArgument.blockPos())
                        .then(Commands.argument("b", BlockPosArgument.blockPos()).executes(ctx -> mrel(ctx, null, null))
                                .then(Commands.literal("set").then(Commands.argument("v", IntegerArgumentType.integer(-100, 100))
                                        .executes(ctx -> mrel(ctx, "set", IntegerArgumentType.getInteger(ctx, "v")))))
                                .then(Commands.literal("adjust").then(Commands.argument("v", IntegerArgumentType.integer(-200, 200))
                                        .executes(ctx -> mrel(ctx, "adjust", IntegerArgumentType.getInteger(ctx, "v"))))))))
                .then(Commands.literal("drift").then(Commands.argument("a", BlockPosArgument.blockPos())
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 1000)).executes(MillM5Spike::drift))))
                .then(Commands.literal("raidplan").then(Commands.argument("a", BlockPosArgument.blockPos())
                        .then(Commands.argument("b", BlockPosArgument.blockPos()).executes(MillM5Spike::raidPlan))))
                .then(Commands.literal("raid").then(Commands.argument("a", BlockPosArgument.blockPos()).executes(MillM5Spike::raid)))
                .then(Commands.literal("dpoints").then(Commands.argument("a", BlockPosArgument.blockPos())
                        .then(Commands.argument("player", UuidArgument.uuid()).executes(ctx -> dpoints(ctx, ""))
                                .then(Commands.argument("op", StringArgumentType.word())
                                        .executes(ctx -> dpoints(ctx, StringArgumentType.getString(ctx, "op")))))))
                .then(Commands.literal("history").then(Commands.argument("a", BlockPosArgument.blockPos()).executes(ctx -> history(ctx, null))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> history(ctx, StringArgumentType.getString(ctx, "text"))))))
                .then(Commands.literal("rep").then(Commands.argument("a", BlockPosArgument.blockPos())
                        .then(Commands.argument("player", UuidArgument.uuid()).executes(ctx -> rep(ctx, 0, false))
                                .then(Commands.literal("adjust").then(Commands.argument("v", IntegerArgumentType.integer(-100000, 100000))
                                        .executes(ctx -> rep(ctx, IntegerArgumentType.getInteger(ctx, "v"), true)))))))
                .then(Commands.literal("discover").then(Commands.argument("a", BlockPosArgument.blockPos())
                        .then(Commands.argument("player", UuidArgument.uuid()).executes(MillM5Spike::discover))))
                .then(Commands.literal("goods").then(Commands.argument("culture", StringArgumentType.string())
                        .then(Commands.argument("id", StringArgumentType.word()).executes(MillM5Spike::goods))))
                .then(Commands.literal("shop").then(Commands.argument("culture", StringArgumentType.string())
                        .then(Commands.argument("shop", StringArgumentType.word()).executes(MillM5Spike::shop))));
    }

    private static void send(CommandSourceStack src, String s) {
        src.sendSuccess(() -> Component.literal(s), false);
    }

    private static Village at(CommandContext<CommandSourceStack> ctx, String arg) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getServer().overworld();
        BlockPos p = BlockPosArgument.getBlockPos(ctx, arg);
        return VillageSavedData.get(level).getVillageManager().findNearestVillage(p, 96);
    }

    private static String id8(Village v) {
        return v.getId().uuid().toString().substring(0, 8);
    }

    private static int mrel(CommandContext<CommandSourceStack> ctx, String op, Integer v) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getServer().overworld();
        Village a = at(ctx, "a");
        Village b = at(ctx, "b");
        if (a == null || b == null || a == b) {
            send(ctx.getSource(), "m5 mrel villages not found");
            return 0;
        }
        String before = a.getRelation(b.getId()) + "/" + b.getRelation(a.getId());
        if ("set".equals(op)) {
            a.setRelation(b.getId(), v);
            b.setRelation(a.getId(), v);
        } else if ("adjust".equals(op)) {
            a.adjustRelationSymmetric(level, b.getId(), v, false);
        }
        send(ctx.getSource(), "m5 mrel " + id8(a) + "<->" + id8(b) + " before=" + before + " now=" + a.getRelation(b.getId())
                + "/" + b.getRelation(a.getId()) + " op=" + op + " v=" + v);
        return 1;
    }

    private static int drift(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getServer().overworld();
        Village a = at(ctx, "a");
        int n = IntegerArgumentType.getInteger(ctx, "n");
        if (a == null) {
            return 0;
        }
        Map<?, Integer> start = Map.copyOf(a.getRelations());
        int changed = 0;
        int totalAbs = 0;
        for (int i = 0; i < n; i++) {
            Map<?, Integer> before = Map.copyOf(a.getRelations());
            VillageDiplomacyHelper.performNightlyDiplomacyDrift(level, a);
            for (Map.Entry<?, Integer> e : a.getRelations().entrySet()) {
                int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
                if (d != 0) {
                    changed++;
                    totalAbs += Math.abs(d);
                }
            }
        }
        send(ctx.getSource(), "m5 drift " + id8(a) + " nights=" + n + " pairChanges=" + changed + " totalAbs=" + totalAbs
                + " start=" + start.values() + " end=" + a.getRelations().values());
        return 1;
    }

    private static int raidPlan(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getServer().overworld();
        Village a = at(ctx, "a");
        Village b = at(ctx, "b");
        if (a == null || b == null || a == b) {
            return 0;
        }
        RaidManager.planRaid(a, b, level);
        send(ctx.getSource(), "m5 raidplan " + id8(a) + "->" + id8(b) + " planning=" + a.getRaidPlanningStart() + " start=" + a.getRaidStart()
                + " dayTime=" + level.getDayTime() + " relation=" + a.getRelation(b.getId()));
        return 1;
    }

    private static int raid(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getServer().overworld();
        Village a = at(ctx, "a");
        if (a == null) {
            return 0;
        }
        send(ctx.getSource(), "m5 raid " + id8(a) + " target=" + (a.getRaidTarget() == null ? "none" : a.getRaidTarget().uuid().toString().substring(0, 8))
                + " planning=" + a.getRaidPlanningStart() + " start=" + a.getRaidStart() + " dayTime=" + level.getDayTime()
                + " performed=" + a.getRaidsPerformed().size());
        return 1;
    }

    private static int dpoints(CommandContext<CommandSourceStack> ctx, String op) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getServer().overworld();
        Village a = at(ctx, "a");
        UUID p = UuidArgument.getUuid(ctx, "player");
        if (a == null) {
            return 0;
        }
        PlayerCultureReputation pcr = PlayerCultureReputation.get(level);
        int before = pcr.getDiplomacyPoints(p, a.getId());
        String r = "";
        if (op.equals("consume")) {
            r = " consumed=" + pcr.consumeDiplomacyPoint(p, a.getId());
        } else if (op.equals("regen")) {
            pcr.regenerateDiplomacyPoints(p, a.getId());
        } else if (op.equals("nightly")) {
            VillageDiplomacyHelper.regenerateDiplomacyPointsForPlayers(level, a);
        }
        send(ctx.getSource(), "m5 dpoints " + id8(a) + " player=" + p.toString().substring(0, 8) + " before=" + before + r
                + " now=" + pcr.getDiplomacyPoints(p, a.getId()) + " max=" + PlayerCultureReputation.MAX_DIPLOMACY_POINTS + " op=" + op);
        return 1;
    }

    private static int history(CommandContext<CommandSourceStack> ctx, String text) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getServer().overworld();
        Village a = at(ctx, "a");
        if (a == null) {
            return 0;
        }
        if (text != null) {
            a.recordEvent(level, text);
        }
        List<VillageHistoryEntry> h = a.getHistory();
        StringBuilder last = new StringBuilder();
        for (int i = Math.max(0, h.size() - 3); i < h.size(); i++) {
            last.append(" | t=").append(h.get(i).tick()).append(" '").append(h.get(i).message()).append("'");
        }
        send(ctx.getSource(), "m5 history " + id8(a) + " size=" + h.size() + " startTick=" + a.getHistoryStartTick() + last);
        return 1;
    }

    private static int rep(CommandContext<CommandSourceStack> ctx, int delta, boolean adjust) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getServer().overworld();
        Village a = at(ctx, "a");
        UUID p = UuidArgument.getUuid(ctx, "player");
        if (a == null) {
            return 0;
        }
        PlayerCultureReputation pcr = PlayerCultureReputation.get(level);
        String before = a.getReputation().get(p) + "/" + pcr.get(p, a.getCultureId()) + "/" + a.getCombinedReputation(level, p);
        int ret = adjust ? a.adjustReputation(level, p, delta) : 0;
        send(ctx.getSource(), "m5 rep " + id8(a) + " player=" + p.toString().substring(0, 8) + " village/culture/combined before=" + before
                + " now=" + a.getReputation().get(p) + "/" + pcr.get(p, a.getCultureId()) + "/" + a.getCombinedReputation(level, p)
                + (adjust ? " adjust=" + delta + " returned=" + ret : ""));
        return 1;
    }

    private static int discover(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getServer().overworld();
        Village a = at(ctx, "a");
        UUID p = UuidArgument.getUuid(ctx, "player");
        if (a == null) {
            return 0;
        }
        PlayerCultureReputation pcr = PlayerCultureReputation.get(level);
        boolean before = pcr.hasDiscoveredVillage(p, a.getId());
        boolean marked = pcr.markVillageDiscovered(p, a.getId());
        send(ctx.getSource(), "m5 discover " + id8(a) + " before=" + before + " marked=" + marked + " now=" + pcr.hasDiscoveredVillage(p, a.getId()));
        return 1;
    }

    private static int goods(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation culture = ResourceLocation.parse(StringArgumentType.getString(ctx, "culture"));
        String id = StringArgumentType.getString(ctx, "id");
        TradeGood g = TradeGoodsLoader.getGoodById(culture, id);
        send(ctx.getSource(), "m5 goods " + culture + " total=" + TradeGoodsLoader.getGoods(culture).size() + " " + id + "="
                + (g == null ? "none" : g.item() + " sell=" + g.sellingPrice() + " buy=" + g.buyingPrice() + " minRep=" + g.minReputation()
                + " resolved=" + g.resolveItem()));
        return g == null ? 0 : 1;
    }

    private static int shop(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation culture = ResourceLocation.parse(StringArgumentType.getString(ctx, "culture"));
        String name = StringArgumentType.getString(ctx, "shop");
        ShopProfile p = ShopProfileLoader.getProfile(culture, name);
        send(ctx.getSource(), "m5 shop " + culture + " " + name + " = " + (p == null ? "none" : p.toString()));
        return p == null ? 0 : 1;
    }
}

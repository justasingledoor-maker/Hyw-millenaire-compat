package dev.villageroads.item;

import dev.villageroads.RoadsConfig;
import dev.villageroads.VillageRoads;
import dev.villageroads.net.RoadPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * The Surveyor's Staff: used on a block it marks the first point, on another the second, and opens the survey screen (a road
 * that finds its way, or a straight bridge; what it is made of; how wide). Used in the air while sneaking it forgets the first
 * point.
 */
public final class SurveyorsStaff extends Item {
    public SurveyorsStaff(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (!(ctx.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = ctx.getItemInHand();
        BlockPos pos = ctx.getClickedPos();
        BlockPos a = stack.get(VillageRoads.POINT_A.get());
        if (a == null) {
            stack.set(VillageRoads.POINT_A.get(), pos);
            player.displayClientMessage(Component.literal("First point: (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                    + "). Use the staff on the second point."), true);
            return InteractionResult.CONSUME;
        }
        stack.remove(VillageRoads.POINT_A.get());
        double d = Math.sqrt(a.distSqr(pos));
        if (d < 2) {
            player.displayClientMessage(Component.literal("The two points are the same. Survey cleared."), true);
        } else if (d > RoadsConfig.STAFF_REACH.get()) {
            player.displayClientMessage(Component.literal("The points are " + (int) d + " blocks apart; the staff reaches "
                    + RoadsConfig.STAFF_REACH.get() + ". Survey cleared.").withStyle(ChatFormatting.RED), false);
        } else {
            int choices = stack.getOrDefault(VillageRoads.CHOICES.get(), 0);
            PacketDistributor.sendToPlayer(player, new RoadPayloads.OpenSurvey(a, pos, choices));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && stack.has(VillageRoads.POINT_A.get())) {
            if (!level.isClientSide) {
                stack.remove(VillageRoads.POINT_A.get());
                player.displayClientMessage(Component.literal("Survey cleared."), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(VillageRoads.POINT_A.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        BlockPos a = stack.get(VillageRoads.POINT_A.get());
        if (a != null) {
            lines.add(Component.literal("First point: " + a.getX() + ", " + a.getY() + ", " + a.getZ()).withStyle(ChatFormatting.GOLD));
        }
        lines.add(Component.literal("Use on a block: the first point, then the second.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Then choose a road or a bridge, and what it is made of.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Sneak and use in the air: forget the first point.").withStyle(ChatFormatting.GRAY));
    }
}

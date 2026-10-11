package dev.assaultcrossbow.client;

import dev.assaultcrossbow.AssaultCrossbow;
import dev.assaultcrossbow.AssaultCrossbowItem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** The loaded look, the two-handed aim while loaded, and the rounds left beside the crosshair. */
@EventBusSubscriber(modid = AssaultCrossbow.ID, value = Dist.CLIENT)
public final class CrossbowClient {
    private CrossbowClient() {}

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AssaultCrossbow.ID, path);
    }

    @SubscribeEvent
    static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> ItemProperties.register(AssaultCrossbow.CROSSBOW.get(), id("loaded"),
                (stack, level, entity, seed) -> AssaultCrossbowItem.rounds(stack) > 0 ? 1.0F : 0.0F));
    }

    @SubscribeEvent
    static void extensions(RegisterClientExtensionsEvent e) {
        e.registerItem(new IClientItemExtensions() {
            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return AssaultCrossbowItem.rounds(stack) > 0 ? HumanoidModel.ArmPose.CROSSBOW_HOLD : null;
            }
        }, AssaultCrossbow.CROSSBOW.get());
    }

    @SubscribeEvent
    static void layers(RegisterGuiLayersEvent e) {
        e.registerAbove(VanillaGuiLayers.CROSSHAIR, id("magazine"), CrossbowClient::magazine);
    }

    static void magazine(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        ItemStack s = mc.player.getMainHandItem();
        if (!s.is(AssaultCrossbow.CROSSBOW.get())) {
            s = mc.player.getOffhandItem();
            if (!s.is(AssaultCrossbow.CROSSBOW.get())) {
                return;
            }
        }
        int rounds = AssaultCrossbowItem.rounds(s);
        long until = AssaultCrossbowItem.state(s).reloadUntil();
        String text;
        int colour;
        if (until > 0) {
            long left = Math.max(0, until - mc.level.getGameTime());
            int pct = (int) Math.min(100, 100 - left * 100 / AssaultCrossbowItem.RELOAD);
            text = "Reloading " + pct + "%";
            colour = 0xFF7FC4FF;
        } else {
            text = rounds + " / " + AssaultCrossbowItem.MAGAZINE;
            colour = rounds == 0 ? 0xFFFF5555 : rounds <= 5 ? 0xFFFFAA33 : 0xFFFFFFFF;
        }
        g.drawString(mc.font, text, g.guiWidth() / 2 + 10, g.guiHeight() / 2 + 6, colour, true);
    }
}

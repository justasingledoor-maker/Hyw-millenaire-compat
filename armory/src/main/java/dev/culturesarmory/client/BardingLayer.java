package dev.culturesarmory.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HorseModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.HorseRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The bardings in 3D, as Epic Knights draws its caparison: a layer on every horse renderer (vanilla horses and HYW's) that
 * draws the barding's own model over the horse, posed like it (the same part tree, animated the same way), a dyed
 * barding tinted by its dye with an untinted overlay. The items' vanilla armour texture is blank, so the game's own horse
 * armour layer draws nothing for them.
 */
public final class BardingLayer extends RenderLayer<Horse, HorseModel<Horse>> {
    private record Baked(ArmoryBardings.Look look, HorseModel<Horse> model) {}

    private final Map<Item, Baked> byItem = new IdentityHashMap<>();

    BardingLayer(RenderLayerParent<Horse, HorseModel<Horse>> parent, EntityModelSet models) {
        super(parent);
        for (ArmoryBardings.Look look : ArmoryBardings.LOOKS) {
            byItem.put(look.item().get(), new Baked(look, new HorseModel<>(models.bakeLayer(look.layer()))));
        }
    }

    /** The models' layer definitions, and the layer on each horse renderer (mod bus, client only). */
    public static void register(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterLayerDefinitions e) -> {
            for (ArmoryBardings.Look look : ArmoryBardings.LOOKS) {
                e.registerLayerDefinition(look.layer(), look.mesh());
            }
        });
        modBus.addListener((EntityRenderersEvent.AddLayers e) -> {
            for (EntityType<?> type : e.getEntityTypes()) {
                if (e.getRenderer(type) instanceof HorseRenderer r) {
                    r.addLayer(new BardingLayer(r, e.getEntityModels()));
                }
            }
        });
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, Horse horse, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack stack = horse.getBodyArmorItem();
        Baked baked = byItem.get(stack.getItem());
        if (baked == null) {
            return;
        }
        HorseModel<Horse> model = baked.model();
        getParentModel().copyPropertiesTo(model);
        model.prepareMobModel(horse, limbSwing, limbSwingAmount, partialTick);
        model.setupAnim(horse, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        ArmoryBardings.Look look = baked.look();
        int tint = look.dyeable() ? FastColor.ARGB32.opaque(DyedItemColor.getOrDefault(stack, look.dye())) : -1;
        model.renderToBuffer(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(look.texture())), light, OverlayTexture.NO_OVERLAY, tint);
        if (look.overlay() != null) {
            model.renderToBuffer(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(look.overlay())), light, OverlayTexture.NO_OVERLAY, -1);
        }
    }
}

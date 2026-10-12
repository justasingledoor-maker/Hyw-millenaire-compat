package dev.villageroads;

import com.mojang.serialization.Codec;
import dev.villageroads.item.SurveyorsStaff;
import dev.villageroads.net.RoadPayloads;
import dev.villageroads.world.RoadJob;
import dev.villageroads.world.RoadManager;
import dev.villageroads.world.Styles.BridgeStyle;
import dev.villageroads.world.Styles.RoadStyle;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Village Roads: roads and bridges between villages. Each Millénaire village is linked to its neighbours by a road that finds
 * its own way round buildings, water and cliffs, felling trees and filling hollows, and crosses a lake by a bridge where that
 * is shorter than going round. The Surveyor's Staff lets a player lay a road or a bridge between two points.
 */
@Mod(VillageRoads.ID)
public final class VillageRoads {
    public static final String ID = "villageroads";

    static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ID);
    /** The staff's first point. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> POINT_A = COMPONENTS.registerComponentType("point_a",
            b -> b.persistent(BlockPos.CODEC).networkSynchronized(BlockPos.STREAM_CODEC));
    /** The staff's last choices on the survey screen. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> CHOICES = COMPONENTS.registerComponentType("choices",
            b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));
    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    public static final DeferredItem<SurveyorsStaff> STAFF = ITEMS.register("surveyors_staff", () -> new SurveyorsStaff(new Item.Properties().stacksTo(1)));

    /** The running server's roads (null between servers). */
    private static RoadManager manager;

    public VillageRoads(IEventBus modBus, ModContainer container) {
        COMPONENTS.register(modBus);
        ITEMS.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, RoadsConfig.SPEC);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, e -> {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                e.accept(STAFF.get());
            }
        });
        modBus.addListener(RegisterPayloadHandlersEvent.class, VillageRoads::payloads);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> manager = new RoadManager(e.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> {
            if (manager != null) {
                manager.stop();
                manager = null;
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
            if (manager != null) {
                manager.tick();
            }
        });
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> RoadCommands.register(e.getDispatcher()));
    }

    public static RoadManager manager() {
        return manager;
    }

    static void payloads(RegisterPayloadHandlersEvent e) {
        var r = e.registrar("1");
        r.playToClient(RoadPayloads.OpenSurvey.TYPE, RoadPayloads.OpenSurvey.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> dev.villageroads.client.RoadsClient.openSurvey(p)));
        r.playToServer(RoadPayloads.Build.TYPE, RoadPayloads.Build.CODEC, (p, ctx) -> ctx.enqueueWork(() -> build(p, ctx)));
        r.playToServer(RoadPayloads.Undo.TYPE, RoadPayloads.Undo.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp && manager != null) {
                sp.displayClientMessage(Component.literal(manager.undo(sp)), false);
            }
        }));
    }

    /** A player asks for a road: checked again here (he holds the staff, the points are within its reach, he may build). */
    static void build(RoadPayloads.Build p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp) || manager == null) {
            return;
        }
        ItemStack staff = sp.getItemInHand(InteractionHand.MAIN_HAND).is(STAFF.get()) ? sp.getItemInHand(InteractionHand.MAIN_HAND)
                : sp.getItemInHand(InteractionHand.OFF_HAND).is(STAFF.get()) ? sp.getItemInHand(InteractionHand.OFF_HAND) : null;
        if (staff == null) {
            sp.displayClientMessage(Component.literal("Hold the Surveyor's Staff to build.").withStyle(ChatFormatting.RED), false);
            return;
        }
        if (RoadsConfig.STAFF_CREATIVE_ONLY.get() && !sp.isCreative()) {
            sp.displayClientMessage(Component.literal("Only builders in creative mode may lay roads here.").withStyle(ChatFormatting.RED), false);
            return;
        }
        if (Math.sqrt(p.a().distSqr(p.b())) > RoadsConfig.STAFF_REACH.get() || !sp.serverLevel().isLoaded(p.a()) && !sp.serverLevel().isLoaded(p.b())) {
            sp.displayClientMessage(Component.literal("Those points are out of the staff's reach.").withStyle(ChatFormatting.RED), false);
            return;
        }
        RoadPayloads.Choices c = RoadPayloads.Choices.unpack(p.choices());
        staff.set(CHOICES.get(), c.pack());
        RoadJob.Spec spec = new RoadJob.Spec(c.mode() == 1 ? RoadJob.Mode.BRIDGE : RoadJob.Mode.ROAD,
                RoadStyle.values()[Math.min(c.road(), RoadStyle.values().length - 1)],
                BridgeStyle.values()[Math.min(c.bridge(), BridgeStyle.values().length - 1)], c.width() == 5 ? 5 : 3);
        if (!manager.lay(sp, p.a(), p.b(), spec)) {
            sp.displayClientMessage(Component.literal("Your last road is still being built.").withStyle(ChatFormatting.RED), false);
            return;
        }
        sp.displayClientMessage(Component.literal("Surveying the way for your " + (spec.mode() == RoadJob.Mode.BRIDGE ? "bridge" : "road") + "..."), true);
    }
}

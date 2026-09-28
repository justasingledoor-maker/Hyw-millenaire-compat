package dev.hywmill.recruit;

import dev.hywmill.HywMill;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registration of the Muster Roll block, its item and block entity (post-M5 recruitment). */
public final class MusterRoll {
    private MusterRoll() {}

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(HywMill.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(HywMill.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, HywMill.MODID);

    public static final DeferredBlock<MusterRollBlock> BLOCK = BLOCKS.register("muster_roll", () -> new MusterRollBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD).noOcclusion()));
    public static final DeferredItem<BlockItem> ITEM = ITEMS.register("muster_roll", () -> new BlockItem(BLOCK.get(), new Item.Properties()));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MusterRollBlockEntity>> BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "muster_roll", () -> BlockEntityType.Builder.of(MusterRollBlockEntity::new, BLOCK.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(MusterRoll::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            e.accept(ITEM.get());
        }
    }
}

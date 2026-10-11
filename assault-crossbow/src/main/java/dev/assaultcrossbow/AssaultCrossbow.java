package dev.assaultcrossbow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Assault Crossbow: one repeating crossbow for the player. A 30-bolt magazine of arrows; holding the use key fires it
 * fully automatic (the game repeats a held use every 4 ticks), each tap fires one bolt. Nothing else in the game is given it.
 */
@Mod(AssaultCrossbow.ID)
public final class AssaultCrossbow {
    public static final String ID = "assaultcrossbow";

    /** The time of the last shot and the time the reload in progress ends (0: none), in game ticks. */
    public record State(long lastShot, long reloadUntil) {
        public static final State NONE = new State(0, 0);
        public static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("last_shot").forGetter(State::lastShot),
                Codec.LONG.fieldOf("reload_until").forGetter(State::reloadUntil)).apply(i, State::new));
        public static final StreamCodec<ByteBuf, State> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, State::lastShot, ByteBufCodecs.VAR_LONG, State::reloadUntil, State::new);
    }

    static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<State>> STATE = COMPONENTS.registerComponentType("state",
            b -> b.persistent(State.CODEC).networkSynchronized(State.STREAM_CODEC));
    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    public static final DeferredItem<AssaultCrossbowItem> CROSSBOW = ITEMS.register("assault_crossbow",
            () -> new AssaultCrossbowItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public AssaultCrossbow(IEventBus modBus) {
        COMPONENTS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, e -> {
            if (e.getTabKey() == CreativeModeTabs.COMBAT) {
                e.accept(CROSSBOW.get());
            }
        });
        NeoForge.EVENT_BUS.addListener(ProjectileImpactEvent.class, AssaultCrossbow::impact);
    }

    /** Its bolts come too fast for a target's half second of hurt immunity: each one lands in full. */
    static void impact(ProjectileImpactEvent e) {
        if (e.getProjectile() instanceof AbstractArrow a && e.getRayTraceResult() instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity target) {
            ItemStack weapon = a.getWeaponItem();
            if (weapon != null && weapon.is(CROSSBOW.get())) {
                target.invulnerableTime = 0;
            }
        }
    }
}

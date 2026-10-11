package dev.assaultcrossbow;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * The repeating crossbow. Its magazine is the stack's charged projectiles: one arrow stack of up to {@link #MAGAZINE}.
 * The server fires on each use: a held use key repeats every 4 ticks (about 5 bolts a second), taps as fast as
 * {@link #TAP_GAP} allows. Empty, it reloads from the player's arrows in {@link #RELOAD} ticks; sneak + use tops it up.
 */
public class AssaultCrossbowItem extends ProjectileWeaponItem {
    public static final int MAGAZINE = 30;
    /** The least time between two shots, in ticks (taps faster than this are ignored). */
    public static final int TAP_GAP = 2;
    public static final int RELOAD = 30;
    public static final float SPEED = 3.15F, SPREAD = 1.25F;

    public AssaultCrossbowItem(Properties properties) {
        super(properties);
    }

    /** The loaded arrows (one stack, its count the rounds left); empty when the magazine is. */
    public static ItemStack ammo(ItemStack weapon) {
        ChargedProjectiles c = weapon.getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
        return c.isEmpty() ? ItemStack.EMPTY : c.getItems().get(0);
    }

    public static int rounds(ItemStack weapon) {
        return ammo(weapon).getCount();
    }

    public static AssaultCrossbow.State state(ItemStack weapon) {
        return weapon.getOrDefault(AssaultCrossbow.STATE.get(), AssaultCrossbow.State.NONE);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel sl) {
            long now = sl.getGameTime();
            AssaultCrossbow.State st = state(stack);
            int rounds = rounds(stack);
            if (st.reloadUntil() > 0) {
                // reloading: a held key fires again as soon as it is done
            } else if (rounds > 0 && !(player.isShiftKeyDown() && rounds < MAGAZINE)) {
                if (now - st.lastShot() >= TAP_GAP || now < st.lastShot()) {
                    fire(sl, player, stack, now);
                }
            } else {
                startReload(sl, player, stack);
            }
        }
        // consumed on both sides, so the off hand is never used instead; the hand dips a little each shot (recoil)
        return InteractionResultHolder.consume(stack);
    }

    private void fire(ServerLevel level, Player player, ItemStack stack, long now) {
        ItemStack ammo = ammo(stack);
        ItemStack bolt = ammo.copyWithCount(1);
        ItemStack rest = ammo.copyWithCount(ammo.getCount() - 1);
        stack.set(DataComponents.CHARGED_PROJECTILES, rest.isEmpty() ? ChargedProjectiles.EMPTY : ChargedProjectiles.of(rest));
        stack.set(AssaultCrossbow.STATE.get(), new AssaultCrossbow.State(now, 0));
        Projectile p = createProjectile(level, player, stack, bolt, false);
        if (p instanceof AbstractArrow a) {
            a.setSoundEvent(SoundEvents.CROSSBOW_HIT);
        }
        shootProjectile(player, p, 0, SPEED, SPREAD, 0.0F, null);
        level.addFreshEntity(p);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 0.8F,
                1.05F + level.random.nextFloat() * 0.25F);
        player.awardStat(Stats.ITEM_USED.get(this));
        if (rest.isEmpty()) {
            startReload(level, player, stack);
        }
    }

    @Override
    protected void shootProjectile(LivingEntity shooter, Projectile projectile, int index, float velocity, float inaccuracy, float angle,
                                   @Nullable LivingEntity target) {
        projectile.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot() + angle, 0.0F, velocity, inaccuracy);
    }

    /** The kind of arrow a reload takes: the loaded kind, else the first the player carries (an arrow in creative). */
    private static ItemStack kind(Player player, ItemStack weapon) {
        ItemStack loaded = ammo(weapon);
        if (loaded.isEmpty()) {
            ItemStack found = player.getProjectile(weapon);
            return found.isEmpty() ? ItemStack.EMPTY : found.copyWithCount(1);
        }
        ItemStack k = loaded.copyWithCount(1);
        k.remove(DataComponents.INTANGIBLE_PROJECTILE);
        if (player.hasInfiniteMaterials()) {
            return k;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (ItemStack.isSameItemSameComponents(player.getInventory().getItem(i), k)) {
                return k;
            }
        }
        return ItemStack.EMPTY;
    }

    private static void startReload(ServerLevel level, Player player, ItemStack stack) {
        if (rounds(stack) >= MAGAZINE) {
            return;
        }
        if (kind(player, stack).isEmpty()) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6F, 1.6F);
            return;
        }
        stack.set(AssaultCrossbow.STATE.get(), new AssaultCrossbow.State(state(stack).lastShot(), level.getGameTime() + RELOAD));
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CROSSBOW_LOADING_START.value(), SoundSource.PLAYERS, 1.0F, 0.9F);
    }

    /** Fills the magazine from the player's arrows of its kind (free in creative: those bolts can't be picked up). */
    private static void finishReload(ServerLevel level, Player player, ItemStack stack) {
        stack.set(AssaultCrossbow.STATE.get(), new AssaultCrossbow.State(state(stack).lastShot(), 0));
        ItemStack kind = kind(player, stack);
        if (kind.isEmpty()) {
            return;
        }
        int need = MAGAZINE - rounds(stack), took = 0;
        if (player.hasInfiniteMaterials()) {
            took = need;
            kind.set(DataComponents.INTANGIBLE_PROJECTILE, Unit.INSTANCE);
        } else {
            for (int i = 0; i < player.getInventory().getContainerSize() && took < need; i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (ItemStack.isSameItemSameComponents(s, kind)) {
                    int n = Math.min(need - took, s.getCount());
                    s.shrink(n);
                    took += n;
                }
            }
        }
        if (took > 0) {
            ItemStack loaded = ammo(stack);
            int rounds = (loaded.isEmpty() ? 0 : loaded.getCount()) + took;
            stack.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(kind.copyWithCount(rounds)));
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CROSSBOW_LOADING_END.value(), SoundSource.PLAYERS, 1.0F, 0.9F);
    }

    /** The reload runs while the crossbow is in hand; put away, it stops. */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!(level instanceof ServerLevel sl) || !(entity instanceof Player player)) {
            return;
        }
        AssaultCrossbow.State st = state(stack);
        if (st.reloadUntil() == 0) {
            return;
        }
        if (player.getMainHandItem() != stack && player.getOffhandItem() != stack) {
            stack.set(AssaultCrossbow.STATE.get(), new AssaultCrossbow.State(st.lastShot(), 0));
            return;
        }
        long now = sl.getGameTime();
        if (now == st.reloadUntil() - RELOAD / 2) {
            sl.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), SoundSource.PLAYERS, 1.0F, 0.9F);
        }
        if (now >= st.reloadUntil() || st.reloadUntil() - now > RELOAD) {
            finishReload(sl, player, stack);
        }
    }

    @Override
    public Predicate<ItemStack> getAllSupportedProjectiles() {
        return ARROW_ONLY;
    }

    @Override
    public int getDefaultProjectileRange() {
        return 8;
    }

    /** The bar under the icon is the magazine (blue while reloading). */
    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0F * rounds(stack) / MAGAZINE);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return state(stack).reloadUntil() > 0 ? 0x55AAFF : 0xF0B030;
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !newStack.is(oldStack.getItem());
    }

    @Override
    public boolean canContinueUsing(ItemStack oldStack, ItemStack newStack) {
        return ItemStack.isSameItem(oldStack, newStack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ItemStack ammo = ammo(stack);
        tooltip.add(Component.translatable("item.assaultcrossbow.assault_crossbow.magazine", ammo.getCount(), MAGAZINE,
                ammo.isEmpty() ? Component.translatable("item.minecraft.arrow") : ammo.getHoverName()).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.assaultcrossbow.assault_crossbow.help1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.assaultcrossbow.assault_crossbow.help2").withStyle(ChatFormatting.GRAY));
    }
}

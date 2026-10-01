package dev.hywmill.integration.astikor;

import dev.hywmill.core.HmLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * Optional AstikorCarts Redux (post-M5): supply carts for convoys, a horse hitched to each. No compile dependency: the cart
 * is found by its entity id and is a vanilla container; hitching calls its public {@code setPulling(Entity)} by reflection.
 * Without the mod, callers fall back to a chest minecart.
 */
public final class AstikorCarts {
    private AstikorCarts() {}

    public static final ResourceLocation SUPPLY_CART = ResourceLocation.fromNamespaceAndPath("astikorcartsredux", "supply_cart");

    @Nullable
    public static Entity supplyCart(ServerLevel level, Vec3 at) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(SUPPLY_CART).orElse(null);
        if (type == null) {
            return null;
        }
        Entity cart = type.create(level);
        if (cart == null) {
            return null;
        }
        cart.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360, 0);
        level.addFreshEntity(cart);
        return cart;
    }

    /** Hitches {@code puller} to the cart (no-op if the cart is not an Astikor cart). */
    public static void hitch(Entity cart, Entity puller) {
        for (Class<?> c = cart.getClass(); c != null && c != Entity.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("setPulling") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(puller.getClass())) {
                    try {
                        m.setAccessible(true);
                        m.invoke(cart, puller);
                    } catch (ReflectiveOperationException | RuntimeException ex) {
                        HmLog.warn("AstikorCarts: could not hitch a horse: {}", ex.toString());
                    }
                    return;
                }
            }
        }
    }
}

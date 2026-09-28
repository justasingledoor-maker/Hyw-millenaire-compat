package dev.hywmill.integration.hyw;

import dev.hywmill.core.HmLog;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import ydmsama.hundred_years_war.main.entity.entities.ArcherEntity;
import ydmsama.hundred_years_war.main.entity.entities.CrossbowmanEntity;

/**
 * Crash guard (post-M5 bug report): an HYW archer with an empty main hand crashes the server. Its per-tick target check
 * ({@code ArcherEntity.canLobAttackTarget}) builds a test arrow from the main-hand stack, and vanilla rejects an empty
 * weapon ("Invalid weapon firing an arrow"). HYW leaves the hand empty when an item of its equipment data is missing, and
 * other mods can clear it too. Any HYW archer or crossbowman (garrison, recruit or not) whose main hand is empty on joining
 * the level, or becomes empty, gets HYW's own default weapon back (a bow or a crossbow). Server side only; no mixin.
 */
public final class HywRangedWeaponGuard {
    private HywRangedWeaponGuard() {}

    public static void onJoin(EntityJoinLevelEvent e) {
        if (!e.getLevel().isClientSide()) {
            rearm(e.getEntity(), "joined the level");
        }
    }

    public static void onEquipmentChange(LivingEquipmentChangeEvent e) {
        if (e.getSlot() == EquipmentSlot.MAINHAND && e.getTo().isEmpty() && !e.getEntity().level().isClientSide()) {
            rearm(e.getEntity(), "main hand emptied");
        }
    }

    /** Gives an unarmed HYW ranged unit its default weapon; returns true if it did. */
    static boolean rearm(Entity entity, String why) {
        ItemStack weapon;
        if (entity instanceof ArcherEntity) {
            weapon = new ItemStack(Items.BOW);
        } else if (entity instanceof CrossbowmanEntity) {
            weapon = new ItemStack(Items.CROSSBOW);
        } else {
            return false;
        }
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity le) || !le.getMainHandItem().isEmpty()) {
            return false;
        }
        le.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        HmLog.infoThrottled("hyw.rearm", 10_000, "Re-armed unarmed HYW {} {} at {} ({}): an empty main hand crashes HYW's ranged target check",
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), entity.getUUID(),
                entity.blockPosition().toShortString(), why);
        return true;
    }
}

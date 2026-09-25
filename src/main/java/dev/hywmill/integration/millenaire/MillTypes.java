package dev.hywmill.integration.millenaire;

import dev.hywmill.military.classify.RoleClassifier;
import dev.hywmill.military.classify.RoleTables;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillagerType;
import org.millenaire.entity.MillVillager;

import javax.annotation.Nullable;

/** Small helpers over Millénaire's villager-type tags (helpInAttacks, raider, archer). */
final class MillTypes {
    private MillTypes() {}

    @Nullable
    static VillagerType typeOf(MillVillager v) {
        ResourceLocation id = v.getVillagerTypeId();
        return id == null ? null : ModCultures.getVillagerType(id);
    }

    /** Defender: Millénaire tag helpInAttacks, and not currently a raid clone. */
    static boolean isDefender(MillVillager v) {
        if (v.isRaiderEntity()) {
            return false;
        }
        VillagerType t = typeOf(v);
        return t != null && t.isHelpInAttacks();
    }

    /** Civilian: exactly the types Millénaire itself injects the hide goal into (neither helpInAttacks nor raider). */
    static boolean isCivilian(MillVillager v) {
        if (v.isRaiderEntity()) {
            return false;
        }
        VillagerType t = typeOf(v);
        return t != null && !t.isHelpInAttacks() && !t.isRaider();
    }

    /** Vanilla "last hurt by" memory window used for self-defense (same as LivingEntity's own 100 ticks). */
    static final int SELF_DEFENSE_TICKS = 100;

    /** The villager was damaged by {@code attacker} within the self-defense window. */
    static boolean selfDefense(MillVillager v, LivingEntity attacker) {
        return v.getLastHurtByMob() == attacker && v.tickCount - v.getLastHurtByMobTimestamp() < SELF_DEFENSE_TICKS;
    }

    /** The villager was damaged by any living attacker within the self-defense window. */
    static boolean recentlyHurt(MillVillager v) {
        LivingEntity by = v.getLastHurtByMob();
        return by != null && by.isAlive() && v.tickCount - v.getLastHurtByMobTimestamp() < SELF_DEFENSE_TICKS;
    }

    /** Role-table facts of this villager's type. */
    static RoleClassifier.VillagerFacts facts(MillVillager v) {
        VillagerType t = typeOf(v);
        String id = String.valueOf(v.getVillagerTypeId());
        return t == null ? new RoleClassifier.VillagerFacts(id, false, false, false)
                : new RoleClassifier.VillagerFacts(id, t.isHostile(), t.isChild(), t.isHelpInAttacks());
    }

    /** HywMill defender: a Millénaire defender whose role-table role is SOLDIER, LEADER or MILITIA. */
    static boolean isRoleDefender(MillVillager v) {
        return isDefender(v) && RoleClassifier.villager(facts(v), RoleTables.current()).isDefender();
    }

    static boolean isArcher(MillVillager v) {
        VillagerType t = typeOf(v);
        return t != null && t.isArcher();
    }
}

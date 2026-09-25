package dev.hywmill.integration.millenaire;

import net.minecraft.resources.ResourceLocation;
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

    static boolean isArcher(MillVillager v) {
        VillagerType t = typeOf(v);
        return t != null && t.isArcher();
    }
}

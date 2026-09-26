package dev.hywmill.integration.hyw;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Integration;
import dev.hywmill.core.Services;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import ydmsama.hundred_years_war.main.utils.RelationOwnerMarkedEntity;

/** Registers the HYW-backed {@link dev.hywmill.faction.CombatFactionService}. */
public final class HywIntegration implements Integration {
    @Override
    public String modId() {
        return "hundred_years_war";
    }

    @Override
    public void init(IEventBus modBus) {
        // Fail fast at load time if the identity-marker interface is not mixed into Entity.
        if (!RelationOwnerMarkedEntity.class.isAssignableFrom(Entity.class)) {
            throw new IllegalStateException("HYW RelationOwnerMarkedEntity is not implemented by Entity; HYW mixins missing?");
        }
        Services.registerFactions(new HywCombatFactionService());
        Services.registerUnits(new HywUnitProvider());
        Services.registerEquipment(new HywEquipmentProvider());
        Services.registerEquipment(new HywProfileEquipmentProvider());
        Services.putDiagnostic("hyw.identityMarker", "RelationOwnerMarkedEntity present on Entity");
        HmLog.info("HYW faction service registered (identity marker mixin verified on net.minecraft.world.entity.Entity).");
    }
}

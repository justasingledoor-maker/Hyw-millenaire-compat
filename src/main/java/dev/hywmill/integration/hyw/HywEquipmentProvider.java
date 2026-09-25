package dev.hywmill.integration.hyw;

import dev.hywmill.garrison.spi.EquipmentProvider;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;
import ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity;

/**
 * Equipment through HYW's own per-unit equipment files ({@code BaseCombatEntity.setEquipment(int)},
 * which loads the unit's equipment data, clamps the level with HYW's {@code normalizeTier} and
 * applies it). HYW itself chooses {@code equipment.json} or its Epic Knights variant; HywMill does
 * not know or care which.
 */
public final class HywEquipmentProvider implements EquipmentProvider {
    public static final String ID = "hyw";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public int resolveLevel(UnitSpec unit, MilitaryTier tier, int requestedLevel) {
        return Math.max(0, requestedLevel);
    }

    @Override
    public int apply(Object entity, UnitSpec unit, int level) {
        if (!(entity instanceof BaseCombatEntity unitEntity)) {
            return -1;
        }
        unitEntity.setEquipment(level);
        return unitEntity.getEquipmentLevel();
    }
}

package dev.hywmill.garrison.tables;

import dev.hywmill.military.MilitaryTier;

/**
 * One entry of the garrison unit catalogue ({@code hywmill_garrison/units.json}).
 *
 * @param key        table key, e.g. {@code spear_man}
 * @param entityType registered entity type id, e.g. {@code hundred_years_war:spear_man}
 * @param minTier    lowest village tier allowed to recruit it
 */
public record UnitSpec(String key, String entityType, UnitClass unitClass, double cost, MilitaryTier minTier, boolean enabled) {}

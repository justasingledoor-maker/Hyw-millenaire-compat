package dev.hywmill.military.classify;

/**
 * The role table currently loaded from datapacks. Replaced wholesale on every datapack
 * (re)load, so each server/world sees only its own packs.
 */
public final class RoleTables {
    private static volatile RoleTable current = RoleTable.EMPTY;

    private RoleTables() {}

    public static RoleTable current() {
        return current;
    }

    public static void set(RoleTable table) {
        current = table;
    }
}

package dev.hywmill.classify;

import java.util.Map;

/**
 * Pure classification rules (no Minecraft or Millénaire types; unit-tested).
 *
 * <p>Villagers: Millénaire's {@code hostile} tag → OUTLAW, decided separately from (and before)
 * the table, so bandits are never part of a village's defense. Otherwise, first match wins:
 * <ol>
 *   <li>explicit table entry (authoritative);</li>
 *   <li>special case: child → CIVILIAN;</li>
 *   <li>{@code helpInAttacks} → MILITIA;</li>
 *   <li>CIVILIAN.</li>
 * </ol>
 *
 * <p>Buildings, first match wins:
 * <ol>
 *   <li>explicit table entry (may be NONE to exclude);</li>
 *   <li>plan set flagged as a border post by Millénaire → BORDER_MARKER;</li>
 *   <li>role derived from Millénaire's wall types (see {@link #wallRole});</li>
 *   <li>no military role (null).</li>
 * </ol>
 * No tag or keyword heuristics are used for classification.
 */
public final class RoleClassifier {
    private RoleClassifier() {}

    public record VillagerFacts(String typeId, boolean hostile, boolean child, boolean helpInAttacks) {}

    public static VillagerRole villager(VillagerFacts f, RoleTable table) {
        if (f.hostile()) {
            return VillagerRole.OUTLAW;
        }
        VillagerRole explicit = table.villagers().get(f.typeId());
        if (explicit != null) {
            return explicit;
        }
        if (f.child()) {
            return VillagerRole.CIVILIAN;
        }
        return f.helpInAttacks() ? VillagerRole.MILITIA : VillagerRole.CIVILIAN;
    }

    /**
     * A type is ambiguous when it has no table entry but carries a Millénaire tag that suggests it
     * might be more than militia (chief, archer, defensive, defender). It is still classified by
     * the fallback; it is only reported so the table can be extended deliberately.
     */
    public static boolean isAmbiguous(VillagerFacts f, boolean suggestiveTag, RoleTable table) {
        return !f.hostile() && !f.child() && f.helpInAttacks() && suggestiveTag && !table.villagers().containsKey(f.typeId());
    }

    /**
     * @param wallDerived plan-set id → role derived from Millénaire's wall types
     * @return the role, or null if the building has no military role
     */
    public static BuildingRole building(String planSetId, boolean borderPost, Map<String, BuildingRole> wallDerived, RoleTable table) {
        BuildingRole explicit = table.buildings().get(planSetId);
        if (explicit != null) {
            return explicit == BuildingRole.NONE ? null : explicit;
        }
        if (borderPost) {
            return BuildingRole.BORDER_MARKER;
        }
        return wallDerived.get(planSetId);
    }

    /**
     * Wall-type derivation. A wall type that does not spawn wall segments (Millénaire's
     * "borderposts" types) is a line of markers: all its pieces are BORDER_MARKER. Otherwise the
     * tower piece is TOWER, the gateway piece GATE, and every other piece (wall, corner, caps,
     * slopes) WALL.
     */
    public enum WallPiece { WALL, TOWER, GATEWAY, CORNER, CAP, SLOPE }

    public static BuildingRole wallRole(boolean wallSpawn, WallPiece piece) {
        if (!wallSpawn) {
            return BuildingRole.BORDER_MARKER;
        }
        return switch (piece) {
            case TOWER -> BuildingRole.TOWER;
            case GATEWAY -> BuildingRole.GATE;
            default -> BuildingRole.WALL;
        };
    }
}

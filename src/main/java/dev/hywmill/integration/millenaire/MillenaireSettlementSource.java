package dev.hywmill.integration.millenaire;

import dev.hywmill.classify.BuildingRole;
import dev.hywmill.classify.RoleClassifier;
import dev.hywmill.classify.RoleTable;
import dev.hywmill.classify.RoleTables;
import dev.hywmill.classify.VillagerRole;
import dev.hywmill.settlement.ResidentInfo;
import dev.hywmill.settlement.SettlementSnapshot;
import dev.hywmill.settlement.SettlementSource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillagerType;
import org.millenaire.culture.WallType;
import org.millenaire.entity.MillVillager;
import org.millenaire.goal.GoalScheduler;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageManager;
import org.millenaire.village.VillageSavedData;
import org.millenaire.village.VillagerRecord;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Millénaire 9.0.2 calls used (all public, verified with javap):
 * VillageSavedData.get, VillageManager.getAllVillages/getVillage/findNearestVillage,
 * Village.getId/getVillageName/getCultureId/getVillageTypeId/getCenter/isActive/computeBounds/
 * getVillagerRecords/getVillagerRecord/getVillageDefendingStrength/getBuildings/
 * getOperationalBuildingsWithTag/getTownhall/getCombinedReputation,
 * VillagerRecord.isKilled/isRaidingVillage/getVillagerTypeId/getOriginalVillageId,
 * BuildingInstance.isOperational/isWallSegment/getPlanSetId, BuildingPlanSet.isBorderPost,
 * ModCultures.getBuildingPlanSet/getAllWallTypes, WallType plan-set accessors and wallSpawn,
 * VillagerType.isHostile/isChild/isHelpInAttacks/isDefensive/isArcher/hasTag,
 * MillVillager.getVillageId/getVillagerTypeId/isRaiderEntity, ModCultures.getVillagerType.
 */
final class MillenaireSettlementSource implements SettlementSource {
    @Override
    public String name() {
        return "Millénaire";
    }

    private static VillageManager manager(ServerLevel level) {
        return VillageSavedData.get(level).getVillageManager();
    }

    private static String nameOf(Village v) {
        String n = v.getVillageName();
        return n != null ? n : "";
    }

    @Override
    public List<SettlementRef> list(ServerLevel level) {
        List<SettlementRef> out = new ArrayList<>();
        for (Village v : manager(level).getAllVillages()) {
            out.add(new SettlementRef(v.getId().uuid(), nameOf(v), v.getCenter(), v.isActive()));
        }
        return out;
    }

    @Override
    public Optional<SettlementSnapshot> snapshot(ServerLevel level, UUID settlementId) {
        Village v = manager(level).getVillage(new VillageId(settlementId));
        if (v == null) {
            return Optional.empty();
        }
        RoleTable table = RoleTables.current();
        int population = 0, adults = 0, children = 0, garrison = 0;
        Map<VillagerRole, Integer> villagerRoles = new EnumMap<>(VillagerRole.class);
        Set<String> ambiguous = new TreeSet<>();
        for (VillagerRecord r : v.getVillagerRecords().values()) {
            if (r.isKilled() || r.isRaidingVillage()) {
                continue; // dead residents and foreign raid clones are not part of the garrison
            }
            population++;
            ResourceLocation typeId = r.getVillagerTypeId();
            VillagerType type = typeId != null ? ModCultures.getVillagerType(typeId) : null;
            if (type == null) {
                continue;
            }
            if (type.isChild()) {
                children++;
            } else {
                adults++;
            }
            if (type.isHelpInAttacks()) {
                garrison++;
            }
            RoleClassifier.VillagerFacts facts = new RoleClassifier.VillagerFacts(
                    typeId.toString(), type.isHostile(), type.isChild(), type.isHelpInAttacks());
            villagerRoles.merge(RoleClassifier.villager(facts, table), 1, Integer::sum);
            boolean suggestive = type.hasTag("chief") || type.hasTag("defender") || type.isDefensive() || type.isArcher();
            if (RoleClassifier.isAmbiguous(facts, suggestive, table)) {
                ambiguous.add(typeId.toString());
            }
        }

        Map<String, Integer> tagCounts = new LinkedHashMap<>();
        for (String tag : SettlementSnapshot.TRACKED_TAGS) {
            tagCounts.put(tag, v.getOperationalBuildingsWithTag(tag).size());
        }
        Map<String, BuildingRole> wallDerived = wallDerivedRoles();
        Map<BuildingRole, Integer> buildingRoles = new EnumMap<>(BuildingRole.class);
        int wallPending = 0, operational = 0;
        for (BuildingInstance b : v.getBuildings()) {
            if (!b.isOperational()) {
                if (b.isWallSegment()) {
                    wallPending++;
                }
                continue;
            }
            operational++;
            ResourceLocation planSetId = b.getPlanSetId();
            if (planSetId == null) {
                continue;
            }
            BuildingPlanSet planSet = ModCultures.getBuildingPlanSet(planSetId);
            BuildingRole role = RoleClassifier.building(planSetId.toString(), planSet != null && planSet.isBorderPost(), wallDerived, table);
            if (role != null) {
                buildingRoles.merge(role, 1, Integer::sum);
            }
        }
        BuildingInstance th = v.getTownhall();
        String thPlan = th != null && th.getPlanSetId() != null ? th.getPlanSetId().toString() : "";

        return Optional.of(new SettlementSnapshot(
                settlementId, nameOf(v), String.valueOf(v.getCultureId()), String.valueOf(v.getVillageTypeId()),
                v.getCenter(), v.computeBounds(), v.isActive(),
                population, adults, children, garrison, v.getVillageDefendingStrength(),
                villagerRoles, buildingRoles, tagCounts, wallPending, List.copyOf(ambiguous), thPlan,
                v.getBuildings().size(), operational));
    }

    /** Plan-set id → role, derived from every loaded Millénaire WallType (see RoleClassifier.wallRole). */
    static Map<String, BuildingRole> wallDerivedRoles() {
        Map<String, BuildingRole> out = new HashMap<>();
        for (WallType w : ModCultures.getAllWallTypes().values()) {
            boolean spawn = w.wallSpawn();
            put(out, w.wallPlanSet(), spawn, RoleClassifier.WallPiece.WALL);
            put(out, w.towerPlanSet(), spawn, RoleClassifier.WallPiece.TOWER);
            put(out, w.gatewayPlanSet(), spawn, RoleClassifier.WallPiece.GATEWAY);
            put(out, w.cornerPlanSet(), spawn, RoleClassifier.WallPiece.CORNER);
            for (ResourceLocation cap : new ResourceLocation[]{w.capLeftPlanSet(), w.capRightPlanSet(), w.capBothPlanSet()}) {
                put(out, cap, spawn, RoleClassifier.WallPiece.CAP);
            }
            for (ResourceLocation slope : new ResourceLocation[]{w.slope1LeftPlanSet(), w.slope1RightPlanSet(), w.slope2LeftPlanSet(),
                    w.slope2RightPlanSet(), w.slope3LeftPlanSet(), w.slope3RightPlanSet()}) {
                put(out, slope, spawn, RoleClassifier.WallPiece.SLOPE);
            }
        }
        return out;
    }

    private static void put(Map<String, BuildingRole> out, ResourceLocation planSet, boolean wallSpawn, RoleClassifier.WallPiece piece) {
        if (planSet != null) {
            out.put(planSet.toString(), RoleClassifier.wallRole(wallSpawn, piece));
        }
    }

    @Override
    public Optional<SettlementRef> nearest(ServerLevel level, BlockPos pos, double maxDistance) {
        Village v = manager(level).findNearestVillage(pos, maxDistance);
        return v == null ? Optional.empty()
                : Optional.of(new SettlementRef(v.getId().uuid(), nameOf(v), v.getCenter(), v.isActive()));
    }

    @Override
    public Optional<ResidentInfo> residentInfo(Entity entity) {
        if (!(entity instanceof MillVillager mv)) {
            return Optional.empty();
        }
        VillageId vid = mv.getVillageId();
        if (vid == null) {
            return Optional.empty();
        }
        ResourceLocation typeId = mv.getVillagerTypeId();
        String type = typeId != null ? typeId.toString() : "?";
        boolean raider = mv.isRaiderEntity();
        UUID origin = vid.uuid();
        if (raider) {
            origin = null;
            if (mv.level() instanceof ServerLevel sl) {
                Village target = manager(sl).getVillage(vid);
                VillagerRecord rec = target != null ? target.getVillagerRecord(mv.getUUID()) : null;
                if (rec != null && rec.getOriginalVillageId() != null) {
                    origin = rec.getOriginalVillageId().uuid();
                }
            }
        }
        return Optional.of(new ResidentInfo(vid.uuid(), raider, origin, MillTypes.isDefender(mv), type));
    }

    @Override
    public List<Entity> loadedResidents(ServerLevel level, UUID settlementId) {
        Village v = manager(level).getVillage(new VillageId(settlementId));
        if (v == null) {
            return List.of();
        }
        List<Entity> out = new ArrayList<>();
        for (UUID id : v.getVillagerRecords().keySet()) {
            Entity e = level.getEntity(id);
            if (e instanceof MillVillager && e.isAlive()) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public int playerReputation(ServerLevel level, UUID settlementId, UUID playerId) {
        Village v = manager(level).getVillage(new VillageId(settlementId));
        return v == null ? 0 : v.getCombinedReputation(level, playerId);
    }

    @Override
    public List<String> describeResidents(ServerLevel level, UUID settlementId) {
        List<String> lines = new ArrayList<>();
        for (Entity e : loadedResidents(level, settlementId)) {
            MillVillager mv = (MillVillager) e;
            String role = mv.isRaiderEntity() ? "RAIDER" : MillTypes.isDefender(mv) ? "DEFENDER" : MillTypes.isCivilian(mv) ? "CIVILIAN" : "OTHER";
            GoalScheduler gs = mv.getGoalScheduler();
            VillagerGoal goal = gs != null ? gs.getCurrentGoal() : null;
            String goalDesc = goal == null ? "none" : goal.id() + (goal instanceof BridgeDecorator ? "(bridged)" : "");
            String target = mv.getAttackTarget() == null ? "none" : mv.getAttackTarget().getType().toShortString();
            lines.add(e.getUUID().toString() + " " + mv.getVillagerTypeId() + " " + role
                    + " goal=" + goalDesc + " attackTarget=" + target
                    + " hp=" + (int) mv.getHealth() + " @" + mv.blockPosition().toShortString());
        }
        return lines;
    }
}

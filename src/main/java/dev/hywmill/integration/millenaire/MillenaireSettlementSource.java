package dev.hywmill.integration.millenaire;

import dev.hywmill.settlement.ResidentInfo;
import dev.hywmill.settlement.SettlementSnapshot;
import dev.hywmill.settlement.SettlementSource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.millenaire.building.BuildingInstance;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillagerType;
import org.millenaire.entity.MillVillager;
import org.millenaire.goal.GoalScheduler;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageManager;
import org.millenaire.village.VillageSavedData;
import org.millenaire.village.VillagerRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Millénaire 9.0.2 calls used (all public, verified with javap):
 * VillageSavedData.get, VillageManager.getAllVillages/getVillage/findNearestVillage,
 * Village.getId/getVillageName/getCultureId/getVillageTypeId/getCenter/isActive/computeBounds/
 * getVillagerRecords/getVillagerRecord/getVillageDefendingStrength/getBuildings/
 * getOperationalBuildingsWithTag/getTownhall/getCombinedReputation,
 * VillagerRecord.isKilled/isRaidingVillage/getVillagerTypeId/getOriginalVillageId,
 * BuildingInstance.isOperational/isWallSegment/getPlanSetId,
 * MillVillager.getVillageId/getVillagerTypeId/isRaiderEntity, ModCultures.getVillagerType.
 */
final class MillenaireSettlementSource implements SettlementSource {
    /** Keywords matched against building plan-set ids to list military buildings (diagnostic + tier input). */
    private static final List<String> MILITARY_PLAN_KEYWORDS = List.of(
            "fort", "guard", "watchtower", "barrack", "armoury", "armory", "garrison", "militar", "tower");

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
        int population = 0, adults = 0, children = 0, garrison = 0;
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
        }

        Map<String, Integer> tagCounts = new LinkedHashMap<>();
        for (String tag : SettlementSnapshot.TRACKED_TAGS) {
            tagCounts.put(tag, v.getOperationalBuildingsWithTag(tag).size());
        }
        List<BuildingInstance> patrol = v.getOperationalBuildingsWithTag("patrol");
        int wallSegments = 0, wallPending = 0, wallTowers = 0, defensive = 0, operational = 0;
        List<String> militaryPlans = new ArrayList<>();
        for (BuildingInstance b : v.getBuildings()) {
            if (!b.isOperational()) {
                if (b.isWallSegment()) {
                    wallPending++;
                }
                continue;
            }
            operational++;
            boolean isPatrol = patrol.contains(b);
            if (b.isWallSegment()) {
                wallSegments++;
                if (isPatrol) {
                    wallTowers++;
                }
            } else if (isPatrol) {
                defensive++;
            }
            ResourceLocation planSet = b.getPlanSetId();
            if (planSet != null && !b.isWallSegment()) {
                String p = planSet.getPath().toLowerCase(Locale.ROOT);
                for (String k : MILITARY_PLAN_KEYWORDS) {
                    if (p.contains(k)) {
                        militaryPlans.add(planSet.toString());
                        break;
                    }
                }
            }
        }
        BuildingInstance th = v.getTownhall();
        String thPlan = th != null && th.getPlanSetId() != null ? th.getPlanSetId().toString() : "";
        boolean fortTownhall = thPlan.toLowerCase(Locale.ROOT).contains("fort");

        return Optional.of(new SettlementSnapshot(
                settlementId, nameOf(v), String.valueOf(v.getCultureId()), String.valueOf(v.getVillageTypeId()),
                v.getCenter(), v.computeBounds(), v.isActive(),
                population, adults, children, garrison, v.getVillageDefendingStrength(),
                tagCounts, wallSegments, wallPending, wallTowers, defensive, militaryPlans, thPlan, fortTownhall,
                v.getBuildings().size(), operational));
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
            lines.add(e.getUUID().toString().substring(0, 8) + " " + mv.getVillagerTypeId() + " " + role
                    + " goal=" + goalDesc + " attackTarget=" + target
                    + " hp=" + (int) mv.getHealth() + " @" + mv.blockPosition().toShortString());
        }
        return lines;
    }
}

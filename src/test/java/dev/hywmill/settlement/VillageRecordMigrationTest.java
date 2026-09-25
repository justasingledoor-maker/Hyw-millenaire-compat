package dev.hywmill.settlement;

import dev.hywmill.military.classify.BuildingRole;
import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.doctrine.DoctrineField;
import dev.hywmill.military.doctrine.MilitiaPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageRecordMigrationTest {
    private static final UUID VILLAGE = UUID.fromString("f7edd961-3e4b-4b15-8583-ecbdac270e4a");
    private static final UUID FACTION = UUID.fromString("1aa3e6cf-84d4-3b7b-8e61-f06352a032d8");

    /** A record as M1 (format 1) wrote it for Douvres la-forge. */
    private static CompoundTag format1() {
        CompoundTag t = new CompoundTag();
        t.putUUID("village", VILLAGE);
        t.putUUID("faction", FACTION);
        t.putString("name", "Douvres la-forge");
        t.putString("culture", "millenaire:norman");
        t.putString("type", "millenaire:norman/artisans");
        t.putLong("center", new BlockPos(100, 64, -200).asLong());
        t.putString("tier", "GUARD_POST");
        t.putInt("garrison", 12);
        t.putInt("fortification", 12);
        t.putInt("wallSegments", 0);
        t.putInt("wallTowers", 0);
        t.putInt("defensiveBuildings", 4);
        ListTag plans = new ListTag();
        plans.add(StringTag.valueOf("millenaire:norman/guildhouse"));
        t.put("militaryPlans", plans);
        t.putLong("firstSeenTick", 1000);
        t.putInt("updateCount", 42);
        return t;
    }

    @Test
    void format1IsFlaggedForRecomputeAndKeepsIdentity() {
        VillageRecord r = VillageRecord.load(format1(), 1);
        assertTrue(r.needsRecompute);
        assertEquals(VILLAGE, r.villageId);
        assertEquals(FACTION, r.factionId);
        assertEquals("Douvres la-forge", r.name);
        assertEquals(1000, r.firstSeenTick);
        assertEquals(42, r.updateCount);
        assertTrue(r.buildingRoles.isEmpty());
    }

    @Test
    void format2IsMigratedToFormat3() {
        VillageRecord v2 = VillageRecord.load(format1(), 1);
        v2.buildingRoles.put(BuildingRole.BORDER_MARKER, 13);
        v2.villagerRoles.put(VillagerRole.MILITIA, 12);
        CompoundTag t = v2.save();
        for (String k : new String[]{"capacity", "readiness", "equipmentScore", "villageRadius", "loneBuilding", "doctrineOverride", "stats"}) {
            t.remove(k); // what a format-2 build wrote
        }
        VillageRecord r = VillageRecord.load(t, 2);
        assertTrue(r.needsRecompute, "profile fields are recomputed");
        assertEquals(13, r.buildingRoles.get(BuildingRole.BORDER_MARKER), "format-2 role counts are kept");
        assertEquals(12, r.villagerRoles.get(VillagerRole.MILITIA));
        assertEquals(FACTION, r.factionId);
        assertTrue(r.doctrineOverride.isEmpty());
        assertEquals(0, r.stats.alerts);
        assertEquals(-1, r.equipmentScore);
    }

    @Test
    void format3KeepsOverrideStatsAndControllerSeparateFromFaction() {
        VillageRecord r = VillageRecord.load(format1(), 1);
        UUID controller = UUID.fromString("11111111-2222-4333-8444-555555555555");
        r.controllerPlayerId = controller;
        r.capacity = 7;
        r.equipmentScore = 5.5;
        r.doctrineOverride = r.doctrineOverride.with(DoctrineField.RESERVE, 3).with(DoctrineField.MILITIA_POLICY, MilitiaPolicy.NEVER);
        r.stats.hywKills = 4;
        r.stats.lastEngagedTick = 1234;
        VillageRecord back = VillageRecord.load(r.save(), VillageRecord.FORMAT);
        assertEquals(controller, back.controllerPlayerId);
        assertEquals(FACTION, back.factionId, "the controller never replaces the faction identity");
        assertEquals(7, back.capacity);
        assertEquals(5.5, back.equipmentScore);
        assertEquals(3, back.doctrineOverride.values().get(DoctrineField.RESERVE));
        assertEquals(MilitiaPolicy.NEVER, back.doctrineOverride.values().get(DoctrineField.MILITIA_POLICY));
        assertEquals(4, back.stats.hywKills);
        assertEquals(1234, back.stats.lastEngagedTick);
        assertFalse(back.needsRecompute);
    }

    @Test
    void format2RoundTrips() {
        VillageRecord r = VillageRecord.load(format1(), 1);
        r.tier = MilitaryTier.WATCH;
        r.fortification = 0;
        r.buildingRoles.put(BuildingRole.BORDER_MARKER, 13);
        r.villagerRoles.put(VillagerRole.MILITIA, 12);
        r.ambiguousTypes.add("millenaire:norman/seneschal");
        CompoundTag saved = r.save();
        assertFalse(saved.contains("wallTowers"), "format-1 fields are not written back");
        VillageRecord back = VillageRecord.load(saved, VillageRecord.FORMAT);
        assertFalse(back.needsRecompute);
        assertEquals(MilitaryTier.WATCH, back.tier);
        assertEquals(13, back.buildingRoles.get(BuildingRole.BORDER_MARKER));
        assertEquals(12, back.villagerRoles.get(VillagerRole.MILITIA));
        assertEquals("millenaire:norman/seneschal", back.ambiguousTypes.get(0));
        assertEquals(FACTION, back.factionId);
    }
}

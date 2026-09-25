package dev.hywmill.settlement;

import dev.hywmill.military.classify.BuildingRole;
import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.military.MilitaryTier;
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

package dev.hywmill.settlement;

import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.LossReason;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.military.classify.VillagerRole;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Ledger format 3 (M2) -> 4 (M3: persistent HYW garrison roster). */
class GarrisonMigrationTest {
    static final UUID VILLAGE = UUID.fromString("f7edd961-3e4b-4b15-8583-ecbdac270e4a");
    static final UUID FACTION = UUID.fromString("1aa3e6cf-84d4-3b7b-8e61-f06352a032d8");
    static final UUID CONTROLLER = UUID.fromString("11111111-2222-4333-8444-555555555555");

    static VillageRecord m2Record() {
        VillageRecord r = new VillageRecord(VILLAGE, FACTION);
        r.name = "Douvres la-forge";
        r.culture = "millenaire:norman";
        r.center = new BlockPos(100, 64, -200);
        r.garrison = 12;
        r.capacity = 9;
        r.updateCount = 40;
        r.firstSeenTick = 1000;
        r.controllerPlayerId = CONTROLLER;
        r.villagerRoles.put(VillagerRole.SOLDIER, 3);
        r.stats.alerts = 5;
        return r;
    }

    /** What an M2 build (format 3) wrote. */
    static CompoundTag format3() {
        CompoundTag t = m2Record().save();
        t.remove("hywRoster");
        return t;
    }

    @Test
    void format3LoadsWithoutARosterAndKeepsIdentityAndHistory() {
        VillageRecord r = VillageRecord.load(format3(), 3);
        assertNull(r.hywRoster, "roster is created at the first garrison slot");
        assertFalse(r.needsRecompute, "3 -> 4 keeps the M2 profile");
        assertEquals(VILLAGE, r.villageId);
        assertEquals(FACTION, r.factionId);
        assertEquals(CONTROLLER, r.controllerPlayerId);
        assertEquals(12, r.garrison, "the M1 Millénaire soldier count keeps its meaning");
        assertEquals(9, r.capacity);
        assertEquals(5, r.stats.alerts);
        assertEquals(1000, r.firstSeenTick);
    }

    @Test
    void migratedRosterStartsEmptyAtTheCurrentTick() {
        VillageRecord r = VillageRecord.load(format3(), 3);
        GarrisonRoster g = dev.hywmill.garrison.service.GarrisonService.roster(r, 777_000);
        assertFalse(g.startingGranted);
        assertEquals(0, g.levyPoints);
        assertEquals(777_000, g.lastAccrualTick);
        assertTrue(g.entries().isEmpty());
    }

    @Test
    void format4RoundTripsTheRoster() {
        VillageRecord r = m2Record();
        GarrisonRoster g = new GarrisonRoster(100);
        g.startingGranted = true;
        RosterEntry e = g.recruit(VILLAGE, "spear_man", "hundred_years_war:spear_man", 2, 100, false);
        g.beginSpawn(VILLAGE, e, 110);
        g.spawned(e, 2, 110);
        RosterEntry d = g.recruit(VILLAGE, "archer", "hundred_years_war:archer", 2, 120, true);
        d.transition(UnitState.LOST, 130, LossReason.REMOVED);
        r.hywRoster = g;
        assertEquals(4, VillageRecord.FORMAT);
        VillageRecord back = VillageRecord.load(r.save(), 4);
        assertNotNull(back.hywRoster);
        assertEquals(g.save(), back.hywRoster.save());
        assertEquals(e.entityUuid, back.hywRoster.entries().get(0).entityUuid);
        assertEquals(FACTION, back.factionId);
        assertEquals(12, back.garrison);
    }

    @Test
    void anM2ReaderIgnoresTheRoster() {
        // An M2 build reads a format-4 record with its format-3 code path: every M2 field must be
        // where it was, and the extra hywRoster compound is simply not read.
        VillageRecord r = m2Record();
        r.hywRoster = new GarrisonRoster(5);
        r.hywRoster.recruit(VILLAGE, "militia", "hundred_years_war:militia", 0, 5, false);
        CompoundTag t4 = r.save();
        CompoundTag t3 = format3();
        for (String k : t3.getAllKeys()) {
            assertEquals(t3.get(k), t4.get(k), "field " + k + " unchanged in format 4");
        }
        assertEquals(t3.getAllKeys().size() + 1, t4.getAllKeys().size());
        VillageRecord asM2 = VillageRecord.load(t4, 3);
        assertNull(asM2.hywRoster);
        assertEquals(9, asM2.capacity);
    }

    @Test
    void unknownStateInSavedRosterDegradesToMissingNotAWildcard() {
        CompoundTag g = new GarrisonRoster(0).save();
        VillageRecord r = m2Record();
        r.hywRoster = GarrisonRoster.load(g, 0);
        RosterEntry e = r.hywRoster.recruit(VILLAGE, "militia", "hundred_years_war:militia", 0, 0, true);
        r.hywRoster.beginSpawn(VILLAGE, e, 1);
        CompoundTag t = r.hywRoster.save();
        t.getList("entries", 10).getCompound(0).putString("state", "FUTURE_STATE");
        GarrisonRoster back = GarrisonRoster.load(t, 0);
        assertEquals(UnitState.MISSING, back.entries().get(0).state());
    }
}

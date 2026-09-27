package dev.hywmill.settlement;

import dev.hywmill.faction.FactionIds;
import dev.hywmill.politics.EnvoyKind;
import dev.hywmill.politics.EnvoyMission;
import dev.hywmill.politics.FavorSource;
import dev.hywmill.politics.GrievanceEvent;
import dev.hywmill.politics.GrievanceKind;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.Standing;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ledger format 5: politics round-trips; format-4 records load with empty politics; defaults are not written. */
class PoliticsPersistenceTest {
    static final UUID VILLAGE = UUID.fromString("f7edd961-3e4b-4b15-8583-ecbdac270e4a");

    @Test
    void roundTrip() {
        VillageRecord r = new VillageRecord(VILLAGE, FactionIds.forVillage(VILLAGE));
        UUID p = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        PoliticsRecord pr = r.politics.get(p);
        pr.grievances.add(new GrievanceEvent(GrievanceKind.KILL_RESIDENT, 100, true, true, false, 5, 64, -7), PoliticsTables.DEFAULTS.grievance());
        pr.refresh(100, 0, PoliticsTables.DEFAULTS);
        pr.favor.earn(FavorSource.DEFENSE, PoliticsTables.DEFAULTS.favor());
        pr.lastRequestTick = 90;
        pr.casualtiesOnErrands = 2;
        pr.lastProposal.put(target, 80L);
        r.politics.get(UUID.randomUUID()); // default: not written
        r.politics.setTruce(target, 5000);
        VillageRecord back = VillageRecord.load(r.save(), VillageRecord.FORMAT);
        assertEquals(1, back.politics.players().size());
        PoliticsRecord b = back.politics.peek(p);
        assertEquals(Standing.OUTLAW, b.status);
        assertEquals(100, b.statusSince);
        assertEquals(150, b.grievances.value(), 1e-9);
        assertTrue(b.grievances.peacetimeKillPending());
        assertEquals(GrievanceKind.KILL_RESIDENT, b.grievances.lastKind());
        assertEquals(-7, b.grievances.lastZ());
        assertEquals(3, b.favor.points());
        assertEquals(90, b.lastRequestTick);
        assertEquals(2, b.casualtiesOnErrands);
        assertEquals(80L, b.lastProposal.get(target));
        assertTrue(back.politics.truceWith(target, 4999));
    }

    @Test
    void emptyPoliticsAddsNoKeyAndFormat4LoadsEmpty() {
        VillageRecord r = new VillageRecord(VILLAGE, FactionIds.forVillage(VILLAGE));
        r.politics.get(UUID.randomUUID());
        CompoundTag t = r.save();
        assertFalse(t.contains("politics"), "a default-only politics map is not written");
        r.politics.get(UUID.randomUUID()).favor.earn(FavorSource.LONG_STANDING, PoliticsTables.DEFAULTS.favor());
        CompoundTag t5 = r.save();
        assertTrue(t5.contains("politics"));
        VillageRecord asFormat4 = VillageRecord.load(t5, 4);
        assertTrue(asFormat4.politics.isEmpty(), "a format-4 reader path ignores the key");
    }

    @Test
    void envoysRoundTrip() {
        EnvoyMission m = new EnvoyMission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                EnvoyKind.TRUCE, 10, 2410, 42L);
        assertEquals(List.of(m), PoliticsNbt.loadEnvoys(PoliticsNbt.saveEnvoys(List.of(m))));
    }
}

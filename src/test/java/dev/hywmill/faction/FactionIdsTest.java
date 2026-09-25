package dev.hywmill.faction;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The faction UUID derivation is a persistent contract (worlds store it in HYW markers and our ledger). */
class FactionIdsTest {
    @Test
    void derivationIsFrozen() {
        // Values observed on the M1 dedicated-server test world ("Douvres la-forge").
        UUID village = UUID.fromString("f7edd961-3e4b-4b15-8583-ecbdac270e4a");
        assertEquals(UUID.fromString("1aa3e6cf-84d4-3b7b-8e61-f06352a032d8"), FactionIds.forVillage(village));
    }

    @Test
    void deterministicAndRegistered() {
        UUID village = UUID.randomUUID();
        UUID a = FactionIds.forVillage(village);
        assertEquals(a, FactionIds.forVillage(village));
        assertTrue(FactionIds.isVillageFaction(a));
        assertEquals(village, FactionIds.villageOf(a));
        assertFalse(FactionIds.isVillageFaction(village));
        assertFalse(FactionIds.isVillageFaction(null));
    }
}

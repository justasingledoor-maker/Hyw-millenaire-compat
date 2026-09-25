package dev.hywmill.faction;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactionRegistryTest {
    @Test
    void registerIsDeterministicAndReversible() {
        FactionRegistry reg = new FactionRegistry();
        UUID village = UUID.randomUUID();
        UUID a = reg.register(village);
        assertEquals(a, reg.register(village));
        assertEquals(FactionIds.forVillage(village), a);
        assertTrue(reg.isVillageFaction(a));
        assertEquals(village, reg.villageOf(a));
        assertFalse(reg.isVillageFaction(village));
        assertFalse(reg.isVillageFaction(null));
    }

    @Test
    void registriesAreIndependentPerServer() {
        UUID village = UUID.randomUUID();
        FactionRegistry first = new FactionRegistry();
        UUID f = first.register(village);
        FactionRegistry second = new FactionRegistry();
        assertFalse(second.isVillageFaction(f));
        assertNull(second.villageOf(f));
    }
}

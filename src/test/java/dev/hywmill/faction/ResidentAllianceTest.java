package dev.hywmill.faction;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** M5 Option 1: resident <-> faction FRIENDLY is written per direction, only when missing. */
class ResidentAllianceTest {
    /** A relation store with HYW's directed semantics (missing = NEUTRAL). */
    static CombatFactionService fake(Map<List<UUID>, String> store) {
        return (CombatFactionService) Proxy.newProxyInstance(CombatFactionService.class.getClassLoader(),
                new Class<?>[]{CombatFactionService.class}, (p, m, a) -> switch (m.getName()) {
                    case "relation" -> store.getOrDefault(List.of((UUID) a[0], (UUID) a[1]), "NEUTRAL");
                    case "setRelation" -> {
                        store.put(List.of((UUID) a[0], (UUID) a[1]), (String) a[2]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void writesBothDirectionsOnceThenNothing() {
        Map<List<UUID>, String> store = new HashMap<>();
        UUID village = UUID.randomUUID();
        CombatFactionService f = fake(store);
        assertEquals(2, ResidentAlliance.ensure(f, village));
        UUID fac = FactionIds.forVillage(village);
        UUID res = FactionIds.residentsOf(village);
        assertEquals("FRIENDLY", store.get(List.of(res, fac)));
        assertEquals("FRIENDLY", store.get(List.of(fac, res)));
        assertEquals(0, ResidentAlliance.ensure(f, village));
    }

    @Test
    void repairsAOneWayOrHostileState() {
        Map<List<UUID>, String> store = new HashMap<>();
        UUID village = UUID.randomUUID();
        UUID fac = FactionIds.forVillage(village);
        UUID res = FactionIds.residentsOf(village);
        store.put(List.of(res, fac), "FRIENDLY");
        store.put(List.of(fac, res), "HOSTILE");
        assertEquals(1, ResidentAlliance.ensure(fake(store), village));
        assertEquals("FRIENDLY", store.get(List.of(fac, res)));
    }
}

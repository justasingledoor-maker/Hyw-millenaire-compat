package dev.hywmill.politics.war;

import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.military.doctrine.MilitiaPolicy;
import dev.hywmill.settlement.PoliticsNbt;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** M5-5b: war states, the relation plan (symmetry, expiry, restoration input) and the rules of engagement. */
class WarTest {
    static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    static final UUID FA = UUID.fromString("00000000-0000-4000-8000-0000000000fa");
    static final UUID FB = UUID.fromString("00000000-0000-4000-8000-0000000000fb");
    static final UUID P = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final long MIN = 1000, PEACE = 500;

    static UUID faction(UUID v) {
        return v.equals(A) ? FA : v.equals(B) ? FB : null;
    }

    @Test
    void warStartsAfterSustainedConflictNotOnDrift() {
        WarRecord w = new WarRecord(B, A);
        assertEquals(A, w.a, "canonical order");
        assertEquals(WarRecord.Change.NONE, w.update(0, true, false, false, true, MIN, PEACE));
        assertEquals(WarRecord.Change.NONE, w.update(400, false, false, false, true, MIN, PEACE), "nightly drift above -90 resets");
        assertEquals(WarRecord.Change.NONE, w.update(500, true, false, false, true, MIN, PEACE));
        assertEquals(WarRecord.Change.NONE, w.update(1499, true, false, false, true, MIN, PEACE));
        assertEquals(WarRecord.Change.STARTED, w.update(1500, true, false, false, true, MIN, PEACE));
        assertTrue(w.atWar());
    }

    @Test
    void aRaidStartsAWarAtOnce() {
        WarRecord w = new WarRecord(A, B);
        assertEquals(WarRecord.Change.STARTED, w.update(0, false, true, false, true, MIN, PEACE));
    }

    @Test
    void warEndsAtTruceSwitchOffOrLastingPeace() {
        WarRecord w = new WarRecord(A, B);
        w.update(0, false, true, false, true, MIN, PEACE);
        assertEquals(WarRecord.Change.ENDED, w.update(10, true, false, true, true, MIN, PEACE), "truce");
        w.update(20, false, true, false, true, MIN, PEACE);
        assertEquals(WarRecord.Change.ENDED, w.update(30, true, false, false, false, MIN, PEACE), "autoWar off");
        assertEquals(WarRecord.Change.NONE, w.update(40, true, true, false, false, MIN, PEACE), "never starts while off");
        w.update(50, false, true, false, true, MIN, PEACE);
        assertEquals(WarRecord.Change.NONE, w.update(100, false, false, false, true, MIN, PEACE));
        assertEquals(WarRecord.Change.NONE, w.update(300, true, false, false, true, MIN, PEACE), "conflict again resets the calm");
        assertEquals(WarRecord.Change.NONE, w.update(400, false, false, false, true, MIN, PEACE));
        assertEquals(WarRecord.Change.ENDED, w.update(900, false, false, false, true, MIN, PEACE));
        assertTrue(w.idle());
    }

    @Test
    void planIsSymmetricFactionOnlyAndHostileWins() {
        WarRecord w = new WarRecord(A, B);
        w.update(0, false, true, false, true, MIN, PEACE);
        Campaign c = new Campaign(P, A, B, 0, 100);
        Map<RelationPlan.Edge, String> plan = RelationPlan.desired(List.of(w), List.of(c), 50, WarTest::faction);
        assertEquals("HOSTILE", plan.get(new RelationPlan.Edge(FA, FB)));
        assertEquals("HOSTILE", plan.get(new RelationPlan.Edge(FB, FA)));
        assertEquals("HOSTILE", plan.get(new RelationPlan.Edge(P, FB)));
        assertEquals("HOSTILE", plan.get(new RelationPlan.Edge(FB, P)));
        assertEquals("FRIENDLY", plan.get(new RelationPlan.Edge(P, FA)));
        assertEquals("FRIENDLY", plan.get(new RelationPlan.Edge(FA, P)));
        assertEquals(6, plan.size(), "villages themselves (and resident identities) never appear");
        assertFalse(plan.containsKey(new RelationPlan.Edge(A, B)));
        // a campaign both for and against the same faction: HOSTILE wins
        Map<RelationPlan.Edge, String> odd = RelationPlan.desired(List.of(), List.of(new Campaign(P, A, A, 0, 100)), 50, WarTest::faction);
        assertEquals("HOSTILE", odd.get(new RelationPlan.Edge(P, FA)));
        // expiry: an expired campaign and a war that ended project nothing (the projector restores what it recorded)
        assertTrue(RelationPlan.desired(List.of(), List.of(c), 100, WarTest::faction).isEmpty());
        w.update(60, false, false, true, true, MIN, PEACE);
        assertTrue(RelationPlan.desired(List.of(w), List.of(), 70, WarTest::faction).isEmpty());
        assertTrue(RelationPlan.wantsHostile(plan, FB, FA));
        assertFalse(RelationPlan.wantsHostile(plan, P, FA));
    }

    @Test
    void combatantsOnly() {
        assertTrue(RoeState.combatant(VillagerRole.SOLDIER, MilitiaPolicy.NEVER));
        assertTrue(RoeState.combatant(VillagerRole.LEADER, MilitiaPolicy.NEVER));
        assertTrue(RoeState.combatant(VillagerRole.MILITIA, MilitiaPolicy.WHEN_ATTACKED));
        assertFalse(RoeState.combatant(VillagerRole.MILITIA, MilitiaPolicy.NEVER));
        for (MilitiaPolicy p : MilitiaPolicy.values()) {
            assertFalse(RoeState.combatant(VillagerRole.CIVILIAN, p), "civilians are never targets");
        }
    }

    @Test
    void warStatePersists() {
        Map<String, WarRecord> wars = new LinkedHashMap<>();
        WarRecord w = new WarRecord(A, B);
        w.update(5, false, true, false, true, MIN, PEACE);
        wars.put(w.key(), w);
        WarRecord idle = new WarRecord(A, P);
        wars.put(idle.key(), idle);
        List<Campaign> camps = List.of(new Campaign(P, A, B, 1, 2));
        Map<String, String> proj = Map.of(FA + ">" + FB, "NEUTRAL");
        CompoundTag root = new CompoundTag();
        PoliticsNbt.saveWar(root, wars, camps, proj);
        Map<String, WarRecord> w2 = new LinkedHashMap<>();
        List<Campaign> c2 = new ArrayList<>();
        Map<String, String> p2 = new LinkedHashMap<>();
        PoliticsNbt.loadWar(root, w2, c2, p2);
        assertEquals(1, w2.size(), "idle pairs are not written");
        assertEquals(5, w2.get(w.key()).warSince);
        assertEquals(camps, c2);
        assertEquals(proj, p2);
    }

    @Test
    void warAndCampaignMakeTheEnemysCiviliansFairGame() {
        UUID ra = new UUID(9, 1), rb = new UUID(9, 2);
        WarRecord w = new WarRecord(A, B);
        w.warSince = 0;
        Campaign c = new Campaign(P, A, B, 0, 100);
        Map<RelationPlan.Edge, String> plan = RelationPlan.desired(List.of(w), List.of(c), 50, WarTest::faction,
                v -> v.equals(A) ? ra : v.equals(B) ? rb : null);
        assertEquals("HOSTILE", plan.get(new RelationPlan.Edge(FA, rb)), "A's soldiers fight B's villagers");
        assertEquals("HOSTILE", plan.get(new RelationPlan.Edge(FB, ra)), "and B's soldiers A's");
        assertEquals("HOSTILE", plan.get(new RelationPlan.Edge(P, rb)), "the campaigning player (and their troops) fight the enemy's villagers");
        assertEquals("HOSTILE", plan.get(new RelationPlan.Edge(rb, P)));
        assertNull(plan.get(new RelationPlan.Edge(P, ra)), "never the ally's villagers");
        assertNull(plan.get(new RelationPlan.Edge(ra, rb)), "villagers are not set against villagers");
    }
}

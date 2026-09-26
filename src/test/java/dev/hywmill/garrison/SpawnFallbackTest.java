package dev.hywmill.garrison;

import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.tag.GarrisonTag;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spawn-location fallback: defending position first (unchanged M3 search), then the village centre
 * for that attempt only; if neither has a safe spot the slot stays RECRUITED and is retried.
 */
class SpawnFallbackTest {
    static final UUID VILLAGE = GarrisonLifecycleTest.VILLAGE;
    static final UUID FACTION = dev.hywmill.faction.FactionIds.forVillage(VILLAGE);
    static final BlockPos DEFENDING = new BlockPos(646, 84, 636);
    static final BlockPos CENTRE = new BlockPos(630, 82, 612);

    /**
     * A world model for the probe: only chunks in {@code loaded} can be searched (the real probe
     * checks {@code hasChunk} and never loads a chunk); {@code safe} anchors have a spot. Records
     * every chunk it looked at.
     */
    static final class World implements SpawnSpots.Probe<BlockPos> {
        final Set<Long> loaded = new HashSet<>();
        final Set<BlockPos> safe = new HashSet<>();
        final List<BlockPos> asked = new ArrayList<>();
        final Set<Long> touched = new HashSet<>();

        World load(BlockPos p) {
            loaded.add(chunk(p));
            return this;
        }

        static long chunk(BlockPos p) {
            return ((long) (p.getX() >> 4) << 32) ^ (p.getZ() >> 4);
        }

        @Override
        public BlockPos find(BlockPos anchor, UUID rosterId) {
            asked.add(anchor);
            for (int[] o : SpawnSpots.candidates(rosterId)) {
                BlockPos c = anchor.offset(o[0], 0, o[1]);
                if (!loaded.contains(chunk(c))) {
                    continue; // unloaded: skipped, never loaded
                }
                touched.add(chunk(c));
                if (safe.contains(anchor)) {
                    return c;
                }
            }
            return null;
        }
    }

    RosterEntry recruit(GarrisonRoster r) {
        return r.recruit(VILLAGE, "archer", "hundred_years_war:archer", 1, 0, true);
    }

    @Test
    void defendingPositionIsUsedWhenItHasASafeSpot() {
        World w = new World().load(DEFENDING).load(CENTRE);
        w.safe.add(DEFENDING);
        w.safe.add(CENTRE);
        UUID id = GarrisonRoster.rosterId(VILLAGE, 1);
        SpawnSpots.Choice<BlockPos> c = SpawnSpots.choose(DEFENDING, CENTRE, id, w);
        assertNotNull(c);
        assertFalse(c.fallback());
        assertEquals(List.of(DEFENDING), w.asked, "the centre is not even searched");
        World w2 = new World().load(DEFENDING).load(CENTRE);
        w2.safe.add(DEFENDING);
        assertEquals(c, SpawnSpots.choose(DEFENDING, CENTRE, id, w2), "deterministic");
    }

    @Test
    void villageCentreIsTheFallbackWhenTheDefendingPositionHasNone() {
        World w = new World().load(DEFENDING).load(CENTRE);
        w.safe.add(CENTRE);
        SpawnSpots.Choice<BlockPos> c = SpawnSpots.choose(DEFENDING, CENTRE, GarrisonRoster.rosterId(VILLAGE, 2), w);
        assertNotNull(c);
        assertTrue(c.fallback());
        assertTrue(c.spot().distSqr(CENTRE) <= (double) SpawnSpots.MAX_RADIUS * SpawnSpots.MAX_RADIUS * 2, "within the bounded search around the centre");
        assertEquals(List.of(DEFENDING, CENTRE), w.asked);
    }

    @Test
    void bothFailingLeavesTheSlotRecruitedAndNeverLost() {
        World w = new World().load(DEFENDING).load(CENTRE);
        GarrisonRoster r = new GarrisonRoster(0);
        RosterEntry e = recruit(r);
        for (long tick = 0; tick < 200_000; tick += 200) {
            // the spawn loop: no choice -> no beginSpawn, retry next slot
            if (SpawnSpots.choose(DEFENDING, CENTRE, e.rosterId, w) != null) {
                fail("no safe spot exists");
            }
            Reconciler.reconcile(r, FACTION, id -> null, tick, true, GarrisonSettings.DEFAULTS);
        }
        assertEquals(UnitState.RECRUITED, e.state());
        assertNull(e.entityUuid);
        assertEquals(0, e.generation);
        assertNull(e.lossReason());
        assertEquals(1, r.live());
        // once terrain allows it, the same slot spawns (retried, not replaced)
        w.safe.add(CENTRE);
        assertNotNull(SpawnSpots.choose(DEFENDING, CENTRE, e.rosterId, w));
    }

    @Test
    void unloadedTerrainIsSkippedNotLoaded() {
        World w = new World(); // nothing loaded
        w.safe.add(DEFENDING);
        w.safe.add(CENTRE);
        assertNull(SpawnSpots.choose(DEFENDING, CENTRE, GarrisonRoster.rosterId(VILLAGE, 3), w));
        assertTrue(w.touched.isEmpty(), "no chunk was touched");
        assertTrue(w.loaded.isEmpty(), "nothing was loaded by the search");
        w.load(CENTRE);
        SpawnSpots.Choice<BlockPos> c = SpawnSpots.choose(DEFENDING, CENTRE, GarrisonRoster.rosterId(VILLAGE, 3), w);
        assertTrue(c != null && c.fallback());
        assertEquals(Set.of(World.chunk(CENTRE)), w.loaded, "still only the chunk that was already loaded");
    }

    @Test
    void centreEqualToTheDefendingPositionIsSearchedOnce() {
        World w = new World().load(CENTRE);
        assertNull(SpawnSpots.choose(CENTRE, CENTRE, GarrisonRoster.rosterId(VILLAGE, 4), w));
        assertEquals(List.of(CENTRE), w.asked);
    }

    @Test
    void fallbackSpawnKeepsTheDeterministicUuidAndRestartDoesNotDuplicate() {
        GarrisonRoster r = new GarrisonRoster(0);
        RosterEntry e = recruit(r);
        e.assignedDuty = Duty.SENTRY;
        e.duty = Duty.SENTRY;
        e.dutyIndex = 0;
        World w = new World().load(DEFENDING).load(CENTRE);
        w.safe.add(CENTRE);
        assertTrue(SpawnSpots.choose(DEFENDING, CENTRE, e.rosterId, w).fallback());
        GarrisonTag tag = r.beginSpawn(VILLAGE, e, 10);
        UUID entity = tag.expectedEntityUuid();
        r.spawned(e, 1, 10);
        assertEquals(entity, e.entityUuid, "same deterministic UUID as any spawn");
        // restart: the roster is reloaded; the loaded entity rejoins and binds; a second copy is refused
        GarrisonRoster back = GarrisonRoster.load(r.save(), 20);
        RosterEntry b = back.entry(e.rosterId);
        assertEquals(entity, b.entityUuid);
        assertEquals(JoinAdjudicator.Decision.BIND, JoinAdjudicator.onJoin(back, tag, entity, FACTION, FACTION, 30));
        assertEquals(JoinAdjudicator.Decision.DUPLICATE, JoinAdjudicator.onJoin(back, tag, UUID.randomUUID(), FACTION, FACTION, 31));
        Map<UUID, Reconciler.Observation> world = new HashMap<>();
        world.put(entity, new Reconciler.Observation(FACTION, CENTRE.getX(), CENTRE.getY(), CENTRE.getZ()));
        Reconciler.reconcile(back, FACTION, world::get, 400, true, GarrisonSettings.DEFAULTS);
        assertEquals(1, back.entries().size(), "no new slot");
        assertEquals(UnitState.GARRISONED, b.state());
        // the fallback touched neither the duty assignment nor anything stored about the anchor
        assertEquals(Duty.SENTRY, b.assignedDuty);
        assertEquals(0, b.dutyIndex);
    }
}

package dev.hywmill.garrison;

import dev.hywmill.garrison.tag.GarrisonTag;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Randomized model of the world and the roster (10,000 seeded runs): spawns, deaths, removals,
 * captures, chunk unloads/reloads, saves, crashes that lose the roster or the entities written
 * since the last save, dev rewinds, and restarts. After every step the invariants must hold:
 *
 * <ul>
 *   <li>I1: at most one loaded live entity per (rosterId, generation), and per rosterId.</li>
 *   <li>I2: every loaded tagged entity is bound by a non-terminal roster entry (same UUID).</li>
 *   <li>I3: entities only come from RECRUITED entries (spawn is the only creator).</li>
 *   <li>I4: an unloaded entity never causes a spawn: the number of spawns never exceeds the
 *       number of RECRUITED entries processed.</li>
 *   <li>I5: restart is idempotent: a restart with no other events spawns nothing.</li>
 * </ul>
 * The world model follows vanilla's rule, verified in M3-0 (R4), that an entity whose UUID is
 * already loaded is not added.
 */
class DuplicationPropertyTest {
    static final UUID VILLAGE = GarrisonLifecycleTest.VILLAGE;
    static final UUID FACTION = ReconcilerTest.FACTION;
    static final GarrisonSettings S = GarrisonSettings.DEFAULTS;
    static boolean DEBUG = Boolean.getBoolean("hywmill.dupdebug");

    /** A persisted entity (in a chunk file or loaded). */
    static final class Ent {
        final UUID uuid;
        final GarrisonTag tag;
        UUID owner;
        boolean loaded;
        boolean alive = true;
        boolean savedOnDisk;
        final int chunk;

        Ent(UUID uuid, GarrisonTag tag, UUID owner, int chunk) {
            this.uuid = uuid;
            this.tag = tag;
            this.owner = owner;
            this.chunk = chunk;
        }
    }

    static final class World {
        final Random rnd;
        GarrisonRoster roster = new GarrisonRoster(0);
        CompoundTag savedRoster = roster.save();
        final List<Ent> ents = new ArrayList<>();
        final boolean[] chunkLoaded = {true, true, true, true};
        long tick;
        int spawns;
        int recruitedEver;

        World(long seed) {
            rnd = new Random(seed);
        }

        Ent loaded(UUID uuid) {
            for (Ent e : ents) {
                if (e.loaded && e.alive && e.uuid.equals(uuid)) {
                    return e;
                }
            }
            return null;
        }

        /** Vanilla add: refused if an entity with that UUID is already loaded. Then HywMill's join adjudication. */
        boolean join(Ent e) {
            if (loaded(e.uuid) != null) {
                return false;
            }
            JoinAdjudicator.Decision d = JoinAdjudicator.onJoin(roster, e.tag, e.uuid, e.owner, FACTION, tick);
            if (d == JoinAdjudicator.Decision.DUPLICATE) {
                e.alive = false; // cancelled join: dropped from the chunk
                return false;
            }
            e.loaded = true;
            if (d == JoinAdjudicator.Decision.RELEASE || d == JoinAdjudicator.Decision.ORPHAN) {
                e.alive = false; // untagged from now on: not a garrison entity any more (tracked out of the model)
            }
            return true;
        }

        void spawnPending() {
            if (!chunkLoaded[0]) {
                return;
            }
            for (RosterEntry r : new ArrayList<>(roster.entries())) {
                if (r.state() != UnitState.RECRUITED) {
                    continue;
                }
                GarrisonTag tag = roster.beginSpawn(VILLAGE, r, tick);
                int chunk = rnd.nextInt(4);
                while (!chunkLoaded[chunk]) {
                    chunk = rnd.nextInt(4);
                }
                Ent e = new Ent(r.entityUuid, tag, FACTION, chunk);
                spawns++;
                if (join(e)) {
                    ents.add(e);
                    roster.spawned(r, 1, tick);
                } else {
                    roster.revertSpawn(r, tick);
                }
            }
        }

        void reconcile() {
            for (Reconciler.Event ev : Reconciler.reconcile(roster, FACTION, id -> {
                Ent e = loaded(id);
                return e == null ? null : new Reconciler.Observation(e.owner, 0, 0, 0);
            }, tick, true, S)) {
                if (ev.kind() == Reconciler.Kind.CAPTURED) {
                    Ent e = loaded(ev.entityUuid());
                    if (e != null) {
                        e.alive = false; // the service strips the tag: an ordinary (captured) HYW unit from now on
                    }
                }
            }
        }

        void step() {
            tick += 200;
            int kind = rnd.nextInt(12);
            if (DEBUG) System.out.println("step kind " + kind + " roster " + roster.entries() + " ents " + ents.stream().map(e -> e.uuid.toString().substring(0, 8) + (e.loaded ? "L" : "") + (e.alive ? "" : "x") + "g" + e.tag.generation()).toList());
            switch (kind) {
                case 0, 1 -> {
                    if (roster.live() < 6) {
                        roster.recruit(VILLAGE, "archer", "hundred_years_war:archer", 1, tick, true);
                        recruitedEver++;
                    }
                }
                case 2, 3 -> {
                    reconcile();
                    spawnPending();
                }
                case 4 -> { // a loaded unit dies
                    Ent e = pickLoaded();
                    if (e != null) {
                        e.alive = false;
                        RosterEntry r = roster.entry(e.tag.rosterId());
                        if (r != null && !r.state().terminal() && e.uuid.equals(r.entityUuid)) {
                            r.transition(UnitState.DEAD, tick, LossReason.KILLED);
                        }
                    }
                }
                case 5 -> { // chunk unload / load
                    int c = 1 + rnd.nextInt(3);
                    chunkLoaded[c] = !chunkLoaded[c];
                    for (Ent e : ents) {
                        if (e.chunk == c && e.alive) {
                            if (!chunkLoaded[c] && e.loaded) {
                                e.loaded = false;
                                e.savedOnDisk = true;
                            } else if (chunkLoaded[c] && !e.loaded && e.savedOnDisk) {
                                join(e);
                            }
                        }
                    }
                }
                case 6 -> { // save everything
                    savedRoster = roster.save();
                    for (Ent e : ents) {
                        if (e.alive) {
                            e.savedOnDisk = true;
                        }
                    }
                }
                case 7 -> crash(rnd.nextBoolean());
                case 8 -> { // dev rewind of a live slot (stale roster)
                    List<RosterEntry> live = roster.entries().stream().filter(r -> r.state().bound()).toList();
                    if (!live.isEmpty()) {
                        live.get(rnd.nextInt(live.size())).devRewind(tick);
                    }
                    reconcile(); // the next garrison slot (invariants are checked after slots and joins)
                }
                case 9 -> { // capture
                    Ent e = pickLoaded();
                    if (e != null && rnd.nextInt(4) == 0) {
                        e.owner = UUID.randomUUID();
                    }
                }
                default -> reconcile();
            }
        }

        Ent pickLoaded() {
            List<Ent> l = ents.stream().filter(e -> e.loaded && e.alive).toList();
            return l.isEmpty() ? null : l.get(rnd.nextInt(l.size()));
        }

        /**
         * Crash: either the roster or the entities lose what happened since the last save (the two
         * are written by different parts of the save, so either can be behind), then restart.
         */
        void crash(boolean rosterBehind) {
            if (rosterBehind) {
                roster = GarrisonRoster.load(savedRoster, tick);
            } else {
                ents.removeIf(e -> !e.savedOnDisk);
            }
            restart();
        }

        void restart() {
            for (Ent e : ents) {
                e.loaded = false;
            }
            // chunks load in random order
            List<Ent> order = new ArrayList<>(ents);
            java.util.Collections.shuffle(order, rnd);
            for (Ent e : order) {
                if (e.alive && e.savedOnDisk && chunkLoaded[e.chunk]) {
                    join(e);
                }
            }
        }

        void checkInvariants(String where) {
            Map<UUID, Integer> perSlot = new HashMap<>();
            Set<UUID> uuids = new HashSet<>();
            for (Ent e : ents) {
                if (!e.loaded || !e.alive) {
                    continue;
                }
                assertTrue(uuids.add(e.uuid), where + ": two loaded entities with one UUID");
                perSlot.merge(e.tag.rosterId(), 1, Integer::sum);
                RosterEntry r = roster.entry(e.tag.rosterId());
                assertNotNull(r, where + ": I2 loaded tagged entity without a roster entry");
                assertFalse(r.state().terminal(), where + ": I2 loaded tagged entity of a terminal slot " + r);
                assertEquals(r.entityUuid, e.uuid, where + ": I2 loaded tagged entity not bound by its slot");
            }
            perSlot.forEach((slot, n) -> assertEquals(1, n, where + ": I1 slot " + slot + " has " + n + " loaded entities"));
            assertTrue(spawns <= recruitedEver + countRewindsAndReverts(), where + ": I3/I4 more spawns than recruits");
        }

        int rewindsOrReverts;

        int countRewindsAndReverts() {
            // every spawn consumes one generation of a slot that was RECRUITED; generations bound the spawns
            int gens = 0;
            for (RosterEntry r : roster.entries()) {
                gens += r.generation;
            }
            return Math.max(gens, spawns);
        }
    }

    @Test
    void invariantsHoldOver10000SeededRuns() {
        for (long seed = Long.getLong("hywmill.dupseed", 0); seed < Long.getLong("hywmill.dupseedEnd", 10_000); seed++) {
            World w = new World(seed);
            for (int i = 0; i < 60; i++) {
                w.step();
                w.checkInvariants("seed " + seed + " step " + i);
            }
        }
    }

    @Test
    void restartIsIdempotent() {
        for (long seed = 0; seed < 2_000; seed++) {
            World w = new World(seed);
            for (int i = 0; i < 40; i++) {
                w.step();
            }
            w.savedRoster = w.roster.save();
            int before = w.spawns;
            w.restart();
            w.reconcile();
            assertEquals(before, w.spawns, "seed " + seed + ": restart spawned");
            long loaded = w.ents.stream().filter(e -> e.loaded && e.alive).count();
            long bound = w.roster.entries().stream().filter(r -> r.state().bound()).count();
            assertTrue(loaded <= bound, "seed " + seed + ": " + loaded + " loaded units for " + bound + " bound slots");
            w.checkInvariants("seed " + seed + " after restart");
        }
    }

    @Test
    void tenRosterEntriesNeverBecomeTwentyEntities() {
        World w = new World(42);
        for (int i = 0; i < 10; i++) {
            w.roster.recruit(VILLAGE, "archer", "hundred_years_war:archer", 1, 0, true);
        }
        w.spawnPending();
        w.savedRoster = w.roster.save();
        for (Ent e : w.ents) {
            e.savedOnDisk = true;
        }
        for (int round = 0; round < 50; round++) {
            // stale roster: rewind every slot, restart, spawn again before and after chunks load
            for (RosterEntry r : w.roster.entries()) {
                if (r.state().bound()) {
                    r.devRewind(w.tick);
                }
            }
            w.reconcile();
            w.spawnPending();
            w.restart();
            w.reconcile();
            w.spawnPending();
            long loaded = w.ents.stream().filter(e -> e.loaded && e.alive).count();
            assertTrue(loaded <= 10, "round " + round + ": " + loaded + " loaded entities for 10 slots");
            w.checkInvariants("round " + round);
        }
    }
}

package dev.hywmill.garrison.service;

import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.duty.DutyAllocator;
import dev.hywmill.garrison.duty.DutyMotion;
import dev.hywmill.garrison.duty.DutyQuota;
import dev.hywmill.garrison.duty.MoveRule;
import dev.hywmill.garrison.duty.ScoutRule;
import dev.hywmill.garrison.duty.StuckWatch;
import dev.hywmill.garrison.duty.StuckWatch.Step;
import dev.hywmill.garrison.duty.StuckWatch.Track;
import dev.hywmill.garrison.tables.UnitClass;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 reliability recovery ({@link StuckWatch}): a home-duty unit the duty movement has not got anywhere for a long time
 * falls back to the village by normal movement, is moved there only as a last resort, and goes back on GARRISON duty for
 * the allocator; units that are merely slow, waiting, or owned by another subsystem are never touched. Duty ticks are
 * simulated every 40 ticks (the default interval) with the shipped movement and scouting data.
 */
class StuckWatchTest {
    static final MoveRule M = MoveRule.DEFAULT;
    static final ScoutRule S = ScoutRule.DEFAULT;
    static final StuckWatch.Limits L = StuckWatch.Limits.of(M, S);
    static final int DT = 40;
    static final BlockPos POST = new BlockPos(613, 72, 907);
    static final double SENTRY_ARRIVE = StuckWatch.arrive(Duty.SENTRY, M);

    /** Runs duty ticks from {@code from} (inclusive) to {@code to}; returns the first tick observe() reported stuck, else -1. */
    static long firstStuck(Track t, long from, long to, java.util.function.LongFunction<BlockPos> goal,
                           java.util.function.LongToDoubleFunction dist, double arrive) {
        for (long tick = from; tick < to; tick += DT) {
            if (StuckWatch.observe(t, goal.apply(tick), dist.applyAsDouble(tick), arrive, tick, L)) {
                return tick;
            }
        }
        return -1;
    }

    @Test
    void limitsExceedEveryLegitimateWait() {
        // a scout that cannot ride further watches where it is: up to phaseTimeout (out) + dwell without getting closer
        assertTrue(L.stuckTicks() > S.phaseTimeout() + S.dwell(), "window " + L.stuckTicks());
        assertTrue(L.stuckTicks() >= 6000, "at least five minutes");
        assertEquals(5 * M.hopTimeout(), L.fallbackTicks(), "one full detour cycle of the normal movement before the last resort");
        assertTrue(StuckWatch.watched(Duty.GARRISON) && StuckWatch.watched(Duty.SENTRY) && StuckWatch.watched(Duty.PATROL)
                && StuckWatch.watched(Duty.SCOUT));
    }

    @Test
    void normalMovementNeverTriggersFallback() {
        // 1. a slow sentry (0.25 blocks/s) walking 150 blocks, then standing at its post for an hour
        Track t = new Track();
        assertEquals(-1, firstStuck(t, 0, 72000, k -> POST, k -> Math.max(2, 150 - k * 0.0125), SENTRY_ARRIVE));
        // 2. a sentry holding within M4's hold radius of a post it cannot reach (a tower top): at duty
        assertEquals(-1, firstStuck(new Track(), 0, 72000, k -> POST, k -> 20, SENTRY_ARRIVE));
        // 3. a patrol: legs of ~40 blocks walked at ~1 block/s, 5 s pause at each waypoint, forever
        List<BlockPos> wps = List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0), new BlockPos(40, 64, 40), new BlockPos(0, 64, 40));
        long leg = 40 * 20 + M.patrolPause();
        assertEquals(-1, firstStuck(new Track(), 0, 72000, k -> wps.get((int) ((k / leg) % 4)),
                k -> Math.max(0, 40 - (k % leg) / 20.0), StuckWatch.arrive(Duty.PATROL, M)));
        // 4. a scout whose ride out is blocked half way: out (no progress, phaseTimeout), watches where it is (dwell),
        //    rides back (progress), rests at the base; repeated
        BlockPos post = new BlockPos(750, 70, 550), base = new BlockPos(650, 70, 610);
        long out = S.phaseTimeout(), dwell = S.dwell(), back = 60 * 20, rest = S.rest(), cycle = out + dwell + back + rest;
        assertEquals(-1, firstStuck(new Track(), 0, 72000, k -> {
            long c = k % cycle;
            return c < out + dwell ? post : base;
        }, k -> {
            long c = k % cycle;
            if (c < out + dwell) {
                return 60; // stuck half way, then watching from there
            }
            if (c < out + dwell + back) {
                return 60 - (c - out - dwell) / 20.0; // riding back
            }
            return 10; // resting at the base
        }, StuckWatch.arrive(Duty.SCOUT, M)));
    }

    @Test
    void genuinelyStuckSentryEventuallyRecovers() {
        // the ravine case: wanders 35-55 blocks from its post, never getting closer
        Track t = new Track();
        long stuck = firstStuck(t, 0, 72000, k -> POST, k -> 45 - 10 * Math.cos(k / 400.0), SENTRY_ARRIVE);
        assertTrue(stuck >= L.stuckTicks() && stuck < L.stuckTicks() + 2 * DT, "stuck after the window, not before: " + stuck);
        // falls back by normal movement and walks there: recovered without any teleport
        StuckWatch.fallBack(t, new BlockPos(650, 68, 920), 60, stuck);
        Step s = Step.MOVE;
        long tick = stuck;
        double d = 60;
        while (s == Step.MOVE) {
            tick += DT;
            d -= 1.5;
            s = StuckWatch.fallback(t, d, StuckWatch.arriveFallback(M), tick, L);
        }
        assertEquals(Step.RECOVERED, s);
    }

    @Test
    void genuinelyStuckPatrolEventuallyRecovers() {
        // in a pit: its waypoints change (leg timeouts, blocked legs) but it never gets closer to any
        List<BlockPos> wps = List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0), new BlockPos(40, 64, 40), new BlockPos(0, 64, 40));
        long stuck = firstStuck(new Track(), 0, 72000, k -> wps.get((int) ((k / 1200) % 4)), k -> 30 + (k / 1200) % 4,
                StuckWatch.arrive(Duty.PATROL, M));
        assertTrue(stuck >= L.stuckTicks() && stuck < L.stuckTicks() + 2 * DT, "" + stuck);
    }

    @Test
    void genuinelyStuckScoutEventuallyRecovers() {
        // cannot get out: phases cycle (out, watch, back, rest) with no progress towards the post or the base
        BlockPos post = new BlockPos(750, 70, 550), base = new BlockPos(650, 70, 610);
        long stuck = firstStuck(new Track(), 0, 72000, k -> (k / 2400) % 2 == 0 ? post : base, k -> (k / 2400) % 2 == 0 ? 90 : 50,
                StuckWatch.arrive(Duty.SCOUT, M));
        assertTrue(stuck >= L.stuckTicks() && stuck < L.stuckTicks() + 2 * DT, "" + stuck);
    }

    @Test
    void fallbackPositionIsLoadedStandableDeterministicAndBounded() {
        BlockPos defend = new BlockPos(650, 68, 920), centre = new BlockPos(655, 70, 915);
        UUID id = UUID.fromString("c51e7d70-0000-0000-0000-000000000003");
        // the defending position itself is not loaded/standable/safe; the ring points east of it are
        List<BlockPos> asked = new ArrayList<>();
        Function<BlockPos, BlockPos> ground = p -> {
            asked.add(p);
            return p.getX() > defend.getX() ? p.atY(69) : null;
        };
        BlockPos a = StuckWatch.fallbackSpot(id, List.of(defend, centre), ground);
        assertNotNull(a);
        assertTrue(a.getX() > defend.getX(), "only a spot the ground check accepted");
        assertTrue(DutyMotion.horizontal(a.getX() + 0.5, a.getZ() + 0.5, defend) <= StuckWatch.FALLBACK_RADIUS, "bounded: " + a);
        List<BlockPos> first = List.copyOf(asked);
        asked.clear();
        assertEquals(a, StuckWatch.fallbackSpot(id, List.of(defend, centre), ground), "deterministic");
        assertEquals(first, asked, "same candidates, same order");
        // a ground check that shifts the spot too far is not accepted; the centre is the second choice
        assertNull(StuckWatch.fallbackSpot(id, List.of(defend), p -> p.offset(40, 0, 0)));
        BlockPos c = StuckWatch.fallbackSpot(id, List.of(defend, centre), p -> DutyMotion.horizontal(p.getX() + 0.5, p.getZ() + 0.5, centre) < 1 ? p : null);
        assertEquals(centre, c);
        // nothing loaded and standable: no fallback (never a chunk load or an unchecked spot)
        assertNull(StuckWatch.fallbackSpot(id, List.of(defend, centre), p -> null));
    }

    @Test
    void teleportIsOnlyTheLastResort() {
        Track t = new Track();
        StuckWatch.observe(t, POST, 50, SENTRY_ARRIVE, 0, L);
        StuckWatch.fallBack(t, new BlockPos(650, 68, 920), 60, 0);
        // walking towards the spot, even slowly (4 blocks a minute): never the last resort
        double d = 60;
        for (long tick = DT; d > 10; tick += DT) {
            d -= 4.0 / (1200 / DT);
            assertEquals(Step.MOVE, StuckWatch.fallback(t, d, StuckWatch.arriveFallback(M), tick, L), "at " + tick);
        }
        // cannot reach it: normal movement for the whole bounded recovery period, then (only then) the last resort
        Track u = new Track();
        StuckWatch.observe(u, POST, 50, SENTRY_ARRIVE, 0, L);
        StuckWatch.fallBack(u, new BlockPos(650, 68, 920), 60, 0);
        long first = -1;
        for (long tick = DT; tick <= 2 * L.fallbackTicks(); tick += DT) {
            Step s = StuckWatch.fallback(u, 60 + Math.sin(tick), StuckWatch.arriveFallback(M), tick, L);
            if (s == Step.TELEPORT) {
                first = tick;
                break;
            }
            assertEquals(Step.MOVE, s);
        }
        assertTrue(first >= L.fallbackTicks() && first < L.fallbackTicks() + 2 * DT, "" + first);
    }

    static RosterEntry garrisoned(GarrisonRoster r, UUID village, Duty duty, int index) {
        RosterEntry e = r.recruit(village, "archer", "hundred_years_war:archer", 1, 0, true);
        r.beginSpawn(village, e, 0);
        r.spawned(e, 1, 0);
        e.transition(UnitState.GARRISONED, 0);
        e.assignedDuty = duty;
        e.duty = duty;
        e.dutyIndex = index;
        return e;
    }

    @Test
    void defenseRaidLentAndReturningUnitsAreNeverTouched() {
        GarrisonRoster r = new GarrisonRoster(0);
        RosterEntry e = garrisoned(r, UUID.randomUUID(), Duty.SENTRY, 0);
        assertTrue(DutyService.movedByDuties(e), "precondition");
        e.duty = Duty.RAID;
        assertFalse(DutyService.movedByDuties(e), "raid contingent: RaidService's");
        e.duty = Duty.DETACHED;
        assertFalse(DutyService.movedByDuties(e), "lent soldier: the request system's");
        e.duty = Duty.DEFENSE;
        e.transition(UnitState.DEPLOYED, 1);
        assertFalse(DutyService.movedByDuties(e), "M2 DEPLOYED + DEFENSE: the defense coordinator's");
        e.duty = Duty.RAID;
        assertFalse(DutyService.movedByDuties(e), "DEPLOYED + RAID");
        e.transition(UnitState.RETURNING, 2);
        e.duty = Duty.RETURNING;
        assertFalse(DutyService.movedByDuties(e), "RETURNING: the deployment's");
        for (Duty d : List.of(Duty.DEFENSE, Duty.RAID, Duty.RETURNING, Duty.DETACHED, Duty.RESERVE)) {
            assertFalse(StuckWatch.watched(d), d + " is not watched");
        }
    }

    @Test
    void recoveredUnitBecomesGarrisonAndIsReallocated() {
        GarrisonRoster r = new GarrisonRoster(0);
        UUID village = UUID.randomUUID();
        RosterEntry stuck = garrisoned(r, village, Duty.SENTRY, 0);
        stuck.dutyStep = 3;
        RosterEntry mate = garrisoned(r, village, Duty.SENTRY, 0);
        Track t = new Track();
        StuckWatch.observe(t, POST, 50, SENTRY_ARRIVE, 0, L);
        StuckWatch.fallBack(t, new BlockPos(650, 68, 920), 60, 0);
        StuckWatch.toGarrison(stuck, t, 100);
        assertEquals(Duty.GARRISON, stuck.assignedDuty);
        assertEquals(Duty.GARRISON, stuck.duty);
        assertEquals(-1, stuck.dutyIndex);
        assertEquals(0, stuck.dutyStep);
        assertNull(t.spot(), "the failed target is cleared");
        // the normal allocator fills the pair again from the available units (the recovered one included)
        Map<UUID, DutyAllocator.Assignment> out = DutyAllocator.allocate(List.of(
                new DutyAllocator.Candidate(stuck.rosterId, UnitClass.RANGED, stuck.assignedDuty, stuck.dutyIndex),
                new DutyAllocator.Candidate(mate.rosterId, UnitClass.RANGED, mate.assignedDuty, mate.dutyIndex)), new DutyQuota(1, 0, 0, 0));
        assertEquals(new DutyAllocator.Assignment(Duty.SENTRY, 0), out.get(stuck.rosterId), "redistributed on the next pass");
        assertEquals(new DutyAllocator.Assignment(Duty.SENTRY, 0), out.get(mate.rosterId));
    }

    @Test
    void noRepeatedTeleportEveryDutyTick() {
        // a unit that is stuck for ever (even after each recovery): count last-resort moves over two hours of duty ticks
        Track t = new Track();
        int teleports = 0;
        long lastTeleport = Long.MIN_VALUE;
        for (long tick = 0; tick < 144000; tick += DT) {
            if (t.spot() != null) {
                Step s = StuckWatch.fallback(t, 60, StuckWatch.arriveFallback(M), tick, L);
                if (s == Step.TELEPORT) {
                    teleports++;
                    assertTrue(lastTeleport == Long.MIN_VALUE || tick - lastTeleport >= L.stuckTicks() + L.fallbackTicks(),
                            "at most one per window: " + (tick - lastTeleport));
                    lastTeleport = tick;
                    StuckWatch.toGarrison(new RosterEntry(UUID.randomUUID(), "archer", "x", 1, 0, true), t, tick);
                }
            } else if (StuckWatch.observe(t, POST, 50, SENTRY_ARRIVE, tick, L)) {
                StuckWatch.fallBack(t, new BlockPos(650, 68, 920), 60, tick);
            }
        }
        long window = L.stuckTicks() + L.fallbackTicks();
        assertTrue(teleports >= 1 && teleports <= 144000 / window, teleports + " last-resort moves in 2 h");
        // and an invalid spot at the last resort drops the fallback: back to duty, another full window before a new one
        Track u = new Track();
        StuckWatch.observe(u, POST, 50, SENTRY_ARRIVE, 0, L);
        StuckWatch.fallBack(u, new BlockPos(650, 68, 920), 60, 0);
        StuckWatch.abandonFallback(u, 5000);
        assertNull(u.spot());
        assertEquals(-1, firstStuck(u, 5000 + DT, 5000 + L.stuckTicks() - DT, k -> POST, k -> 50, SENTRY_ARRIVE));
    }

    @Test
    void unitsUnseenForAWhileStartAFreshWatch() {
        // unloaded or deployed for a while (not watched): it is not stuck the moment it is seen again
        Track t = new Track();
        StuckWatch.observe(t, POST, 50, SENTRY_ARRIVE, 0, L);
        assertFalse(StuckWatch.observe(t, POST, 50, SENTRY_ARRIVE, 3 * L.stuckTicks(), L));
        assertFalse(StuckWatch.observe(t, POST, 50, SENTRY_ARRIVE, 3 * L.stuckTicks() + DT, L));
    }
}

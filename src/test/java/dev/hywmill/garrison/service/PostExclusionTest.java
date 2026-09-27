package dev.hywmill.garrison.service;

import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.duty.DutyAllocator;
import dev.hywmill.garrison.duty.DutyAllocator.Assignment;
import dev.hywmill.garrison.duty.DutyAllocator.Candidate;
import dev.hywmill.garrison.duty.DutyMotion;
import dev.hywmill.garrison.duty.DutyQuota;
import dev.hywmill.garrison.duty.StuckWatch;
import dev.hywmill.garrison.tables.UnitClass;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Post exclusion after a stuck-unit recovery (approved): a recovered unit is not given the SENTRY pair or PATROL slot it
 * failed at, or any SCOUT duty, for {@link StuckWatch#AVOID_TICKS}; nothing else in the allocation changes. Breaks the
 * observed cycle post → stuck → fallback → GARRISON → allocator → same post (docs/m5-test-evidence/g4-diag4*.txt).
 */
class PostExclusionTest {
    static final UUID R = new UUID(0, 1);   // the recovered unit: lowest id, so it wins every tie
    static final UUID M = new UUID(0, 2);
    static final UUID X = new UUID(0, 3);
    static final UUID Y = new UUID(0, 4);
    static final BlockPos SPOT = new BlockPos(658, 78, 975);

    static Candidate c(UUID id, UnitClass cls, Duty cur, int idx, Assignment avoid) {
        return new Candidate(id, cls, cur, idx, avoid);
    }

    static RosterEntry entry(Duty duty, int index) {
        GarrisonRoster r = new GarrisonRoster(0);
        UUID village = UUID.randomUUID();
        RosterEntry e = r.recruit(village, "archer", "hundred_years_war:archer", 1, 0, true);
        r.beginSpawn(village, e, 0);
        r.spawned(e, 1, 0);
        e.transition(UnitState.GARRISONED, 0);
        e.assignedDuty = duty;
        e.duty = duty;
        e.dutyIndex = index;
        return e;
    }

    /** Runs the recovery path of the service on a unit on {@code duty}#{@code index}: fall back, recover. */
    static StuckWatch.Avoid recover(RosterEntry e, long tick) {
        StuckWatch.Track t = new StuckWatch.Track();
        StuckWatch.fallBack(t, e, SPOT, 60, tick - 3000);
        return StuckWatch.toGarrison(e, t, tick);
    }

    @Test
    void recoveredSentryDoesNotRefillItsFailedPair() {
        StuckWatch.Avoid av = recover(entry(Duty.SENTRY, 0), 1000);
        assertEquals(new Assignment(Duty.SENTRY, 0), av.slot());
        DutyQuota q = new DutyQuota(1, 0, 0, 0);
        // without the exclusion the recovered archer (ranged, lowest id) takes pair 0 straight back: the observed cycle
        List<Candidate> base = List.of(c(R, UnitClass.RANGED, Duty.GARRISON, -1, null), c(M, UnitClass.RANGED, Duty.SENTRY, 0, null),
                c(X, UnitClass.LINE, Duty.GARRISON, -1, null));
        assertEquals(new Assignment(Duty.SENTRY, 0), DutyAllocator.allocate(base, q).get(R));
        Map<UUID, Assignment> out = DutyAllocator.allocate(List.of(c(R, UnitClass.RANGED, Duty.GARRISON, -1, av.slot()),
                base.get(1), base.get(2)), q);
        assertNotEquals(new Assignment(Duty.SENTRY, 0), out.get(R));
        assertEquals(new Assignment(Duty.SENTRY, 0), out.get(X), "another unit fills the pair");
        assertEquals(new Assignment(Duty.SENTRY, 0), out.get(M));
    }

    @Test
    void recoveredPatrolDoesNotRefillItsFailedSlotButMayTakeAnother() {
        StuckWatch.Avoid av = recover(entry(Duty.PATROL, 1), 1000);
        assertEquals(new Assignment(Duty.PATROL, 1), av.slot());
        List<Candidate> units = List.of(c(R, UnitClass.LEVY, Duty.GARRISON, -1, av.slot()), c(M, UnitClass.LEVY, Duty.PATROL, 0, null),
                c(X, UnitClass.LINE, Duty.GARRISON, -1, null));
        Map<UUID, Assignment> two = DutyAllocator.allocate(units, new DutyQuota(0, 2, 0, 0));
        assertEquals(new Assignment(Duty.PATROL, 1), two.get(X), "slot 1 goes to another unit");
        assertEquals(new Assignment(Duty.GARRISON, -1), two.get(R));
        Map<UUID, Assignment> three = DutyAllocator.allocate(units, new DutyQuota(0, 3, 0, 0));
        assertEquals(new Assignment(Duty.PATROL, 2), three.get(R), "only the failed slot is excluded");
    }

    @Test
    void recoveredScoutIsExcludedFromScoutingAndResumesItsRide() {
        RosterEntry e = entry(Duty.SCOUT, 1);
        e.dutyStep = 4 + DutyMotion.OUT; // ride 1, riding out: the failed ride
        StuckWatch.Avoid av = recover(e, 1000);
        assertEquals(Duty.SCOUT, av.duty());
        assertEquals(2, av.nextRide(), "resumes after the failed ride");
        DutyQuota q = new DutyQuota(0, 0, 2, 0);
        Map<UUID, Assignment> out = DutyAllocator.allocate(List.of(c(R, UnitClass.CAVALRY, Duty.GARRISON, -1, av.slot()),
                c(M, UnitClass.RANGED, Duty.GARRISON, -1, null)), q);
        assertEquals(Duty.SCOUT, out.get(M).duty());
        assertNotEquals(Duty.SCOUT, out.get(R).duty(), "no scouting at all, whatever the index");
        // when it scouts again: DutyMotion.start begins at ride 0; the recovery resumes it at the next ride instead
        e.assignedDuty = Duty.SCOUT;
        e.dutyIndex = 1;
        e.dutyStep = DutyMotion.OUT; // what start() sets
        StuckWatch.resumeScout(e, av.nextRide());
        assertEquals(DutyMotion.OUT, DutyMotion.phase(e), "the M4 phase is unchanged");
        assertEquals(2, e.dutyStep / 4, "ride 2, not ride 0");
    }

    @Test
    void theExclusionExpiresAfterTwelveThousandTicks() {
        StuckWatch.Avoid av = recover(entry(Duty.SENTRY, 0), 1000);
        assertEquals(12000, StuckWatch.AVOID_TICKS);
        assertTrue(av.active(1000));
        assertTrue(av.active(1000 + 11999));
        assertFalse(av.active(1000 + 12000), "expired");
        // expired: the service builds the candidate without it and the unit is eligible normally again
        Map<UUID, Assignment> out = DutyAllocator.allocate(List.of(c(R, UnitClass.RANGED, Duty.GARRISON, -1, null),
                c(M, UnitClass.RANGED, Duty.SENTRY, 0, null), c(X, UnitClass.LINE, Duty.GARRISON, -1, null)), new DutyQuota(1, 0, 0, 0));
        assertEquals(new Assignment(Duty.SENTRY, 0), out.get(R));
        // GARRISON (or anything else) recovered: nothing to exclude
        assertNull(recover(entry(Duty.GARRISON, -1), 1000));
    }

    static List<Candidate> mixed() {
        List<Candidate> out = new ArrayList<>();
        UnitClass[] cls = UnitClass.values();
        Duty[] cur = {Duty.GARRISON, Duty.SENTRY, Duty.PATROL, Duty.SCOUT, Duty.RESERVE};
        for (int i = 0; i < 40; i++) {
            Duty d = cur[i % cur.length];
            out.add(new Candidate(new UUID(3, i * 7919L), cls[i % cls.length], d, d == Duty.GARRISON || d == Duty.RESERVE ? -1 : i % 5));
        }
        return out;
    }

    @Test
    void otherUnitsAreUnaffectedByAnotherUnitsExclusion() {
        DutyQuota q = new DutyQuota(4, 6, 3, 4);
        List<Candidate> plain = mixed();
        Map<UUID, Assignment> base = DutyAllocator.allocate(plain, q);
        // one unit avoids a slot that is not being filled (outside the quota): every assignment is identical
        List<Candidate> withUnused = new ArrayList<>(plain);
        Candidate r = plain.get(0);
        withUnused.set(0, new Candidate(r.rosterId(), r.unitClass(), r.current(), r.index(), new Assignment(Duty.PATROL, 99)));
        assertEquals(base, DutyAllocator.allocate(withUnused, q));
        // one unit avoids a pair: only its own place and the place of whoever fills that pair instead change
        UUID target = base.entrySet().stream().filter(x -> x.getValue().duty() == Duty.SENTRY && plain.stream()
                .anyMatch(cc -> cc.rosterId().equals(x.getKey()) && cc.current() != Duty.SENTRY)).map(Map.Entry::getKey).findFirst().orElseThrow();
        Assignment was = base.get(target);
        List<Candidate> withAvoid = new ArrayList<>(plain);
        int at = 0;
        for (int i = 0; i < plain.size(); i++) {
            if (plain.get(i).rosterId().equals(target)) {
                at = i;
            }
        }
        Candidate t = plain.get(at);
        withAvoid.set(at, new Candidate(t.rosterId(), t.unitClass(), t.current(), t.index(), was));
        Map<UUID, Assignment> out = DutyAllocator.allocate(withAvoid, q);
        assertNotEquals(was, out.get(target));
        for (Candidate cc : plain) {
            if (cc.current() == Duty.SENTRY || cc.current() == Duty.PATROL || cc.current() == Duty.SCOUT) {
                if (base.get(cc.rosterId()).equals(new Assignment(cc.current(), cc.index()))) {
                    assertEquals(base.get(cc.rosterId()), out.get(cc.rosterId()), "kept assignments are untouched: " + cc);
                }
            }
        }
        assertEquals(base.values().stream().filter(a -> a.duty() == Duty.SENTRY).count(),
                out.values().stream().filter(a -> a.duty() == Duty.SENTRY).count(), "same duty composition");
    }

    @Test
    void theExclusionIsRuntimeOnlyAndLeavesPersistedRosterDataAsBefore() {
        GarrisonRoster roster = new GarrisonRoster(0);
        UUID village = UUID.randomUUID();
        RosterEntry a = roster.recruit(village, "archer", "hundred_years_war:archer", 1, 0, true);
        RosterEntry b = roster.recruit(village, "archer", "hundred_years_war:archer", 1, 0, true);
        for (RosterEntry e : List.of(a, b)) {
            roster.beginSpawn(village, e, 0);
            roster.spawned(e, 1, 0);
            e.transition(UnitState.GARRISONED, 0);
            e.assignedDuty = Duty.SENTRY;
            e.duty = Duty.SENTRY;
            e.dutyIndex = 2;
        }
        // a: recovered with an exclusion; b: the same recovery state set by hand (no exclusion anywhere)
        StuckWatch.Avoid av = recover(a, 500);
        assertNotNull(av);
        b.assignedDuty = Duty.GARRISON;
        b.duty = Duty.GARRISON;
        b.dutyIndex = -1;
        b.dutyStep = 0;
        b.dutySince = 500;
        String saved = roster.save().toString();
        assertFalse(saved.toLowerCase().contains("avoid"), "nothing about the exclusion is saved");
        GarrisonRoster loaded = GarrisonRoster.load(roster.save(), 600);
        for (RosterEntry e : loaded.entries()) {
            assertEquals(Duty.GARRISON, e.assignedDuty);
            assertEquals(-1, e.dutyIndex);
        }
    }

    @Test
    void withoutExclusionsTheAllocationIsUnchangedAndDeterministic() {
        DutyQuota q = new DutyQuota(4, 6, 3, 4);
        List<Candidate> four = mixed(); // the 4-argument constructor: no exclusion
        List<Candidate> five = new ArrayList<>();
        for (Candidate cc : four) {
            five.add(new Candidate(cc.rosterId(), cc.unitClass(), cc.current(), cc.index(), null));
        }
        Map<UUID, Assignment> base = DutyAllocator.allocate(four, q);
        assertEquals(base, DutyAllocator.allocate(five, q));
        for (long seed = 1; seed <= 5; seed++) {
            List<Candidate> shuffled = new ArrayList<>(five);
            Collections.shuffle(shuffled, new Random(seed));
            assertEquals(base, DutyAllocator.allocate(shuffled, q), "input order does not matter");
        }
    }
}

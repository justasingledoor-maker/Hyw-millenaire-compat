package dev.hywmill.garrison;

import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.duty.RaidPlanner;
import dev.hywmill.garrison.duty.RaidRule;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** M4 raid contingent selection. */
class RaidPlannerTest {
    static final RaidRule RULE = new RaidRule(true, 0.35, 2, 12, 0.5, 1, 1, 6);

    static List<RaidPlanner.Candidate> garrison(int garrison, int reserve, int patrol, int sentryPairs, int scouts) {
        List<RaidPlanner.Candidate> out = new ArrayList<>();
        int id = 0;
        for (int i = 0; i < garrison; i++) {
            out.add(new RaidPlanner.Candidate(new UUID(1, id++), Duty.GARRISON, -1));
        }
        for (int i = 0; i < reserve; i++) {
            out.add(new RaidPlanner.Candidate(new UUID(1, id++), Duty.RESERVE, -1));
        }
        for (int i = 0; i < patrol; i++) {
            out.add(new RaidPlanner.Candidate(new UUID(1, id++), Duty.PATROL, i));
        }
        for (int p = 0; p < sentryPairs; p++) {
            out.add(new RaidPlanner.Candidate(new UUID(1, id++), Duty.SENTRY, p));
            out.add(new RaidPlanner.Candidate(new UUID(1, id++), Duty.SENTRY, p));
        }
        for (int i = 0; i < scouts; i++) {
            out.add(new RaidPlanner.Candidate(new UUID(1, id++), Duty.SCOUT, i));
        }
        return out;
    }

    static Map<Duty, Long> byDuty(List<RaidPlanner.Candidate> all, List<UUID> chosen) {
        Map<UUID, Duty> d = all.stream().collect(Collectors.toMap(RaidPlanner.Candidate::rosterId, RaidPlanner.Candidate::duty));
        return chosen.stream().collect(Collectors.groupingBy(d::get, Collectors.counting()));
    }

    @Test
    void sizeFollowsTheCommitFractionAndLeavesTheHomeShare() {
        for (int n = 0; n <= 64; n++) {
            int k = RaidPlanner.size(RULE, n);
            if (k > 0) {
                assertTrue(k >= RULE.minCommit() && k <= RULE.maxCommit(), n + " -> " + k);
                assertTrue(n - k >= Math.ceil(n * RULE.minHome()), "home share kept at " + n);
                assertTrue(n - k >= 1);
            }
        }
        assertEquals(0, RaidPlanner.size(RULE, 5), "below minGarrison nobody goes");
        assertEquals(2, RaidPlanner.size(RULE, 6));
        assertEquals(12, RaidPlanner.size(RULE, 64));
        assertEquals(0, RaidPlanner.size(RaidRule.OFF, 64));
    }

    @Test
    void rovingAndSpareReserveGoFirstThenPatrolAndSentriesAreKept() {
        List<RaidPlanner.Candidate> g = garrison(2, 3, 3, 3, 1);
        // 2 + 3 + 3 + 6 + 1 = 15 available -> 5 go
        List<UUID> c = RaidPlanner.select(RULE, g);
        assertEquals(5, c.size());
        Map<Duty, Long> d = byDuty(g, c);
        assertEquals(2L, d.get(Duty.GARRISON));
        assertEquals(2L, d.get(Duty.RESERVE), "one reserve unit stays");
        assertEquals(1L, d.get(Duty.PATROL));
        assertNull(d.get(Duty.SENTRY));
    }

    @Test
    void theBestSentryPairAndOneReserveAlwaysStay() {
        RaidRule greedy = new RaidRule(true, 0.9, 1, 64, 0.1, 1, 1, 1);
        List<RaidPlanner.Candidate> g = garrison(0, 2, 0, 4, 0);
        List<UUID> c = RaidPlanner.select(greedy, g);
        Map<UUID, RaidPlanner.Candidate> by = g.stream().collect(Collectors.toMap(RaidPlanner.Candidate::rosterId, Function.identity()));
        assertTrue(c.stream().noneMatch(id -> by.get(id).duty() == Duty.SENTRY && by.get(id).index() == 0), "post 0 stays manned");
        assertEquals(1, g.stream().filter(x -> x.duty() == Duty.RESERVE && !c.contains(x.rosterId())).count());
        assertTrue(c.size() < g.size());
    }

    @Test
    void selectionIsDeterministic() {
        List<RaidPlanner.Candidate> g = garrison(4, 3, 4, 4, 2);
        List<UUID> first = RaidPlanner.select(RULE, g);
        for (int i = 0; i < 20; i++) {
            List<RaidPlanner.Candidate> sh = new ArrayList<>(g);
            Collections.shuffle(sh, new Random(i));
            assertEquals(first, RaidPlanner.select(RULE, sh));
        }
    }

    @Test
    void raidRecordSurvivesSaveAndLoad() {
        GarrisonRoster r = new GarrisonRoster(0);
        r.raid = new GarrisonRoster.RaidRecord(new UUID(3, 4), 2906, 1, "AWAY", 3500, 4);
        GarrisonRoster back = GarrisonRoster.load(r.save(), 4000);
        assertNotNull(back.raid);
        assertEquals(new UUID(3, 4), back.raid.target);
        assertEquals(2906, back.raid.raidStart);
        assertEquals(1, back.raid.performedBase);
        assertEquals("AWAY", back.raid.phase);
        assertEquals(4, back.raid.sent);
        CompoundTag none = new GarrisonRoster(0).save();
        assertFalse(none.contains("raid"));
        assertNull(GarrisonRoster.load(none, 0).raid);
    }
}

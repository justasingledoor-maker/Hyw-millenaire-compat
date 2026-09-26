package dev.hywmill.garrison;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.duty.DutyAllocator;
import dev.hywmill.garrison.duty.DutyMotion;
import dev.hywmill.garrison.duty.DutyPlan;
import dev.hywmill.garrison.duty.DutyQuota;
import dev.hywmill.garrison.duty.DutyRule;
import dev.hywmill.garrison.duty.DutyTable;
import dev.hywmill.garrison.duty.DutyTables;
import dev.hywmill.garrison.duty.MoveRule;
import dev.hywmill.garrison.duty.ScoutRule;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.classify.BuildingRole;
import dev.hywmill.military.defense.DefenseCoordinator;
import dev.hywmill.settlement.SettlementSource.Layout;
import dev.hywmill.settlement.SettlementSource.LayoutPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** M4 standing duties: data, quotas, allocation, posts and routes, progress, persistence. */
class DutyTest {
    static final UUID VILLAGE = GarrisonLifecycleTest.VILLAGE;
    static final Path DIR = Path.of("src/main/resources/data/hywmill/hywmill_duties");

    static DutyTables shipped(List<String> problems) throws IOException {
        JsonObject f = JsonParser.parseString(Files.readString(DIR.resolve("defaults.json"))).getAsJsonObject();
        return DutyTables.fromJson(List.of(f), problems);
    }

    static List<DutyAllocator.Candidate> units(int n, UnitClass cls) {
        List<DutyAllocator.Candidate> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new DutyAllocator.Candidate(new UUID(7, i), cls, Duty.GARRISON, -1));
        }
        return out;
    }

    // ---------------------------------------------------------------- data

    @Test
    void shippedDataLoadsWithoutProblems() throws IOException {
        List<String> problems = new ArrayList<>();
        DutyTables t = shipped(problems);
        assertEquals(List.of(), problems);
        assertEquals(7, t.cultures().size());
        assertEquals(DutyRule.NONE, t.defaults().tier(MilitaryTier.NONE));
        assertTrue(t.defaults().raid().enabled());
        // culture patches merge per field and inherit the rest
        DutyTable seljuk = t.forCulture("millenaire:seljuk");
        assertEquals(56, seljuk.scout().distance());
        assertEquals(t.defaults().scout().dwell(), seljuk.scout().dwell());
        assertEquals(6, seljuk.tier(MilitaryTier.STRONGHOLD).maxScouts());
        assertEquals(t.defaults().tier(MilitaryTier.STRONGHOLD).maxSentryPairs(), seljuk.tier(MilitaryTier.STRONGHOLD).maxSentryPairs());
        assertEquals(0.45, seljuk.raid().commitFraction());
        assertEquals(t.defaults().raid().minHome(), seljuk.raid().minHome());
    }

    @Test
    void invalidValuesAreReportedAndDefaulted() {
        JsonObject f = JsonParser.parseString("""
                { "defaults": { "tiers": { "WATCH": { "sentryShare": 3, "maxPatrol": "x" }, "HUGE": {} },
                                "movement": { "maxHop": 500 }, "raid": { "minHome": 0 } } }
                """).getAsJsonObject();
        List<String> problems = new ArrayList<>();
        DutyTables t = DutyTables.fromJson(List.of(f), problems);
        assertEquals(5, problems.size(), problems.toString());
        assertEquals(MoveRule.DEFAULT.maxHop(), t.defaults().move().maxHop());
        assertEquals(0, t.defaults().tier(MilitaryTier.WATCH).sentryShare());
    }

    @Test
    void noDataMeansNoDutiesAndNoRaids() {
        DutyTables t = DutyTables.fallback();
        assertFalse(t.defaults().raid().enabled());
        DutyQuota q = DutyQuota.of(t.defaults().tier(MilitaryTier.STRONGHOLD), 64, 10);
        assertEquals(0, q.total());
    }

    // ---------------------------------------------------------------- quotas

    @Test
    void quotaNeverExceedsTheLivingGarrison() throws IOException {
        DutyTables t = shipped(new ArrayList<>());
        for (DutyTable table : new ArrayList<>(List.of(t.defaults()))) {
            for (MilitaryTier tier : MilitaryTier.values()) {
                for (int living = 0; living <= 64; living++) {
                    for (int posts = 0; posts <= 12; posts++) {
                        DutyQuota q = DutyQuota.of(table.tier(tier), living, posts);
                        assertTrue(q.total() <= living, tier + " " + living + " " + posts + " " + q);
                        assertTrue(q.sentryPairs() <= posts);
                        assertTrue(q.sentryPairs() >= 0 && q.patrol() >= 0 && q.scouts() >= 0 && q.reserve() >= 0);
                    }
                }
            }
        }
        for (DutyTable table : t.cultures().values()) {
            for (MilitaryTier tier : MilitaryTier.values()) {
                for (int living = 0; living <= 64; living++) {
                    assertTrue(DutyQuota.of(table.tier(tier), living, 8).total() <= living);
                }
            }
        }
    }

    @Test
    void aFiveSoldierVillageIsNotOverTasked() throws IOException {
        DutyTables t = shipped(new ArrayList<>());
        for (MilitaryTier tier : List.of(MilitaryTier.WATCH, MilitaryTier.GUARD_POST, MilitaryTier.GARRISON)) {
            DutyQuota q = DutyQuota.of(t.defaults().tier(tier), 5, 6);
            assertTrue(q.sentryPairs() <= 1, tier + " " + q);
            assertFalse(q.sentryPairs() >= 2 && q.patrol() > 0 && q.scouts() > 0, tier + " " + q);
        }
    }

    @Test
    void strongholdsAreMoreMilitarisedThanWatches() throws IOException {
        DutyTable d = shipped(new ArrayList<>()).defaults();
        DutyQuota watch = DutyQuota.of(d.tier(MilitaryTier.WATCH), 8, 10);
        DutyQuota stronghold = DutyQuota.of(d.tier(MilitaryTier.STRONGHOLD), 64, 10);
        assertTrue(stronghold.sentryPairs() > watch.sentryPairs());
        assertTrue(stronghold.patrol() > watch.patrol());
        assertTrue(stronghold.scouts() > watch.scouts());
        assertEquals(0, watch.scouts());
    }

    @Test
    void trimmingCutsScoutsFirstThenPatrolThenSentries() {
        DutyRule greedy = new DutyRule(1, 10, 1, 1, 10, 1, 1, 10, 1, 1, 0);
        DutyQuota q = DutyQuota.of(greedy, 6, 10);
        assertEquals(6, q.total());
        assertEquals(0, q.scouts());
        assertEquals(0, q.patrol());
    }

    // ---------------------------------------------------------------- allocation

    @Test
    void allocationIsDeterministicAndIndependentOfInputOrder() {
        List<DutyAllocator.Candidate> u = new ArrayList<>(units(10, UnitClass.LINE));
        u.add(new DutyAllocator.Candidate(new UUID(8, 1), UnitClass.RANGED, Duty.GARRISON, -1));
        u.add(new DutyAllocator.Candidate(new UUID(8, 2), UnitClass.CAVALRY, Duty.GARRISON, -1));
        DutyQuota q = new DutyQuota(2, 2, 1, 1);
        Map<UUID, DutyAllocator.Assignment> a = DutyAllocator.allocate(u, q);
        for (int i = 0; i < 20; i++) {
            List<DutyAllocator.Candidate> shuffled = new ArrayList<>(u);
            Collections.shuffle(shuffled, new Random(i));
            assertEquals(a, DutyAllocator.allocate(shuffled, q));
        }
        assertEquals(Duty.SCOUT, a.get(new UUID(8, 2)).duty(), "cavalry preferred as scouts");
        assertEquals(Duty.SENTRY, a.get(new UUID(8, 1)).duty(), "ranged preferred as sentries");
        Map<Duty, Integer> counts = new HashMap<>();
        a.values().forEach(x -> counts.merge(x.duty(), 1, Integer::sum));
        assertEquals(4, counts.get(Duty.SENTRY));
        assertEquals(2, counts.get(Duty.PATROL));
        assertEquals(1, counts.get(Duty.SCOUT));
        assertEquals(1, counts.get(Duty.RESERVE));
        assertEquals(4, counts.get(Duty.GARRISON));
    }

    @Test
    void sentriesAreFilledInPairsPerPost() {
        Map<UUID, DutyAllocator.Assignment> a = DutyAllocator.allocate(units(7, UnitClass.LINE), new DutyQuota(3, 0, 0, 0));
        Map<Integer, Integer> perPost = new HashMap<>();
        a.values().stream().filter(x -> x.duty() == Duty.SENTRY).forEach(x -> perPost.merge(x.index(), 1, Integer::sum));
        assertEquals(Map.of(0, 2, 1, 2, 2, 2), perPost);
    }

    @Test
    void existingDutiesAreKeptWhenTheGarrisonGrows() {
        List<DutyAllocator.Candidate> u = units(6, UnitClass.LINE);
        DutyQuota q = new DutyQuota(1, 1, 0, 1);
        Map<UUID, DutyAllocator.Assignment> first = DutyAllocator.allocate(u, q);
        List<DutyAllocator.Candidate> now = new ArrayList<>();
        for (DutyAllocator.Candidate c : u) {
            DutyAllocator.Assignment x = first.get(c.rosterId());
            now.add(new DutyAllocator.Candidate(c.rosterId(), c.unitClass(), x.duty(), x.index()));
        }
        // a recruit with a lower roster id joins, quotas grow by one patrol
        now.add(new DutyAllocator.Candidate(new UUID(0, 0), UnitClass.LINE, Duty.GARRISON, -1));
        Map<UUID, DutyAllocator.Assignment> second = DutyAllocator.allocate(now, new DutyQuota(1, 2, 0, 1));
        for (DutyAllocator.Candidate c : u) {
            if (first.get(c.rosterId()).duty() != Duty.GARRISON) {
                assertEquals(first.get(c.rosterId()).duty(), second.get(c.rosterId()).duty(), c.rosterId().toString());
            }
        }
    }

    @Test
    void aLostSentryIsReplacedInTheSamePair() {
        List<DutyAllocator.Candidate> u = units(6, UnitClass.LINE);
        Map<UUID, DutyAllocator.Assignment> first = DutyAllocator.allocate(u, new DutyQuota(2, 0, 0, 0));
        UUID dead = u.stream().filter(c -> first.get(c.rosterId()).duty() == Duty.SENTRY && first.get(c.rosterId()).index() == 1)
                .findFirst().orElseThrow().rosterId();
        List<DutyAllocator.Candidate> now = new ArrayList<>();
        for (DutyAllocator.Candidate c : u) {
            if (!c.rosterId().equals(dead)) {
                DutyAllocator.Assignment x = first.get(c.rosterId());
                now.add(new DutyAllocator.Candidate(c.rosterId(), c.unitClass(), x.duty(), x.index()));
            }
        }
        Map<UUID, DutyAllocator.Assignment> second = DutyAllocator.allocate(now, new DutyQuota(2, 0, 0, 0));
        assertEquals(2, second.values().stream().filter(x -> x.duty() == Duty.SENTRY && x.index() == 1).count());
        assertEquals(2, second.values().stream().filter(x -> x.duty() == Duty.SENTRY && x.index() == 0).count());
    }

    // ---------------------------------------------------------------- plan

    static Layout layout() {
        List<LayoutPoint> mil = List.of(
                new LayoutPoint(BuildingRole.WALL, new BlockPos(40, 64, 0)),
                new LayoutPoint(BuildingRole.WALL, new BlockPos(42, 64, 3)),
                new LayoutPoint(BuildingRole.WALL, new BlockPos(-40, 64, 0)),
                new LayoutPoint(BuildingRole.GATE, new BlockPos(0, 64, 40)),
                new LayoutPoint(BuildingRole.TOWER, new BlockPos(0, 64, -40)),
                new LayoutPoint(BuildingRole.ARMOURY, new BlockPos(5, 64, 5)));
        List<BlockPos> anchors = List.of(new BlockPos(10, 64, 10), new BlockPos(-20, 64, 15), new BlockPos(25, 64, -25), new BlockPos(-30, 64, -20),
                new BlockPos(3, 64, 3), new BlockPos(0, 64, 55));
        return new Layout(BlockPos.ZERO.atY(64), new BlockPos(2, 64, 2), new BlockPos(1, 64, 1), 60, mil, anchors);
    }

    @Test
    void sentryPostsPreferGatesThenTowersThenSpreadWalls() {
        List<BlockPos> posts = DutyPlan.sentryPosts(layout());
        assertEquals(new BlockPos(0, 64, 40), posts.get(0));
        assertEquals(new BlockPos(0, 64, -40), posts.get(1));
        // two wall segments 3.6 blocks apart count as one post
        assertEquals(5, posts.size(), posts.toString());
        assertTrue(posts.contains(new BlockPos(5, 64, 5)));
        assertEquals(posts, DutyPlan.sentryPosts(layout()));
    }

    @Test
    void villageWithoutMilitaryBuildingsStillHasASentryPost() {
        Layout l = new Layout(BlockPos.ZERO, new BlockPos(2, 64, 2), new BlockPos(20, 64, 20), 60, List.of(), List.of());
        assertEquals(List.of(new BlockPos(2, 64, 2), new BlockPos(20, 64, 20)), DutyPlan.sentryPosts(l));
    }

    @Test
    void patrolIsAClosedLoopInsideTheDefensiveArea() {
        Layout l = layout();
        List<BlockPos> route = DutyPlan.patrol(l, MoveRule.DEFAULT);
        assertTrue(route.size() >= 3, route.toString());
        double r = l.radius() * MoveRule.DEFAULT.patrolRadius();
        for (BlockPos p : route) {
            assertTrue(Math.hypot(p.getX(), p.getZ()) <= r + 1e-9, p.toString());
        }
        assertFalse(route.contains(new BlockPos(0, 64, 55)), "beyond the patrol radius");
        assertEquals(route, DutyPlan.patrol(l, MoveRule.DEFAULT));
    }

    @Test
    void sparseVillagesGetARingPatrol() {
        Layout l = new Layout(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, 40, List.of(), List.of(new BlockPos(5, 0, 5)));
        List<BlockPos> route = DutyPlan.patrol(l, MoveRule.DEFAULT);
        assertEquals(MoveRule.DEFAULT.patrolPoints(), route.size());
    }

    @Test
    void scoutPostsAreOutsideTheVillageAndPerVillage() {
        Layout l = layout();
        ScoutRule s = ScoutRule.DEFAULT;
        List<BlockPos> posts = DutyPlan.scoutPosts(l, VILLAGE, s);
        assertEquals(s.posts(), posts.size());
        for (BlockPos p : posts) {
            assertEquals(l.radius() + s.distance(), Math.hypot(p.getX(), p.getZ()), 1.5);
        }
        assertEquals(posts, DutyPlan.scoutPosts(l, VILLAGE, s));
        assertNotEquals(posts, DutyPlan.scoutPosts(l, new UUID(123, 456), s));
    }

    @Test
    void sentryPairStandsEitherSideOfThePost() {
        DutyPlan p = DutyPlan.of(layout(), VILLAGE, MoveRule.DEFAULT, ScoutRule.DEFAULT);
        BlockPos a = p.sentrySpot(0, 0, BlockPos.ZERO, 4), b = p.sentrySpot(0, 1, BlockPos.ZERO, 4);
        assertNotEquals(a, b);
        assertTrue(a.distSqr(p.sentryPosts().get(0)) <= 9 && b.distSqr(p.sentryPosts().get(0)) <= 9);
    }

    @Test
    void layoutKeyChangesWithTheLayout() {
        Layout a = layout();
        List<BlockPos> more = new ArrayList<>(a.anchors());
        more.add(new BlockPos(1, 2, 3));
        Layout b = new Layout(a.center(), a.defendingPos(), a.townhall(), a.radius(), a.military(), more);
        assertNotEquals(a.key(), b.key());
        assertEquals(a.key(), layout().key());
    }

    // ---------------------------------------------------------------- progress

    RosterEntry entry(Duty d, int index) {
        RosterEntry e = new RosterEntry(new UUID(5, index + 10), "archer", "hundred_years_war:archer", 1, 0, true);
        e.assignedDuty = d;
        e.duty = d;
        e.dutyIndex = index;
        return e;
    }

    @Test
    void patrolAdvancesOnArrivalAfterThePauseAndOnTimeout() {
        DutyPlan p = DutyPlan.of(layout(), VILLAGE, MoveRule.DEFAULT, ScoutRule.DEFAULT);
        MoveRule m = MoveRule.DEFAULT;
        DutyMotion.Ctx c = new DutyMotion.Ctx(m, ScoutRule.DEFAULT, BlockPos.ZERO, true, 0, 1);
        RosterEntry e = entry(Duty.PATROL, 0);
        DutyMotion.start(e, p, c, 0);
        BlockPos wp0 = p.patrol().get(0);
        assertEquals(wp0, DutyMotion.goal(e, 1000, 1000, p, c, 10));
        // arrive
        DutyMotion.goal(e, wp0.getX() + 0.5, wp0.getZ() + 0.5, p, c, 20);
        assertEquals(1, e.dutyStep & 1);
        assertEquals(wp0, DutyMotion.goal(e, wp0.getX(), wp0.getZ(), p, c, 20 + m.patrolPause() - 1));
        assertEquals(p.patrol().get(1), DutyMotion.goal(e, wp0.getX(), wp0.getZ(), p, c, 20 + m.patrolPause()));
        // stuck far away: the leg times out
        long since = e.dutySince;
        DutyMotion.goal(e, 500, 500, p, c, since + DutyMotion.legTimeoutFor(MoveRule.DEFAULT, 800));
        assertEquals(2 * 2, e.dutyStep);
    }

    @Test
    void patrolSlotsStartSpreadOverTheLoop() {
        DutyPlan p = DutyPlan.of(layout(), VILLAGE, MoveRule.DEFAULT, ScoutRule.DEFAULT);
        Set<Integer> starts = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            starts.add(p.patrolStart(i, 3));
        }
        assertEquals(3, starts.size());
    }

    @Test
    void scoutRidesOutWatchesReturnsAndRests() {
        DutyPlan p = DutyPlan.of(layout(), VILLAGE, MoveRule.DEFAULT, ScoutRule.DEFAULT);
        ScoutRule s = ScoutRule.DEFAULT;
        DutyMotion.Ctx c = new DutyMotion.Ctx(MoveRule.DEFAULT, s, BlockPos.ZERO, true, 0, 0);
        RosterEntry e = entry(Duty.SCOUT, 1);
        DutyMotion.start(e, p, c, 0);
        assertEquals(DutyMotion.OUT, e.dutyStep);
        BlockPos post = p.scoutPost(1);
        assertEquals(post, DutyMotion.goal(e, 0, 0, p, c, 10));
        DutyMotion.goal(e, post.getX() + 0.5, post.getZ() + 0.5, p, c, 100);
        assertEquals(DutyMotion.DWELL, e.dutyStep);
        assertTrue(DutyMotion.scoutAway(e));
        assertEquals(p.scoutBase(), DutyMotion.goal(e, post.getX(), post.getZ(), p, c, 100 + s.dwell()));
        assertEquals(DutyMotion.BACK, e.dutyStep);
        DutyMotion.goal(e, p.scoutBase().getX(), p.scoutBase().getZ(), p, c, 200 + s.dwell());
        assertEquals(DutyMotion.REST, e.dutyStep);
        assertFalse(DutyMotion.scoutAway(e));
        assertEquals(post, DutyMotion.goal(e, 0, 0, p, c, 200 + s.dwell() + s.rest()));
        assertEquals(DutyMotion.OUT, e.dutyStep);
    }

    @Test
    void anAlertRecallsScoutsAtOnceAndKeepsThemHome() {
        DutyPlan p = DutyPlan.of(layout(), VILLAGE, MoveRule.DEFAULT, ScoutRule.DEFAULT);
        DutyMotion.Ctx alert = new DutyMotion.Ctx(MoveRule.DEFAULT, ScoutRule.DEFAULT, BlockPos.ZERO, false, 0, 0);
        RosterEntry e = entry(Duty.SCOUT, 0);
        e.dutyStep = DutyMotion.DWELL;
        e.dutySince = 0;
        assertEquals(p.scoutBase(), DutyMotion.goal(e, 500, 500, p, alert, 5));
        assertEquals(DutyMotion.BACK, e.dutyStep);
        e.dutyStep = DutyMotion.REST;
        e.dutySince = 0;
        assertEquals(p.scoutBase(), DutyMotion.goal(e, 0, 0, p, alert, 1_000_000), "no ride out while alerted");
    }

    @Test
    void blockedWayAheadEndsTheLeg() {
        DutyPlan p = DutyPlan.of(layout(), VILLAGE, MoveRule.DEFAULT, ScoutRule.DEFAULT);
        RosterEntry s = entry(Duty.SCOUT, 0);
        s.dutyStep = DutyMotion.OUT;
        DutyMotion.blocked(s, p, 50);
        assertEquals(DutyMotion.DWELL, s.dutyStep);
        RosterEntry pt = entry(Duty.PATROL, 0);
        pt.dutyStep = 0;
        DutyMotion.blocked(pt, p, 50);
        assertEquals(2, pt.dutyStep);
    }

    @Test
    void hopsAreAtMostMaxHop() {
        BlockPos goal = new BlockPos(100, 64, 0);
        BlockPos h = DutyMotion.hop(0.5, 64, 0.5, goal, 32);
        assertEquals(32, DutyMotion.horizontal(0.5, 0.5, h), 1.0);
        assertEquals(goal, DutyMotion.hop(80, 64, 0, goal, 32));
    }

    // ---------------------------------------------------------------- persistence and M2 interplay

    @Test
    void dutiesSurviveSaveAndLoad() {
        GarrisonRoster r = new GarrisonRoster(0);
        RosterEntry a = r.recruit(VILLAGE, "archer", "hundred_years_war:archer", 1, 0, true);
        RosterEntry b = r.recruit(VILLAGE, "militia", "hundred_years_war:militia", 0, 0, true);
        a.assignedDuty = Duty.SCOUT;
        a.duty = Duty.DEFENSE;
        a.dutyIndex = 2;
        a.dutyStep = DutyMotion.DWELL;
        a.dutySince = 777;
        CompoundTag t = r.save();
        GarrisonRoster back = GarrisonRoster.load(t, 1000);
        RosterEntry a2 = back.entry(a.rosterId), b2 = back.entry(b.rosterId);
        assertEquals(Duty.SCOUT, a2.assignedDuty);
        assertEquals(Duty.DEFENSE, a2.duty);
        assertEquals(2, a2.dutyIndex);
        assertEquals(DutyMotion.DWELL, a2.dutyStep);
        assertEquals(777, a2.dutySince);
        assertEquals(Duty.GARRISON, b2.assignedDuty);
        assertEquals(Duty.GARRISON, b2.duty);
        assertEquals(-1, b2.dutyIndex);
        assertFalse(t.getList("entries", 10).getCompound(1).contains("duty"), "default duty is not written (M3-compatible)");
    }

    @Test
    void m2DeploymentOverridesAndThenRestoresTheStandingDuty() {
        GarrisonRoster r = new GarrisonRoster(0);
        RosterEntry e = r.recruit(VILLAGE, "spear_man", "hundred_years_war:spear_man", 1, 0, true);
        r.beginSpawn(VILLAGE, e, 0);
        e.transition(UnitState.GARRISONED, 0);
        e.assignedDuty = Duty.SENTRY;
        e.duty = Duty.SENTRY;
        DefenseCoordinator.Pos anchor = new DefenseCoordinator.Pos(0, 64, 0);
        DefenseCoordinator.Pos post = new DefenseCoordinator.Pos(40, 64, 0);
        List<Deployment.UnitView> v = List.of(new Deployment.UnitView(e, new DefenseCoordinator.Pos(20, 64, 0), post));
        Deployment.apply(v, Map.of(e.entityUuid, new UUID(9, 9)), anchor, 10, 1200);
        assertEquals(UnitState.DEPLOYED, e.state());
        assertEquals(Duty.DEFENSE, e.duty);
        assertEquals(Duty.SENTRY, e.assignedDuty);
        Deployment.apply(v, Map.of(), anchor, 20, 1200);
        assertEquals(Duty.RETURNING, e.duty);
        // back at its sentry post (not at the anchor): garrisoned and on sentry duty again
        List<Deployment.UnitView> atPost = List.of(new Deployment.UnitView(e, new DefenseCoordinator.Pos(41, 64, 1), post));
        Deployment.apply(atPost, Map.of(), anchor, 30, 1200);
        assertEquals(UnitState.GARRISONED, e.state());
        assertEquals(Duty.SENTRY, e.duty);
    }
}

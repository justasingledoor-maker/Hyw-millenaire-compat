package dev.hywmill.politics.realm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The realm's council (docs/realm-design.md §10): stance, reserve, goals, the division into armies, ambition. */
class StrategyTest {
    static UUID id(int n) {
        return new UUID(0, n);
    }

    static Strategy.Member member(int n, int x, int men, boolean capital) {
        return new Strategy.Member(id(n), x, 0, men, men, capital, true, false, false);
    }

    static Strategy.Target target(int n, int x, double defense) {
        return new Strategy.Target(id(n), x, 0, defense, false, false, false, false, false, 1);
    }

    @Test
    void theStanceFollowsTheBalanceOfArms() {
        assertEquals(Strategy.Stance.AT_PEACE, Strategy.stance(100, 0, false));
        assertEquals(Strategy.Stance.AGGRESSIVE, Strategy.stance(150, 100, true));
        assertEquals(Strategy.Stance.BALANCED, Strategy.stance(80, 100, true));
        assertEquals(Strategy.Stance.DEFENSIVE, Strategy.stance(79, 100, true));
        assertTrue(Strategy.reserveShare(Strategy.Stance.DEFENSIVE) > Strategy.reserveShare(Strategy.Stance.BALANCED)
                && Strategy.reserveShare(Strategy.Stance.BALANCED) > Strategy.reserveShare(Strategy.Stance.AGGRESSIVE));
    }

    @Test
    void aStrongRealmDividesItsArmyAgainstTwoFrontsNearestMembersFirst() {
        // the capital and a province in the west, two vassals in the east; one weak enemy on each side
        List<Strategy.Member> ms = List.of(member(1, 0, 30, true), member(2, 200, 20, false), member(3, 2000, 20, false), member(4, 2200, 15, false));
        List<Strategy.Target> ts = List.of(target(10, -400, 15), target(11, 2600, 15));
        Strategy.Plan p = Strategy.plan(ms, ts, 30, true);
        assertEquals(Strategy.Stance.AGGRESSIVE, p.stance());
        assertEquals(2, p.armies().size(), "two armies: " + p.armies());
        Strategy.Army west = p.armies().stream().filter(a -> a.target().equals(id(10))).findFirst().orElseThrow();
        Strategy.Army east = p.armies().stream().filter(a -> a.target().equals(id(11))).findFirst().orElseThrow();
        assertTrue(west.members().contains(id(1)) && !west.members().contains(id(3)), "the west army is raised in the west: " + west);
        assertTrue(east.members().contains(id(3)) && !east.members().contains(id(1)), "the east army in the east: " + east);
        assertNotEquals(west.seat(), east.seat());
        assertTrue(west.power() >= west.needed() && east.power() >= east.needed());
        assertTrue(p.reserve() > 0, "some men are always held back");
    }

    @Test
    void aThreatenedMemberKeepsItsMenHomeAndATooStrongTargetWaits() {
        Strategy.Member threatened = new Strategy.Member(id(2), 200, 0, 20, 20, false, true, true, false);
        List<Strategy.Member> ms = List.of(member(1, 0, 30, true), threatened);
        Strategy.Plan p = Strategy.plan(ms, List.of(target(10, 500, 100)), 20, true);
        assertTrue(p.goals().stream().anyMatch(g -> g.kind() == Strategy.Kind.DEFEND && g.target().equals(id(2))));
        assertTrue(p.armies().isEmpty(), "100 behind walls is too much for what is left");
        Strategy.Goal waiting = p.goals().stream().filter(g -> g.target().equals(id(10))).findFirst().orElseThrow();
        assertFalse(waiting.ready());
        assertTrue(waiting.needed() > waiting.power());
        assertTrue(p.reserve() >= 20, "the threatened member's whole pledge is held back");
    }

    @Test
    void theCouncilGoesAfterWhatIsWorthMost() {
        Strategy.Target plain = target(10, 500, 10);
        Strategy.Target rebel = new Strategy.Target(id(11), 500, 0, 10, true, false, false, false, false, 1);
        Strategy.Target away = new Strategy.Target(id(12), 500, 0, 10, false, true, false, false, false, 1);
        List<Strategy.Member> ms = List.of(member(1, 0, 20, true), member(2, 100, 5, false), member(3, 150, 5, false));
        Strategy.Plan p = Strategy.plan(ms, List.of(plain, away, rebel), 5, true);
        assertEquals(id(11), p.armies().get(0).target(), "a rebel first");
        assertEquals(Strategy.Kind.RECLAIM, p.armies().get(0).kind());
        assertEquals(Strategy.Kind.STRIKE, Strategy.kind(away), "an enemy whose army is away: strike its home");
        assertTrue(Strategy.worth(away) > Strategy.worth(plain));
    }

    @Test
    void atPeaceNoArmyMarchesButAStrongRealmCovetsAVillageItHasAClaimOn() {
        List<Strategy.Member> ms = List.of(member(1, 0, 40, true), member(2, 200, 20, false));
        Strategy.Plan p = Strategy.plan(ms, List.of(target(10, 300, 5)), 0, false);
        assertEquals(Strategy.Stance.AT_PEACE, p.stance());
        assertTrue(p.armies().isEmpty());
        assertNull(Strategy.covet(ms, List.of(target(10, 300, 5))), "no claim, no war");
        Strategy.Target kin = new Strategy.Target(id(11), 300, 0, 10, false, false, false, true, false, 1);
        Strategy.Target strongKin = new Strategy.Target(id(12), 300, 0, 80, false, false, false, true, false, 1);
        assertEquals(id(11), Strategy.covet(ms, List.of(target(10, 300, 5), kin, strongKin)).id());
        assertNull(Strategy.covet(ms, List.of(strongKin)), "not one it could take");
    }
}

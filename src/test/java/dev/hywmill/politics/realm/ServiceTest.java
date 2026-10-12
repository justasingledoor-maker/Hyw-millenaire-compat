package dev.hywmill.politics.realm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A realm's service and its officials (docs/realm-design.md §11), and the council's counsel (§10-12). */
class ServiceTest {
    static final UUID HEAD = new UUID(7, 7), PLAYER = new UUID(1, 2);

    @Test
    void payFollowsTheRoleAndTheAnswer() {
        assertEquals(0, Service.wage(Service.Role.VOLUNTEER, 4, true));
        assertEquals(0, Service.wage(Service.Role.MERCENARY, 4, false), "no war, no wage");
        assertTrue(Service.wage(Service.Role.MERCENARY, 4, true) > 0);
        assertTrue(Service.wage(Service.Role.KNIGHT, 4, false) > Service.wage(Service.Role.MERCENARY, 4, true) - 1, "a knight's stipend runs in peace too");
        assertEquals(Service.Answer.OBEYED, Service.answer(true, true));
        assertEquals(Service.Answer.PRESENT, Service.answer(false, true));
        assertEquals(Service.Answer.ABSENT, Service.answer(false, false));
        assertTrue(Service.pay(Service.Role.KNIGHT, Service.Answer.OBEYED) > Service.pay(Service.Role.KNIGHT, Service.Answer.PRESENT));
        assertEquals(0, Service.pay(Service.Role.KNIGHT, Service.Answer.ABSENT));
        assertEquals(0, Service.pay(Service.Role.VOLUNTEER, Service.Answer.PRESENT), "a volunteer is paid only at the rally point");
        assertEquals("Samurai", Service.knightTitle("millenaire:japanese"));
        assertEquals("Basileus", Rulers.title("millenaire:byzantines"));
    }

    @Test
    void officialsAreTheSameForACapitalAndJudgeByTemper() {
        List<Officials.Official> a = Officials.raise(HEAD, "millenaire:norman"), b = Officials.raise(HEAD, "millenaire:norman");
        assertEquals(4, a.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(a.get(i).name, b.get(i).name);
            assertEquals(a.get(i).temper, b.get(i).temper);
        }
        assertEquals(4, a.stream().map(o -> o.name).distinct().count(), "four different men");
        Officials.Official greedy = new Officials.Official("G", Officials.Office.STEWARD, Officials.Temper.GREEDY);
        Officials.Official honest = new Officials.Official("H", Officials.Office.CHANCELLOR, Officials.Temper.HONOURABLE);
        assertTrue(Officials.bribe(greedy, PLAYER, 4096));
        assertEquals(30, greedy.of(PLAYER));
        assertFalse(Officials.bribe(honest, PLAYER, 4096), "an honourable man refuses");
        assertTrue(honest.of(PLAYER) < 0);
        Officials.Official hawk = new Officials.Official("M", Officials.Office.MARSHAL, Officials.Temper.HAWK);
        assertTrue(Officials.support(hawk, PLAYER, true) > Officials.support(hawk, PLAYER, false));
        assertTrue(Officials.marginFactor(List.of(hawk)) < 1, "a hawkish Marshal sends armies with less to spare");
    }

    @Test
    void obedienceAndBanishment() {
        List<Officials.Official> off = Officials.raise(HEAD, "millenaire:seljuk");
        Officials.answered(off, PLAYER, Service.Answer.OBEYED);
        assertTrue(Officials.average(off, PLAYER) >= 10);
        Officials.answered(off, PLAYER, Service.Answer.ABSENT);
        Officials.answered(off, PLAYER, Service.Answer.ABSENT);
        assertFalse(Officials.banish(2, Officials.average(off, PLAYER)), "two ignored orders are not yet enough");
        assertTrue(Officials.banish(3, Officials.average(off, PLAYER)), "three, with a council that thinks little of him");
        assertFalse(Officials.banish(3, 40), "a council that thinks well of him keeps him");
        assertTrue(Officials.banish(0, -60), "a despised knight goes");
    }

    @Test
    void theVoteIsAMajorityWithATieBreaker() {
        List<Officials.Official> four = List.of(new Officials.Official("a", Officials.Office.MARSHAL, Officials.Temper.HAWK),
                new Officials.Official("b", Officials.Office.CHANCELLOR, Officials.Temper.HAWK),
                new Officials.Official("c", Officials.Office.STEWARD, Officials.Temper.HAWK),
                new Officials.Official("d", Officials.Office.SPYMASTER, Officials.Temper.HAWK));
        four.forEach(o -> o.change(PLAYER, 100));
        assertTrue(Officials.vote(four, PLAYER, true, 1).passed(), "hawks who like him back his war");
        four.forEach(o -> o.change(PLAYER, -200));
        int passed = 0;
        for (long seed = 0; seed < 50; seed++) {
            passed += Officials.vote(four, PLAYER, false, seed).passed() ? 1 : 0;
        }
        assertTrue(passed < 10, "hawks who despise him seldom back his peace: " + passed);
    }

    @Test
    void theCouncilCountsItsAlliesAndWhatItsKnightsUrge() {
        List<Strategy.Member> ms = List.of(new Strategy.Member(new UUID(0, 1), 0, 0, 20, 20, true, true, false, false),
                new Strategy.Member(new UUID(0, 2), 100, 0, 10, 10, false, true, false, false));
        Strategy.Target strong = new Strategy.Target(new UUID(0, 10), 400, 0, 40, false, false, false, false, false, 1);
        Strategy.Target plain = new Strategy.Target(new UUID(0, 11), 300, 0, 10, false, false, false, false, false, 1);
        assertTrue(Strategy.plan(ms, List.of(strong), 5, true).armies().isEmpty(), "24 men's strength is not enough alone");
        Strategy.Plan withAllies = Strategy.plan(ms, List.of(strong), 5, true,
                new Strategy.Counsel(1, 0, Map.of(strong.id(), 20.0), Set.of()));
        assertEquals(1, withAllies.armies().size(), "with its allies' contingent it is: " + withAllies.goals());
        Strategy.Target firmer = new Strategy.Target(new UUID(0, 12), 400, 0, 15, false, false, false, false, false, 1);
        assertEquals(plain.id(), Strategy.plan(ms, List.of(plain, firmer), 5, true).goals().get(0).target(), "the weaker first, left to itself");
        Strategy.Plan urged = Strategy.plan(ms, List.of(plain, firmer), 5, true, new Strategy.Counsel(1, 0, Map.of(), Set.of(firmer.id())));
        assertEquals(firmer.id(), urged.goals().get(0).target(), "the target its knights urged comes first");
        assertNotEquals(Strategy.plan(ms, List.of(plain), 5, true).reserve(),
                Strategy.plan(ms, List.of(plain), 5, true, new Strategy.Counsel(1, 0.3, Map.of(), Set.of())).reserve(), "a cautious council holds more back");
    }
}

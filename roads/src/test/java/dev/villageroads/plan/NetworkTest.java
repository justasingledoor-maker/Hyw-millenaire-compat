package dev.villageroads.plan;

import dev.villageroads.plan.Network.Link;
import dev.villageroads.plan.Network.Place;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkTest {
    static boolean linked(List<Link> ls, String a, String b) {
        return ls.stream().anyMatch(l -> Network.key(l.a().id(), l.b().id()).equals(Network.key(a, b)));
    }

    @Test
    void neighboursAreLinkedButNotPastAVillageBetween() {
        // three in a row: A - B - C; and D off to the side of B
        List<Place> ps = List.of(new Place("A", 0, 0), new Place("B", 300, 0), new Place("C", 600, 0), new Place("D", 300, 280));
        List<Link> ls = Network.links(ps, 700);
        assertTrue(linked(ls, "A", "B"));
        assertTrue(linked(ls, "B", "C"));
        assertTrue(linked(ls, "B", "D"));
        assertTrue(!linked(ls, "A", "C"), "A goes to C by way of B");
        assertEquals(ls.get(0).distance(), Math.min(ls.get(0).distance(), ls.get(ls.size() - 1).distance()), "nearest first");
    }

    @Test
    void tooFarIsNotLinked() {
        List<Link> ls = Network.links(List.of(new Place("A", 0, 0), new Place("B", 900, 0)), 700);
        assertTrue(ls.isEmpty());
    }

    @Test
    void theKeyIsTheSameEitherWay() {
        assertEquals(Network.key("x", "y"), Network.key("y", "x"));
    }
}

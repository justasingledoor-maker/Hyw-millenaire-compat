package dev.hywmill.politics.realm;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Post-M5 realms (docs/realm-design.md): treaties, war sides, siege aims, loyalty, province levies. */
class RealmRulesTest {
    static final UUID X = new UUID(0, 1), Y = new UUID(0, 2), C = new UUID(0, 3);

    @Test
    void treatyTiersOpenAtTheirThresholdsAndLapseTenBelow() {
        assertNull(Treaty.qualifies(24));
        assertEquals(Treaty.Kind.PACT, Treaty.qualifies(25));
        assertEquals(Treaty.Kind.DEFENSIVE, Treaty.qualifies(50));
        assertEquals(Treaty.Kind.DEFENSIVE, Treaty.qualifies(74));
        assertEquals(Treaty.Kind.ALLIANCE, Treaty.qualifies(75));
        assertEquals(Treaty.Kind.ALLIANCE, Treaty.standing(Treaty.Kind.ALLIANCE, 65), "an alliance holds down to 65");
        assertEquals(Treaty.Kind.DEFENSIVE, Treaty.standing(Treaty.Kind.ALLIANCE, 64), "then falls back to a defensive pact");
        assertEquals(Treaty.Kind.PACT, Treaty.standing(Treaty.Kind.ALLIANCE, 30));
        assertNull(Treaty.standing(Treaty.Kind.PACT, 14), "a pact ends below 15");
        Treaty t = new Treaty(Y, X, Treaty.Kind.DEFENSIVE, 0);
        assertEquals(X, t.a, "ordered pair");
        assertEquals(Treaty.key(X, Y), t.key());
        assertTrue(t.obligesRelief());
        assertFalse(t.military());
        assertFalse(new Treaty(X, Y, Treaty.Kind.PACT, 0).obligesRelief());
        assertTrue(new Treaty(X, Y, Treaty.Kind.ALLIANCE, 0).military());
    }

    @Test
    void boundToOneSideJoinsIt() {
        var d = WarSides.decide(X, Y, List.of(new WarSides.Bond(C, WarSides.Tie.NONE, 0, false, WarSides.Tie.ALLIANCE, 80, false)));
        assertEquals(1, d.size());
        assertEquals(Y, d.get(0).side());
        assertEquals(X, d.get(0).enemy());
        assertNull(d.get(0).brokenWith());
        assertTrue(WarSides.decide(X, Y, List.of(new WarSides.Bond(C, WarSides.Tie.NONE, 90, true, WarSides.Tie.NONE, 90, true))).isEmpty(),
                "no obliging bond: it stays out");
    }

    @Test
    void boundToBothSidesWithTheStrongerBondAndBreaksTheOther() {
        // a province of X allied to Y stands by its sovereign, whatever its friendship with Y
        var d = WarSides.decide(X, Y, List.of(new WarSides.Bond(C, WarSides.Tie.PROVINCE, 0, false, WarSides.Tie.ALLIANCE, 100, true)));
        assertEquals(X, d.get(0).side());
        assertEquals(Y, d.get(0).brokenWith());
        // two alliances: the warmer friendship and the shared culture decide
        d = WarSides.decide(X, Y, List.of(new WarSides.Bond(C, WarSides.Tie.ALLIANCE, 80, false, WarSides.Tie.ALLIANCE, 76, true)));
        assertEquals(Y, d.get(0).side(), "3.76 + 0.3 beats 3.80");
        assertEquals(X, d.get(0).brokenWith());
    }

    static SiegeAims.Facts facts(int at, int tt, boolean same, double dist, int grudge, boolean rebelled, int prov, boolean raze) {
        return new SiegeAims.Facts(at, tt, same, dist, grudge, rebelled, prov, SiegeAims.capacity(at), raze, true);
    }

    @Test
    void aimsFollowTheSituation() {
        Map<SiegeAims.Aim, Double> w = SiegeAims.weights(facts(3, 2, true, 400, 0, false, 0, true));
        assertEquals(6.0, w.get(SiegeAims.Aim.ANNEX), "a near neighbour of one culture is ripe for annexing");
        assertEquals(0.0, w.get(SiegeAims.Aim.RAZE), "no grudge, no razing");
        assertEquals(0.0, SiegeAims.weights(facts(2, 3, true, 400, 0, false, 0, true)).get(SiegeAims.Aim.ANNEX), "never a stronger target");
        assertEquals(0.0, SiegeAims.weights(facts(1, 1, false, 900, 0, false, 3, true)).get(SiegeAims.Aim.ANNEX), "over capacity");
        assertEquals(6.0, SiegeAims.weights(facts(3, 2, false, 400, 1, true, 0, true)).get(SiegeAims.Aim.RAZE), "a rebel: (1 + grudge) x 3");
        assertEquals(1.2, SiegeAims.weights(facts(4, 3, false, 400, 3, false, 0, true)).get(SiegeAims.Aim.RAZE), 1e-9, "a garrison-tier target: x 0.3");
        assertEquals(0.0, SiegeAims.weights(facts(3, 2, false, 400, 3, true, 0, false)).get(SiegeAims.Aim.RAZE), "razing switched off");
        assertEquals(Map.of(SiegeAims.Aim.SUBJUGATE, 6.0), SiegeAims.weights(new SiegeAims.Facts(3, 2, false, 900, 3, true, 0, 3, true, false)),
                "realms off: always subjugate");
    }

    @Test
    void aimDrawIsStableAndCoversTheWeights() {
        SiegeAims.Facts f = facts(3, 2, true, 400, 2, false, 0, true);
        assertEquals(SiegeAims.choose(f, 42), SiegeAims.choose(f, 42));
        Map<SiegeAims.Aim, Integer> n = new EnumMap<>(SiegeAims.Aim.class);
        for (long s = 0; s < 4000; s++) {
            n.merge(SiegeAims.choose(f, s * 0x9E3779B97F4A7C15L), 1, Integer::sum);
        }
        Map<SiegeAims.Aim, Double> w = SiegeAims.weights(f);
        double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
        for (SiegeAims.Aim a : SiegeAims.Aim.values()) {
            double expect = w.get(a) / total;
            assertEquals(expect, n.getOrDefault(a, 0) / 4000.0, 0.03, a + " drawn about as often as weighted");
        }
    }

    @Test
    void loyaltyDriftsAndReacts() {
        assertEquals(52, Loyalty.daily(50, true, true, 100), 1e-9, "a province drifts up towards 60");
        assertEquals(51, Loyalty.daily(50, true, false, 1500), 1e-9, "less for a foreign, far province");
        assertEquals(50, Loyalty.daily(50, false, true, 100), 1e-9, "a vassal rests at 50");
        assertEquals(68, Loyalty.daily(70, false, true, 100), 1e-9, "and drifts down to it, 2 a day");
        assertEquals(-15, Loyalty.losses(100), 1e-9, "at most 15 a siege");
        assertEquals(-2, Loyalty.losses(4), 1e-9);
        assertEquals(0, Loyalty.rebelChance(30, false, false), 1e-9);
        assertEquals(0.25, Loyalty.rebelChance(0, false, false), 1e-9);
        assertEquals(0.06, Loyalty.rebelChance(0, true, false), 1e-9, "a province rebels about a quarter as often");
        assertEquals(0.12, Loyalty.rebelChance(0, true, true), 1e-9, "doubled under a weaker sovereign");
        assertEquals(0, Loyalty.clamp(-5), 1e-9);
        assertEquals(100, Loyalty.clamp(120), 1e-9);
    }

    @Test
    void provinceLeviesAndRecruits() {
        assertEquals(0, Provinces.levy(0, 0.5, true));
        assertEquals(30, Provinces.levy(100, 0, true));
        assertEquals(50, Provinces.levy(100, 1, true));
        assertEquals(20, Provinces.levy(100, 0, false));
        assertEquals(35, Provinces.levy(100, 1, false));
        assertEquals(1, Provinces.levy(1, 0, false), "at least one man");
        UUID v = UUID.randomUUID();
        int sov = 0;
        for (int seq = 0; seq < 2000; seq++) {
            sov += Provinces.sovereignPick(v, seq) ? 1 : 0;
        }
        assertEquals(Provinces.SOVEREIGN_SHARE, sov / 2000.0, 0.04, "seven recruits in ten are the sovereign's people");
    }
}

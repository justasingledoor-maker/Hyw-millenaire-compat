package dev.hywmill.military.doctrine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hywmill.military.MilitaryTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2-3: the inheritance chain and exact values, using the shipped defaults.json. */
class DoctrineResolverTest {
    static DoctrineDefaults D;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = DoctrineResolverTest.class.getResourceAsStream("/data/hywmill/hywmill_doctrine/defaults.json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> problems = new ArrayList<>();
            D = DoctrineDefaults.fromJson(List.of(o), problems);
            assertTrue(problems.isEmpty(), problems.toString());
        }
    }

    static DoctrineResolver.Resolved r(String type, int radius, MilitaryTier tier) {
        return r(type, radius, false, tier, null);
    }

    static DoctrineResolver.Resolved r(String type, int radius, boolean lone, MilitaryTier tier, DoctrinePatch override) {
        String culture = type.substring(0, type.indexOf('/'));
        return DoctrineResolver.resolve(D, new DoctrineResolver.Context(culture, type, radius, lone, tier), override);
    }

    @Test
    void baselineNormanVillage() {
        DoctrineResolver.Resolved res = r("millenaire:norman/agricole", 90, MilitaryTier.WATCH);
        Doctrine d = res.doctrine();
        assertEquals(106, d.defenseRadius());
        assertFalse(d.proactive());
        assertEquals(3, d.commitPerThreat());
        assertEquals(1, d.reserve());
        assertEquals(MilitiaPolicy.ON_ENGAGED, d.militiaPolicy());
        assertEquals(48, d.shelterRadius());
        assertEquals(AssistMode.MIN_REPUTATION, d.assistPlayers());
        assertEquals(0, d.assistMinReputation());
        assertFalse(d.assistProvokingPlayer());
        assertEquals(ControllerAssist.ALWAYS, d.assistController());
        assertEquals(100, d.alertTicks());
        assertEquals(200, d.engagedTicks());
        assertEquals(600, d.recoveryTicks());
        assertEquals("baseline", res.sourceOf(DoctrineField.RESERVE));
    }

    @Test
    void cultureLayers() {
        Doctrine byz = r("millenaire:byzantines/tradingvillage", 90, MilitaryTier.GUARD_POST).doctrine();
        assertEquals(106, byz.defenseRadius());
        assertEquals(2, byz.reserve());
        assertEquals(MilitiaPolicy.WHEN_ATTACKED, byz.militiaPolicy());
        assertEquals(256, byz.assistMinReputation());
        assertEquals(1200, byz.recoveryTicks());

        Doctrine selj = r("millenaire:seljuk/trading_village_seljuks", 90, MilitaryTier.WATCH).doctrine();
        assertEquals(114, selj.defenseRadius());

        Doctrine jap = r("millenaire:japanese/nogyo", 90, MilitaryTier.WATCH).doctrine();
        assertEquals(2, jap.commitPerThreat());
        assertEquals(32, jap.shelterRadius());

        Doctrine may = r("millenaire:mayan/agriculture", 90, MilitaryTier.WATCH).doctrine();
        assertEquals(4, may.commitPerThreat());
        assertEquals(0, may.reserve());
        assertEquals(MilitiaPolicy.ALWAYS, may.militiaPolicy());
        assertEquals(256, may.assistMinReputation());

        Doctrine inu = r("millenaire:inuits/huntingvillage", 90, MilitaryTier.WATCH).doctrine();
        assertEquals(Doctrine.VILLAGE_WIDE, inu.shelterRadius());
        assertEquals(400, inu.recoveryTicks());
        assertEquals(MilitiaPolicy.ALWAYS, inu.militiaPolicy());
    }

    @Test
    void villageTypeAndTier() {
        DoctrineResolver.Resolved mil = r("millenaire:norman/militaire", 90, MilitaryTier.GARRISON);
        assertEquals(4, mil.doctrine().commitPerThreat());
        assertEquals("baseline + tier GARRISON", mil.sourceOf(DoctrineField.COMMIT_PER_THREAT));
        assertEquals(2, mil.doctrine().reserve());
        assertEquals("village type millenaire:norman/militaire", mil.sourceOf(DoctrineField.RESERVE));
        assertEquals(MilitiaPolicy.WHEN_ATTACKED, mil.doctrine().militiaPolicy());

        Doctrine strong = r("millenaire:norman/militaire", 90, MilitaryTier.STRONGHOLD).doctrine();
        assertEquals(4, strong.commitPerThreat());
        assertEquals(3, strong.reserve());
        assertEquals(122, strong.defenseRadius());

        assertEquals(0, r("millenaire:norman/militaire", 90, MilitaryTier.NONE).doctrine().reserve());

        Doctrine hamlet = r("millenaire:norman/hameau_agricole", 50, MilitaryTier.WATCH).doctrine();
        assertEquals(66, hamlet.defenseRadius());
        assertEquals(2, hamlet.commitPerThreat());
        assertEquals(0, hamlet.reserve());
        assertEquals(Doctrine.VILLAGE_WIDE, hamlet.shelterRadius());

        Doctrine nd = r("millenaire:norman/notredame", 140, MilitaryTier.GUARD_POST).doctrine();
        assertEquals(140, nd.defenseRadius());
        assertEquals(2, nd.commitPerThreat());

        Doctrine fort = r("millenaire:indian/fort", 90, MilitaryTier.GUARD_POST).doctrine();
        assertEquals(2, fort.reserve());
        assertEquals(MilitiaPolicy.WHEN_ATTACKED, fort.militiaPolicy());
    }

    @Test
    void globIsSpecificAndOnlyMatchesItsPattern() {
        Doctrine small = r("millenaire:seljuk/agriculture_small_seljuks", 70, MilitaryTier.WATCH).doctrine();
        assertEquals(94, small.defenseRadius());
        assertEquals(2, small.commitPerThreat());
        assertEquals(0, small.reserve());
        assertEquals("millenaire:seljuk/*_small_seljuks", D.villageTypeKey("millenaire:seljuk/artisans_small_seljuks"));
        assertNull(D.villageTypeKey("millenaire:seljuk/controlled_small_village_seljuks"));
        assertEquals("millenaire:seljuk/controlled_fort_seljuks", D.villageTypeKey("millenaire:seljuk/controlled_fort_seljuks"));
    }

    @Test
    void loneBuildingsAndClamp() {
        Doctrine lone = r("millenaire:norman/banditlair", 10, true, MilitaryTier.NONE, null).doctrine();
        assertEquals(40, lone.defenseRadius(), "10 + 16 clamped up to 40");
        assertEquals(2, lone.commitPerThreat());
        assertEquals(0, lone.reserve());
        assertEquals(Doctrine.VILLAGE_WIDE, lone.shelterRadius());
        Doctrine big = r("millenaire:norman/agricole", 90, false, MilitaryTier.WATCH,
                DoctrinePatch.EMPTY.with(DoctrineField.RADIUS_OFFSET, 100)).doctrine();
        assertEquals(160, big.defenseRadius());
    }

    @Test
    void overrideIsLastAndAbsolute() {
        DoctrinePatch o = DoctrinePatch.EMPTY
                .with(DoctrineField.RESERVE, 0)
                .with(DoctrineField.PROACTIVE, true)
                .with(DoctrineField.MILITIA_POLICY, MilitiaPolicy.NEVER);
        DoctrineResolver.Resolved res = r("millenaire:norman/militaire", 90, false, MilitaryTier.STRONGHOLD, o);
        assertEquals(0, res.doctrine().reserve(), "override beats the tier's +1");
        assertTrue(res.doctrine().proactive());
        assertEquals(MilitiaPolicy.NEVER, res.doctrine().militiaPolicy());
        assertEquals("override", res.sourceOf(DoctrineField.RESERVE));
        assertEquals(4, res.doctrine().commitPerThreat(), "not overridden: still tier-adjusted");
    }

    @Test
    void everyFieldParsesItsFormattedValue() {
        Doctrine d = r("millenaire:norman/agricole", 90, MilitaryTier.WATCH).doctrine();
        d.asMap().forEach((f, v) -> assertEquals(v, f.parse(DoctrineField.format(v)), f.key));
    }

    @Test
    void invalidJsonIsReported() {
        JsonObject bad = JsonParser.parseString("{\"baseline\":{\"commitPerThreat\":0,\"bogus\":1},"
                + "\"cultures\":{\"x:y\":{\"militiaPolicy\":\"SOMETIMES\"}},\"tiers\":{\"MEGA\":{}}}").getAsJsonObject();
        List<String> problems = new ArrayList<>();
        DoctrineDefaults d = DoctrineDefaults.fromJson(List.of(bad), problems);
        assertEquals(4, problems.size(), problems.toString());
        assertEquals(3, d.baseline().values().get(DoctrineField.COMMIT_PER_THREAT), "invalid value falls back to built-in");
    }
}

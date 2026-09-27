package dev.hywmill.politics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoliticsDataTest {
    static final Path FILE = Path.of("src/main/resources/data/hywmill/hywmill_politics/defaults.json");

    @Test
    void shippedDefaultsEqualTheCodeDefaults() throws Exception {
        List<String> problems = new ArrayList<>();
        PoliticsData d = PoliticsData.fromJson(List.of(JsonParser.parseString(Files.readString(FILE)).getAsJsonObject()), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(PoliticsTables.DEFAULTS, d.defaults());
        assertEquals(28 * PoliticsTables.DAY, d.forCulture("millenaire:seljuk").grievance().halfLifeTicks());
        assertEquals(120.0, d.forCulture("millenaire:japanese").grievance().weight(GrievanceKind.KILL_RESIDENT));
        assertEquals(80.0, d.forCulture("millenaire:japanese").grievance().weight(GrievanceKind.KILL_GARRISON), "unpatched fields inherit");
        assertEquals(PoliticsTables.DEFAULTS, d.forCulture("millenaire:norman"));
    }

    @Test
    void badDataIsReportedAndIgnored() {
        List<String> problems = new ArrayList<>();
        JsonObject f = JsonParser.parseString("""
                {"defaults": {"favor": {"sources": {"TRADE": 5}}, "grievance": {"halfLifeDays": 0, "weights": {"NOPE": 1}},
                 "standing": {"keepFactor": 2}}}""").getAsJsonObject();
        PoliticsData d = PoliticsData.fromJson(List.of(f), problems);
        assertEquals(4, problems.size(), problems.toString());
        assertEquals(PoliticsTables.DEFAULTS, d.defaults());
    }

    @Test
    void pardonRuleParsesAndRejectsNegativePrices() {
        List<String> problems = new ArrayList<>();
        JsonObject f = JsonParser.parseString("""
                {"defaults": {"pardon": {"perGrievance": 48}},
                 "cultures": {"millenaire:inuits": {"pardon": {"enabled": false}}, "millenaire:mayan": {"pardon": {"killFee": -5}}}}""").getAsJsonObject();
        PoliticsData d = PoliticsData.fromJson(List.of(f), problems);
        assertEquals(new PoliticsTables.PardonRule(true, 48, 1024), d.defaults().pardon());
        assertEquals(new PoliticsTables.PardonRule(false, 48, 1024), d.forCulture("millenaire:inuits").pardon());
        assertEquals(d.defaults().pardon(), d.forCulture("millenaire:mayan").pardon());
        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    void deferredEscortKeysAreReportedAndIgnored() {
        List<String> problems = new ArrayList<>();
        JsonObject f = JsonParser.parseString("""
                {"defaults": {"requests": {"escortMax": {"TRUSTED": 2}, "escortTicks": 24000, "detachRadius": 128}}}""").getAsJsonObject();
        PoliticsData d = PoliticsData.fromJson(List.of(f), problems);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.stream().allMatch(p -> p.contains("deferred")));
        assertEquals(128, d.defaults().requests().detachRadius());
    }
}

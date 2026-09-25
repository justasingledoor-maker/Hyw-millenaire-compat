package dev.hywmill.classify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The role tables shipped in the mod jar parse cleanly and contain the entries the tier depends on. */
class ShippedRoleTablesTest {
    private static final List<String> CULTURES = List.of("byzantines", "indian", "inuits", "japanese", "mayan", "norman", "seljuk");

    @Test
    void allShippedFilesAreValid() throws Exception {
        List<JsonObject> files = new ArrayList<>();
        for (String c : CULTURES) {
            try (InputStream in = getClass().getResourceAsStream("/data/hywmill/hywmill_roles/" + c + ".json")) {
                assertNotNull(in, c);
                files.add(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        }
        List<String> problems = new ArrayList<>();
        RoleTable table = RoleTable.merge(files, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(VillagerRole.SOLDIER, table.villagers().get("millenaire:norman/guard"));
        assertEquals(VillagerRole.LEADER, table.villagers().get("millenaire:norman/knight"));
        assertEquals(BuildingRole.GUARDHOUSE, table.buildings().get("millenaire:norman/guardhouse"));
        assertEquals(BuildingRole.FORT_TOWNHALL, table.buildings().get("millenaire:norman/fort"));
        assertTrue(table.villagers().values().stream().noneMatch(r -> r == VillagerRole.OUTLAW));
    }

    @Test
    void invalidEntriesAreReportedNotApplied() {
        JsonObject f = JsonParser.parseString("{\"villagers\":{\"a:b\":\"OUTLAW\",\"a:c\":\"soldier\",\"a:d\":\"GENERAL\"},"
                + "\"buildings\":{\"a:e\":\"castle\",\"a:f\":\"none\"}}").getAsJsonObject();
        List<String> problems = new ArrayList<>();
        RoleTable t = RoleTable.merge(List.of(f), problems);
        assertEquals(3, problems.size(), problems.toString());
        assertEquals(VillagerRole.SOLDIER, t.villagers().get("a:c"));
        assertEquals(BuildingRole.NONE, t.buildings().get("a:f"));
    }

    @Test
    void laterFilesOverride() {
        JsonObject a = JsonParser.parseString("{\"villagers\":{\"x:y\":\"MILITIA\"}}").getAsJsonObject();
        JsonObject b = JsonParser.parseString("{\"villagers\":{\"x:y\":\"SOLDIER\"}}").getAsJsonObject();
        assertEquals(VillagerRole.SOLDIER, RoleTable.merge(List.of(a, b), new ArrayList<>()).villagers().get("x:y"));
    }
}

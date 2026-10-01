package dev.hywmill.recruit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.politics.Standing;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 culture squads: the shipped catalogue and the rules. */
class SquadsTest {
    static final Set<String> CULTURES = Set.of("millenaire:norman", "millenaire:byzantines", "millenaire:seljuk", "millenaire:japanese",
            "millenaire:indian", "millenaire:mayan", "millenaire:inuits");

    static GarrisonTables garrison() throws IOException {
        Path dir = Path.of("src/main/resources/data/hywmill/hywmill_garrison");
        List<JsonObject> f = List.of(JsonParser.parseString(Files.readString(dir.resolve("defaults.json"))).getAsJsonObject(),
                JsonParser.parseString(Files.readString(dir.resolve("units.json"))).getAsJsonObject());
        return GarrisonTables.fromJson(f, id -> dev.hywmill.garrison.Recruitment.allowedType(id), new ArrayList<>());
    }

    static Squads shipped(List<String> problems) throws IOException {
        JsonObject j = JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/hywmill/hywmill_squads/defaults.json")))
                .getAsJsonObject();
        return Squads.fromJson(List.of(j), garrison().units(), problems);
    }

    @Test
    void everyCultureHasSixteenSquadsOfTheAgreedShape() throws IOException {
        List<String> problems = new ArrayList<>();
        Squads s = shipped(problems);
        assertEquals(List.of(), problems);
        assertEquals(CULTURES, s.cultures().keySet());
        for (String c : CULTURES) {
            List<Squads.Squad> l = s.forCulture(c);
            assertEquals(16, l.size(), c);
            Map<Squads.Category, Long> cats = l.stream().collect(Collectors.groupingBy(Squads.Squad::category, Collectors.counting()));
            assertEquals(Map.of(Squads.Category.INFANTRY, 6L, Squads.Category.RANGED, 4L, Squads.Category.CAVALRY, 4L, Squads.Category.UNIQUE, 2L),
                    cats, c);
            Map<Squads.Quality, Long> inf = l.stream().filter(q -> q.category() == Squads.Category.INFANTRY)
                    .collect(Collectors.groupingBy(Squads.Squad::quality, Collectors.counting()));
            assertEquals(Map.of(Squads.Quality.LOW, 2L, Squads.Quality.MEDIUM, 2L, Squads.Quality.HIGH, 2L), inf, c);
            for (Squads.Squad q : l) {
                assertTrue(q.size() >= 8 && q.size() <= 12, q.id() + " " + q.size());
                assertEquals(1, l.stream().filter(o -> o.id().equals(q.id())).count(), "unique id " + q.id());
            }
        }
        assertEquals(s.forCulture("millenaire:norman"), s.forCulture("millenaire:unknown"), "unknown cultures fall back to the defaults");
    }

    @Test
    void horseCulturesFieldHorseAndNoSquadCarriesSiegeEngines() throws IOException {
        Squads s = shipped(new ArrayList<>());
        Set<String> horses = Set.of("light_lancer_rider", "archer_rider", "lancer_rider");
        for (String c : List.of("millenaire:norman", "millenaire:byzantines", "millenaire:seljuk", "millenaire:japanese", "millenaire:indian")) {
            for (Squads.Squad q : s.forCulture(c)) {
                if (q.category() == Squads.Category.CAVALRY) {
                    assertTrue(q.members().stream().allMatch(m -> horses.contains(m.unit())), q.id());
                }
            }
        }
        for (String c : List.of("millenaire:mayan", "millenaire:inuits")) {
            assertTrue(s.forCulture(c).stream().flatMap(q -> q.members().stream()).noneMatch(m -> horses.contains(m.unit())), c + " had no horses");
        }
        assertTrue(s.forCulture("millenaire:seljuk").stream().anyMatch(q -> q.name().contains("Cataphract")));
        assertTrue(s.forCulture("millenaire:seljuk").stream().anyMatch(q -> q.name().contains("Horse Archers")));
        assertTrue(s.forCulture("millenaire:norman").stream().anyMatch(q -> q.name().contains("Knights")
                && q.members().stream().allMatch(m -> m.unit().equals("lancer_rider") && m.gear() == MilitaryTier.STRONGHOLD)));
        Set<String> units = s.cultures().values().stream().flatMap(List::stream).flatMap(q -> q.members().stream()).map(Squads.Member::unit)
                .collect(Collectors.toSet());
        assertTrue(units.stream().noneMatch(u -> u.contains("mangonel") || u.contains("trebuchet") || u.contains("ram") || u.contains("engineer")),
                units.toString());
        long militia = s.cultures().values().stream().flatMap(List::stream).flatMap(q -> q.members().stream())
                .filter(m -> m.unit().equals("militia")).count();
        assertTrue(militia <= 1, "militia are few and far between: " + militia);
    }

    @Test
    void priceIsTheMembersLessTheSquadDiscountThenStanding() throws IOException {
        Squads s = shipped(new ArrayList<>());
        GarrisonTables g = garrison();
        RecruitTables t = RecruitTables.DEFAULT;
        Squads.Squad knights = s.find("millenaire:norman", "norman.knights");
        // 8 lancers (cost 6) at STRONGHOLD: 8 x (0.5 + 1.5) x 1.5 = 24 or, less 10% = 21.6 or = 1382.4 argent -> 1382 argent
        assertEquals(1382 * RecruitTables.DENIER_ARGENT, s.price(knights, g.units(), t, Standing.TRUSTED));
        Squads.Squad fyrd = s.find("millenaire:norman", "norman.fyrd_spearmen");
        int trusted = s.price(fyrd, g.units(), t, Standing.TRUSTED);
        // 10 spearmen (cost 2) at GUARD_POST: 10 x 1.0 x 1.0 = 10 or, less 10% = 9 or
        assertEquals(9 * RecruitTables.DENIER_OR, trusted);
        assertTrue(s.price(fyrd, g.units(), t, Standing.PATRON) < trusted, "the standing discount applies");
        int single = 10 * (int) Math.round((t.soldierBase() + t.soldierPerCost() * 2) * RecruitTables.DENIER_OR);
        assertTrue(trusted < single, "a squad is cheaper than its soldiers one by one");
    }

    @Test
    void requirementsFollowQualityStandingAndVillage() throws IOException {
        Squads s = shipped(new ArrayList<>());
        Squads.Squad low = s.find("millenaire:norman", "norman.fyrd_spearmen");
        Squads.Squad high = s.find("millenaire:norman", "norman.knights");
        assertNull(Squads.refusal(low, Standing.TRUSTED, MilitaryTier.WATCH));
        assertNotNull(Squads.refusal(low, Standing.STRANGER, MilitaryTier.STRONGHOLD));
        assertNotNull(Squads.refusal(low, Standing.OUTLAW, MilitaryTier.STRONGHOLD));
        assertNotNull(Squads.refusal(high, Standing.PATRON, MilitaryTier.STRONGHOLD), "high squads want a sworn friend");
        assertNotNull(Squads.refusal(high, Standing.SWORN, MilitaryTier.GUARD_POST), "and a garrison-sized village");
        assertNull(Squads.refusal(high, Standing.SWORN, MilitaryTier.GARRISON));
    }

    @Test
    void badSquadsAreReportedAndSkipped() {
        JsonObject j = JsonParser.parseString("""
                { "cultures": { "x": [
                  { "id": "tiny", "name": "Tiny", "category": "INFANTRY", "quality": "LOW", "members": [ { "unit": "spear_man", "count": 3, "gear": "WATCH" } ] },
                  { "id": "ghost", "name": "Ghost", "category": "INFANTRY", "quality": "LOW", "members": [ { "unit": "ghoul", "count": 10, "gear": "WATCH" } ] },
                  { "id": "ok", "name": "Ok", "category": "RANGED", "quality": "MEDIUM", "members": [ { "unit": "archer", "count": 10, "gear": "GARRISON" } ] } ] } }""")
                .getAsJsonObject();
        List<String> problems = new ArrayList<>();
        Squads s;
        try {
            s = Squads.fromJson(List.of(j), garrison().units(), problems);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertEquals(List.of("ok"), s.forCulture("x").stream().map(Squads.Squad::id).toList());
        assertEquals(2, problems.size(), problems.toString());
    }

    @Test
    void everySquadWearsItsOwnLook() throws IOException {
        Squads s = shipped(new ArrayList<>());
        List<String> problems = new ArrayList<>();
        Path eq = Path.of("src/main/resources/data/hywmill/hywmill_equipment");
        dev.hywmill.garrison.equip.EquipmentProfiles p = dev.hywmill.garrison.equip.EquipmentProfiles.fromJson(List.of(
                JsonParser.parseString(Files.readString(eq.resolve("profiles.json"))).getAsJsonObject(),
                JsonParser.parseString(Files.readString(eq.resolve("squad_looks.json"))).getAsJsonObject()), problems);
        assertEquals(List.of(), problems);
        Set<String> seen = new java.util.HashSet<>();
        for (List<Squads.Squad> l : s.cultures().values()) {
            for (Squads.Squad q : l) {
                assertFalse(q.look().isEmpty(), q.id());
                var look = p.lookFor(q.role(q.members().get(0)));
                assertNotNull(look, "look of " + q.id());
                assertFalse(look.kits().isEmpty(), q.id() + " has its own armour");
                assertFalse(look.palette().isEmpty(), q.id() + " has its own colours");
                seen.add(q.look());
            }
        }
        assertEquals(112, seen.size(), "one look per squad");
        // crusader squads: white surcoats with red crosses on their shields
        var crus = p.lookFor("look:norman.crusader_band");
        assertTrue(crus.kits().stream().allMatch(k -> java.util.Set.of("magistuarmory:crusader_chestplate", "magistuarmoryaddon:dark_crusader_chestplate",
                "magistuarmoryaddon:xiii_century_knight_chestplate").contains(k.piece("chest"))));
        // post-M5 addons: every look keeps a plain Epic Knights kit (worn when the addons are not installed); the Byzantines and
        // the Japanese take Slavic Armory and Addon armour when they are
        for (var l : p.looks().entrySet()) {
            assertTrue(l.getValue().kits().stream().anyMatch(k -> java.util.Arrays.stream(new String[]{"head", "chest", "legs", "feet"})
                    .map(k::piece).allMatch(x -> x.equals("none") || x.startsWith("magistuarmory:"))), "plain kit in " + l.getKey());
        }
        assertTrue(p.lookFor("look:byz.excubitors").kits().stream().anyMatch(k -> k.piece("chest").startsWith("slavicarmory:")));
        assertTrue(p.lookFor("look:jp.samurai").kits().stream().anyMatch(k -> k.piece("chest").equals("magistuarmoryaddon:splint_chestplate")));
        assertTrue(crus.arms().stream().anyMatch(a -> a.base() == net.minecraft.world.item.DyeColor.WHITE
                && a.layers().stream().anyMatch(l -> l.pattern().equals("magistuarmory:crusader_cross") && l.color() == net.minecraft.world.item.DyeColor.RED)));
        var excub = p.lookFor("look:byz.excubitors");
        assertTrue(excub.arms().stream().anyMatch(a -> a.layers().stream().anyMatch(l -> l.pattern().equals("magistuarmory:two_headed_eagle"))));
    }
}

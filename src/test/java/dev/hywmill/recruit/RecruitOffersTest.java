package dev.hywmill.recruit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.politics.Standing;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Muster Roll offers and prices (post-M5 recruitment) with the shipped garrison and recruitment data. */
class RecruitOffersTest {
    static final int ARGENT = 64, OR = 4096;

    static JsonObject read(String path) throws IOException {
        return JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject();
    }

    static GarrisonTables garrison() throws IOException {
        String d = "src/main/resources/data/hywmill/hywmill_garrison/";
        return GarrisonTables.fromJson(List.of(read(d + "defaults.json"), read(d + "units.json")), id -> true, new ArrayList<>());
    }

    static RecruitTables shipped(List<String> problems) throws IOException {
        return RecruitTables.fromJson(List.of(read("src/main/resources/data/hywmill/hywmill_recruitment/defaults.json")), problems);
    }

    static Map<String, RecruitOffers.Offer> offers(Standing s, MilitaryTier tier, String culture) throws IOException {
        GarrisonTables g = garrison();
        return RecruitOffers.offers(s, tier, g.forCulture(culture), g.units(), shipped(new ArrayList<>())).stream()
                .collect(Collectors.toMap(RecruitOffers.Offer::key, o -> o));
    }

    @Test
    void shippedDataLoadsCleanlyAndMatchesTheDefaults() throws IOException {
        List<String> problems = new ArrayList<>();
        RecruitTables t = shipped(problems);
        assertEquals(List.of(), problems);
        assertEquals(RecruitTables.DEFAULT, t, "the shipped file documents the built-in defaults");
        assertEquals(32, t.maxPerPurchase());
        assertEquals(2, t.minRadius());
        assertEquals(36, t.maxRadius());
    }

    @Test
    void badStandingGetsNothingNeutralGetsMercenariesOnly() throws IOException {
        assertTrue(offers(Standing.OUTLAW, MilitaryTier.STRONGHOLD, "millenaire:norman").isEmpty());
        assertTrue(offers(Standing.UNWELCOME, MilitaryTier.STRONGHOLD, "millenaire:norman").isEmpty());
        Map<String, RecruitOffers.Offer> stranger = offers(Standing.STRANGER, MilitaryTier.STRONGHOLD, "millenaire:norman");
        assertEquals(List.of("merc:archer", "merc:crossbowman", "merc:militia"), stranger.keySet().stream().sorted().toList());
        assertTrue(stranger.values().stream().allMatch(o -> o.merc() && o.gearTier() == MilitaryTier.WATCH), "light gear");
        assertEquals(3 * ARGENT, stranger.get("merc:militia").price());
        assertEquals(4 * ARGENT, stranger.get("merc:archer").price());
        assertEquals(5 * ARGENT, stranger.get("merc:crossbowman").price());
    }

    @Test
    void qualityRisesWithTheVillageAndWithStanding() throws IOException {
        // the village's tier caps what standing unlocks, and standing caps what the village has
        assertEquals(MilitaryTier.GUARD_POST, RecruitOffers.gearTier(Standing.TRUSTED, MilitaryTier.STRONGHOLD, RecruitTables.DEFAULT));
        assertEquals(MilitaryTier.GARRISON, RecruitOffers.gearTier(Standing.PATRON, MilitaryTier.STRONGHOLD, RecruitTables.DEFAULT));
        assertEquals(MilitaryTier.STRONGHOLD, RecruitOffers.gearTier(Standing.SWORN, MilitaryTier.STRONGHOLD, RecruitTables.DEFAULT));
        assertEquals(MilitaryTier.GUARD_POST, RecruitOffers.gearTier(Standing.SWORN, MilitaryTier.GUARD_POST, RecruitTables.DEFAULT));
        assertEquals(MilitaryTier.NONE, RecruitOffers.gearTier(Standing.STRANGER, MilitaryTier.STRONGHOLD, RecruitTables.DEFAULT));
        Map<String, RecruitOffers.Offer> trusted = offers(Standing.TRUSTED, MilitaryTier.STRONGHOLD, "millenaire:norman");
        Map<String, RecruitOffers.Offer> sworn = offers(Standing.SWORN, MilitaryTier.STRONGHOLD, "millenaire:norman");
        assertTrue(trusted.containsKey("unit:light_lancer_rider"), "post-M5: light horse (scouts) from a GUARD_POST gear tier");
        assertTrue(sworn.containsKey("unit:light_lancer_rider"), "Normans field lancers");
        assertEquals(MilitaryTier.STRONGHOLD, sworn.get("unit:spear_man").gearTier());
        assertTrue(sworn.values().stream().noneMatch(o -> !o.merc() && !o.engine() && o.unit().unitClass() == UnitClass.LEVY), "levies are mercenaries");
        // a small village offers no cultural soldiers at all, however good the standing
        assertTrue(offers(Standing.SWORN, MilitaryTier.WATCH, "millenaire:norman").values().stream().allMatch(o -> o.merc() || o.engine()));
    }

    @Test
    void culturalSoldiersCostOneToThreeOrAndBetterStandingIsCheaper() throws IOException {
        for (Standing s : List.of(Standing.TRUSTED, Standing.PATRON, Standing.SWORN)) {
            for (var o : offers(s, MilitaryTier.STRONGHOLD, "millenaire:norman").values()) {
                if (!o.merc() && !o.engine()) {
                    assertTrue(o.price() >= OR * 0.75 && o.price() <= 3 * OR, s + " " + o.key() + " " + o.price());
                    if (s == Standing.TRUSTED) {
                        assertEquals(0, o.price() % ARGENT, "undiscounted prices are whole argent");
                    }
                }
            }
        }
        // Guard-post spearman (cost 2): exactly 1 or for a Trusted player
        assertEquals(OR, offers(Standing.TRUSTED, MilitaryTier.GUARD_POST, "millenaire:norman").get("unit:spear_man").price());
        // Stronghold lancer (cost 4): (0.5 + 1.0) x 1.5 = 2.25 or, less 20% for Sworn
        assertEquals((int) Math.round(144 * ARGENT * 0.8), offers(Standing.SWORN, MilitaryTier.STRONGHOLD, "millenaire:norman")
                .get("unit:light_lancer_rider").price());
        // Patron gets 10% off mercenaries too
        assertEquals(173, offers(Standing.PATRON, MilitaryTier.WATCH, "millenaire:norman").get("merc:militia").price(), "3 argent less 10%");
    }

    @Test
    void eachCultureOffersItsOwnTroops() throws IOException {
        for (String c : List.of("millenaire:norman", "millenaire:byzantines", "millenaire:seljuk", "millenaire:japanese", "millenaire:indian",
                "millenaire:mayan", "millenaire:inuits")) {
            Map<String, RecruitOffers.Offer> o = offers(Standing.SWORN, MilitaryTier.STRONGHOLD, c);
            assertTrue(o.values().stream().anyMatch(x -> !x.merc()), c + " offers cultural soldiers: " + o.keySet());
        }
    }

    @Test
    void moneyReadsInMillenaireCoins() {
        assertEquals("1 or", RecruitOffers.money(4096));
        assertEquals("2 or 16 argent", RecruitOffers.money(2 * 4096 + 16 * 64));
        assertEquals("3 argent", RecruitOffers.money(192));
        assertEquals("0 deniers", RecruitOffers.money(0));
    }

    @Test
    void siegeEnginesForSaleNoGunpowderTrebuchetsForPatrons() throws IOException {
        assertTrue(offers(Standing.STRANGER, MilitaryTier.STRONGHOLD, "millenaire:norman").values().stream().noneMatch(RecruitOffers.Offer::engine));
        Map<String, RecruitOffers.Offer> trusted = offers(Standing.TRUSTED, MilitaryTier.WATCH, "millenaire:norman");
        assertEquals(2 * OR, trusted.get("engine:mangonels").price());
        assertTrue(trusted.get("engine:mangonels").crewed(), "a catapult comes with its engineer");
        assertFalse(trusted.get("engine:battering_ram").crewed(), "the player drives a ram");
        assertFalse(trusted.containsKey("engine:trebuchets"), "trebuchets are for patrons");
        Map<String, RecruitOffers.Offer> patron = offers(Standing.PATRON, MilitaryTier.WATCH, "millenaire:norman");
        assertEquals((int) Math.round(4 * OR * 0.9), patron.get("engine:trebuchets").price());
        assertTrue(patron.keySet().stream().filter(k -> k.startsWith("engine:"))
                .noneMatch(k -> k.contains("cannon") || k.contains("bombard") || k.contains("culverin") || k.contains("nest")));
        assertEquals("Catapult (with an engineer)", trusted.get("engine:mangonels").label());
    }
}

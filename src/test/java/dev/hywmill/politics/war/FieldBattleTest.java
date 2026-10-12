package dev.hywmill.politics.war;

import dev.hywmill.politics.service.RealmDiplomacy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Field battles (docs/realm-design.md §13) and what realms weigh in each other (§12). */
class FieldBattleTest {
    @Test
    void theArmiesMeetHalfwayAlongWhatIsLeftOfTheMarch() {
        // a march from elapsed 10000 to 16000; now 11000: halfway along the rest is 13500
        assertEquals(13500, FieldBattle.meetAt(11000, 16000, 6000));
        assertEquals(0.5833, FieldBattle.along(13500, 16000, 6000), 0.001);
        // still mustering (before the march): halfway along the whole march
        assertEquals(13000, FieldBattle.meetAt(2000, 16000, 6000));
        // too close to the walls: no battle
        assertEquals(-1, FieldBattle.meetAt(15500, 16000, 6000));
        assertTrue(FieldBattle.worthIt(100, 90));
        assertFalse(FieldBattle.worthIt(80, 90), "it goes only as the host's match");
        assertFalse(FieldBattle.worthIt(80, 0));
    }

    @Test
    void demandsAndAlarm() {
        assertEquals(0, RealmDiplomacy.yieldChance(100, 100), 1e-9, "a match does not kneel");
        assertTrue(RealmDiplomacy.yieldChance(300, 100) > 0.5);
        assertTrue(RealmDiplomacy.yieldChance(1000, 10) <= 0.7);
        assertTrue(RealmDiplomacy.alarm(3, 1) < RealmDiplomacy.ALARM, "a small realm alarms no one");
        assertTrue(RealmDiplomacy.alarm(5, 2) >= RealmDiplomacy.ALARM, "a great realm twice as strong does");
        assertEquals(4096 * 4, RealmDiplomacy.tribute(4));
    }
}

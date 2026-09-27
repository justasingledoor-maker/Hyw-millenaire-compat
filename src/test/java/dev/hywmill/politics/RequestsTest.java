package dev.hywmill.politics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M5-5: the village evaluates detachment requests; it offers what it can spare and is willing to, and says why. */
class RequestsTest {
    static final PoliticsTables.RequestRule R = PoliticsTables.DEFAULTS.requests();

    static Requests.Offer detach(Standing s, int asked, int spare, int casualties, int favor) {
        return Requests.evaluate(Requests.Kind.DETACHMENT, s, asked, 1, 10, true, false, spare, casualties, favor, 100000, -1, R);
    }

    @Test
    void onlyDetachmentsExist() {
        assertArrayEquals(new Requests.Kind[]{Requests.Kind.DETACHMENT}, Requests.Kind.values(), "player-following escorts are deferred");
    }

    @Test
    void limitsFollowStanding() {
        assertEquals(Requests.Refusal.STANDING_TOO_LOW, detach(Standing.STRANGER, 1, 10, 0, 50).refusal());
        assertEquals(Requests.Refusal.STANDING_TOO_LOW, detach(Standing.TRUSTED, 2, 10, 0, 50).refusal(), "no detachments for Trusted");
        assertEquals(4, detach(Standing.PATRON, 8, 10, 0, 50).units());
        assertEquals(8, detach(Standing.SWORN, 8, 10, 0, 50).units());
    }

    @Test
    void theVillageKeepsAgency() {
        assertEquals(Requests.Refusal.NOT_CALM, Requests.evaluate(Requests.Kind.DETACHMENT, Standing.SWORN, 2, 1, 10, false, false, 10, 0, 50, 0, -1, R).refusal());
        assertEquals(Requests.Refusal.RAID_PREPARING, Requests.evaluate(Requests.Kind.DETACHMENT, Standing.SWORN, 2, 1, 10, true, true, 10, 0, 50, 0, -1, R).refusal());
        assertEquals(Requests.Refusal.NOTHING_TO_SPARE, detach(Standing.SWORN, 2, 0, 0, 50).refusal());
        Requests.Offer fewer = detach(Standing.SWORN, 6, 3, 0, 50);
        assertTrue(fewer.ok());
        assertEquals(3, fewer.units());
        assertEquals("we can spare 3", fewer.reason());
        assertEquals(Requests.Refusal.COOLDOWN, Requests.evaluate(Requests.Kind.DETACHMENT, Standing.SWORN, 2, 1, 10, true, false, 10, 0, 50, 1000, 500, R).refusal());
    }

    @Test
    void willingnessFallsWithCasualtiesAndRisesWithFavor() {
        assertEquals(4, detach(Standing.PATRON, 6, 10, 0, 10).units());
        assertEquals(3, detach(Standing.PATRON, 6, 10, 2, 10).units(), "two soldiers lost on your errands");
        assertEquals(Requests.Refusal.UNWILLING, detach(Standing.PATRON, 2, 10, 8, 10).refusal());
        assertEquals(2, detach(Standing.PATRON, 2, 10, 8, 80).units(), "Favor restores willingness");
    }

    @Test
    void favorIsPaidOnAcceptanceAndLimitsTheOffer() {
        Requests.Offer d = Requests.evaluate(Requests.Kind.DETACHMENT, Standing.PATRON, 4, 3, 10, true, false, 10, 0, 30, 0, -1, R);
        assertEquals(4, d.units());
        assertEquals(4 * 2 * 3, d.favorCost(), "2 Favor per unit per day");
        Requests.Offer short_ = Requests.evaluate(Requests.Kind.DETACHMENT, Standing.SWORN, 8, 1, 10, true, false, 10, 0, 6, 0, -1, R);
        assertEquals(3, short_.units(), "Favor covers 3");
        assertEquals(Requests.Refusal.NO_FAVOR, detach(Standing.SWORN, 2, 10, 0, 0).refusal());
    }

    @Test
    void detachmentPointAndLengthAreBounded() {
        assertEquals(Requests.Refusal.OUT_OF_RANGE,
                Requests.evaluate(Requests.Kind.DETACHMENT, Standing.SWORN, 2, 1, R.detachRadius() + 1, true, false, 10, 0, 50, 0, -1, R).refusal());
        assertEquals(Requests.Refusal.BAD_REQUEST,
                Requests.evaluate(Requests.Kind.DETACHMENT, Standing.SWORN, 2, R.detachMaxDays() + 1, 10, true, false, 10, 0, 50, 0, -1, R).refusal());
        assertEquals(Requests.Refusal.BAD_REQUEST,
                Requests.evaluate(Requests.Kind.DETACHMENT, Standing.SWORN, 2, 0, 10, true, false, 10, 0, 50, 0, -1, R).refusal());
    }
}

package dev.hywmill.politics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M5-5: the village evaluates requests; it offers what it can spare and is willing to, and says why. */
class RequestsTest {
    static final PoliticsTables.RequestRule R = PoliticsTables.DEFAULTS.requests();

    static Requests.Offer escort(Standing s, int asked, int spare, int casualties, int favor) {
        return Requests.evaluate(Requests.Kind.ESCORT, s, asked, 0, 0, true, false, spare, casualties, favor, 100000, -1, R);
    }

    @Test
    void limitsFollowStanding() {
        assertEquals(Requests.Refusal.STANDING_TOO_LOW, escort(Standing.STRANGER, 1, 10, 0, 50).refusal());
        assertEquals(2, escort(Standing.TRUSTED, 6, 10, 0, 50).units());
        assertEquals(4, escort(Standing.PATRON, 6, 10, 0, 50).units());
        assertEquals(6, escort(Standing.SWORN, 6, 10, 0, 50).units());
        assertEquals(Requests.Refusal.STANDING_TOO_LOW,
                Requests.evaluate(Requests.Kind.DETACHMENT, Standing.TRUSTED, 2, 1, 10, true, false, 10, 0, 50, 0, -1, R).refusal(), "no detachments for Trusted");
        assertEquals(8, Requests.evaluate(Requests.Kind.DETACHMENT, Standing.SWORN, 8, 1, 10, true, false, 10, 0, 50, 0, -1, R).units());
    }

    @Test
    void theVillageKeepsAgency() {
        assertEquals(Requests.Refusal.NOT_CALM, Requests.evaluate(Requests.Kind.ESCORT, Standing.SWORN, 2, 0, 0, false, false, 10, 0, 50, 0, -1, R).refusal());
        assertEquals(Requests.Refusal.RAID_PREPARING, Requests.evaluate(Requests.Kind.ESCORT, Standing.SWORN, 2, 0, 0, true, true, 10, 0, 50, 0, -1, R).refusal());
        assertEquals(Requests.Refusal.NOTHING_TO_SPARE, escort(Standing.SWORN, 2, 0, 0, 50).refusal());
        Requests.Offer fewer = escort(Standing.SWORN, 6, 3, 0, 50);
        assertTrue(fewer.ok());
        assertEquals(3, fewer.units());
        assertEquals("we can spare 3", fewer.reason());
        assertEquals(Requests.Refusal.COOLDOWN, Requests.evaluate(Requests.Kind.ESCORT, Standing.SWORN, 2, 0, 0, true, false, 10, 0, 50, 1000, 500, R).refusal());
    }

    @Test
    void willingnessFallsWithCasualtiesAndRisesWithFavor() {
        assertEquals(4, escort(Standing.PATRON, 6, 10, 0, 10).units());
        assertEquals(3, escort(Standing.PATRON, 6, 10, 2, 10).units(), "two soldiers lost on your errands");
        assertEquals(Requests.Refusal.UNWILLING, escort(Standing.TRUSTED, 2, 10, 4, 10).refusal());
        assertEquals(2, escort(Standing.TRUSTED, 2, 10, 4, 80).units(), "Favor restores willingness");
    }

    @Test
    void favorIsPaidOnAcceptanceAndLimitsTheOffer() {
        Requests.Offer o = escort(Standing.SWORN, 6, 10, 0, 4);
        assertEquals(4, o.units());
        assertEquals(4, o.favorCost());
        assertEquals(Requests.Refusal.NO_FAVOR, escort(Standing.SWORN, 2, 10, 0, 0).refusal());
        Requests.Offer d = Requests.evaluate(Requests.Kind.DETACHMENT, Standing.PATRON, 4, 3, 10, true, false, 10, 0, 30, 0, -1, R);
        assertEquals(4, d.units());
        assertEquals(4 * 2 * 3, d.favorCost(), "2 Favor per unit per day");
    }

    @Test
    void detachmentPointAndLengthAreBounded() {
        assertEquals(Requests.Refusal.OUT_OF_RANGE,
                Requests.evaluate(Requests.Kind.DETACHMENT, Standing.SWORN, 2, 1, R.detachRadius() + 1, true, false, 10, 0, 50, 0, -1, R).refusal());
        assertEquals(Requests.Refusal.BAD_REQUEST,
                Requests.evaluate(Requests.Kind.DETACHMENT, Standing.SWORN, 2, R.detachMaxDays() + 1, 10, true, false, 10, 0, 50, 0, -1, R).refusal());
    }
}

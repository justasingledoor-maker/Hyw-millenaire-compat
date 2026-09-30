package dev.hywmill.net;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** M5-UI: the versioned payloads round-trip; lists are bounded; the per-player rate limit holds. */
class PoliticsWireTest {
    static RegistryFriendlyByteBuf buf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    static PoliticsSnapshot sample(int villages, int lines) {
        List<PoliticsSnapshot.VillageRow> vs = new ArrayList<>();
        for (int i = 0; i < villages; i++) {
            vs.add(new PoliticsSnapshot.VillageRow(UUID.randomUUID(), "Village " + i, "STRANGER", i % 3 == 0 ? PoliticsSnapshot.VillageRow.NO_RELATION : -i, i % 2 == 0));
        }
        List<String> ls = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            ls.add("line " + i);
        }
        return new PoliticsSnapshot(UUID.randomUUID(), "Home", "millenaire:norman", "TRUSTED", "UNWELCOME", 5000, 12.5, 7, 3, ls, ls, vs,
                null, List.of(new PoliticsSnapshot.ActionRow("RECONCILE", "Envoy: reconciliation", true, "needs trusted", "likely")),
                List.of(), List.of("spear_man (detached, 100 s left)"), List.of("a trusted friend of Home"));
    }

    @Test
    void snapshotRoundTrips() {
        PoliticsSnapshot s = sample(5, 3);
        RegistryFriendlyByteBuf b = buf();
        PoliticsSnapshot.CODEC.encode(b, s);
        assertEquals(s, PoliticsSnapshot.CODEC.decode(b));
        assertEquals(0, b.readableBytes());
    }

    @Test
    void listsAreBounded() {
        PoliticsSnapshot s = sample(PoliticsSnapshot.MAX_ROWS + 20, PoliticsSnapshot.MAX_LINES + 5);
        RegistryFriendlyByteBuf b = buf();
        PoliticsSnapshot.CODEC.encode(b, s);
        PoliticsSnapshot back = PoliticsSnapshot.CODEC.decode(b);
        assertEquals(PoliticsSnapshot.MAX_ROWS, back.villages().size());
        assertEquals(PoliticsSnapshot.MAX_LINES, back.chronicle().size());
        assertEquals("line " + (PoliticsSnapshot.MAX_LINES + 4), back.chronicle().get(PoliticsSnapshot.MAX_LINES - 1), "the newest lines are kept");
    }

    @Test
    void intentsRoundTrip() {
        UUID h = UUID.randomUUID(), t = UUID.randomUUID();
        var sa = new PoliticsPayloads.SubmitAction("SOW_DISCORD", h, t, 3);
        RegistryFriendlyByteBuf b = buf();
        PoliticsPayloads.SubmitAction.CODEC.encode(b, sa);
        assertEquals(sa, PoliticsPayloads.SubmitAction.CODEC.decode(b));
        var sel = new PoliticsPayloads.SelectVillage(h, t);
        PoliticsPayloads.SelectVillage.CODEC.encode(b, sel);
        assertEquals(sel, PoliticsPayloads.SelectVillage.CODEC.decode(b));
        var r = new PoliticsPayloads.ActionResult(false, "PAIR_COOLDOWN", "you proposed this recently");
        PoliticsPayloads.ActionResult.CODEC.encode(b, r);
        assertEquals(r, PoliticsPayloads.ActionResult.CODEC.decode(b));
        assertEquals("4", PoliticsNet.VERSION); // 3: village liveries and distances; 4: player colours
    }

    @Test
    void rateLimitPerPlayer() {
        PoliticsNet.Limiter l = new PoliticsNet.Limiter();
        UUID p = UUID.randomUUID(), q = UUID.randomUUID();
        assertTrue(l.allow(p, 100));
        assertFalse(l.allow(p, 102));
        assertTrue(l.allow(q, 102), "per player");
        assertTrue(l.allow(p, 105));
    }

    @Test
    void musterViewCarriesThePlayersColours() {
        RecruitPayloads.View v = new RecruitPayloads.View(new net.minecraft.core.BlockPos(1, 2, 3), "h", 5, 8, true,
                java.util.List.of(new RecruitPayloads.OfferView("k", "l", "WATCH", 9)), 14, 0);
        RegistryFriendlyByteBuf b = buf();
        RecruitPayloads.View.CODEC.encode(b, v);
        assertEquals(v, RecruitPayloads.View.CODEC.decode(b));
        RecruitPayloads.View none = new RecruitPayloads.View(v.pos(), "h", 5, 8, false, java.util.List.of());
        b = buf();
        RecruitPayloads.View.CODEC.encode(b, none);
        RecruitPayloads.View back = RecruitPayloads.View.CODEC.decode(b);
        assertEquals(-1, back.colour1());
        assertEquals(-1, back.colour2());
        RecruitPayloads.SetColours c = new RecruitPayloads.SetColours(v.pos(), -1, 15);
        b = buf();
        RecruitPayloads.SetColours.CODEC.encode(b, c);
        assertEquals(c, RecruitPayloads.SetColours.CODEC.decode(b));
    }
}

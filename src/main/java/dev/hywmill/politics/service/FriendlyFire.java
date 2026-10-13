package dev.hywmill.politics.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.service.ColumnService;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.garrison.tag.GarrisonAttachments;
import dev.hywmill.garrison.tag.GarrisonTag;
import dev.hywmill.politics.war.Campaign;
import dev.hywmill.politics.war.Column;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * No friendly fire (1.10, config {@code noFriendlyFire}): a player who fights for a realm (serving it as volunteer, mercenary
 * or knight, or on campaign with one of its villages) and the soldiers he hired neither hurt nor are hurt by that realm's
 * soldiers and villagers. His arrows pass through them (and theirs through him); a blow does nothing, so nobody turns on him.
 */
public final class FriendlyFire {
    private FriendlyFire() {}

    /** Whether {@code a} and {@code b} fight on the same side: one a player (or his man), the other of the realm he fights for. */
    public static boolean friends(ServerLevel level, @Nullable Entity a, @Nullable Entity b) {
        if (a == null || b == null || a == b || !HywMillConfig.NO_FRIENDLY_FIRE.get()) {
            return false;
        }
        UUID pa = playerOf(a), pb = playerOf(b);
        if (pa == null && pb == null) {
            return false; // no player in it: the cheap, common case
        }
        if (pa != null && pb != null) {
            return false; // between players and their men: not ours to judge
        }
        ServerLevel ow = level.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        UUID village = villageOf(ledger, pa != null ? b : a);
        if (village == null) {
            return false;
        }
        UUID side = sideOf(ledger, pa != null ? pa : pb, ow.getGameTime());
        long now = ow.getGameTime();
        return side != null && RealmService.sovereignOf(ledger, side, now).equals(RealmService.sovereignOf(ledger, village, now));
    }

    /** The player behind an entity: the player himself, or the owner of a soldier he hired; null for anyone else. */
    @Nullable
    static UUID playerOf(Entity e) {
        if (e instanceof Player p) {
            return p.isSpectator() ? null : p.getUUID();
        }
        UnitProvider units = Services.units();
        if (units != null && GarrisonAttachments.get(e) == null && !e.getPersistentData().hasUUID(ColumnService.TAG) && units.isUnit(e)) {
            return units.ownerOf(e); // a village's own men are owned by its faction, not a player: ownerOf is not a player then
        }
        return null;
    }

    /** The village a soldier or villager fights for: a garrison's, a column's (a vassal's men their overlord's), a Millénaire resident's. */
    @Nullable
    static UUID villageOf(GarrisonLedger ledger, Entity e) {
        GarrisonTag tag = GarrisonAttachments.get(e);
        if (tag != null) {
            return tag.villageId();
        }
        if (e.getPersistentData().hasUUID(ColumnService.TAG)) {
            UUID id = e.getPersistentData().getUUID(ColumnService.TAG);
            for (Column c : ledger.columns()) {
                if (c.id.equals(id)) {
                    return c.kind == Column.Kind.VASSAL && c.destination != null ? c.destination : c.owner;
                }
            }
            return null;
        }
        Entity rider = e.getFirstPassenger(); // a soldier's horse
        if (rider != null && rider != e) {
            UUID v = villageOf(ledger, rider);
            if (v != null) {
                return v;
            }
        }
        SettlementSource source = Services.settlements();
        return source == null ? null : source.residentInfo(e).filter(r -> !r.raider()).map(r -> r.settlementId()).orElse(null);
    }

    /** The village whose realm a player fights for: the realm he serves, else the village he campaigns with; null: none. */
    @Nullable
    static UUID sideOf(GarrisonLedger ledger, UUID player, long now) {
        dev.hywmill.politics.realm.Service sv = ledger.services().get(player);
        if (sv != null && sv.realm != null) {
            return sv.realm;
        }
        Campaign c = RelationProjector.campaignOf(ledger, player);
        return c != null && c.active(now) ? c.ally() : null;
    }
}

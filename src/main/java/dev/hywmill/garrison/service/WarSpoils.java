package dev.hywmill.garrison.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.garrison.tag.GarrisonAttachments;
import dev.hywmill.garrison.tag.GarrisonTag;
import dev.hywmill.politics.war.Morale;
import dev.hywmill.politics.war.Siege;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.PoliticsNbt;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Head prices (1.9, docs/siege-design.md "Spoils"): a soldier killed by a player, or by one of a player's own men, in a war that
 * player fights against the soldier's village, earns the player his head price at once (a levy 1 denier argent, a regular or
 * hired sword 2, a veteran 6, a war engine 8, a royal guard 16, a ruler 4 denier or), paid by the side he fights for. It is also
 * his merit in the sieges and assaults the soldier fell in: at victory the spoils are shared by merit ({@link Morale#spoils}).
 */
public final class WarSpoils {
    private WarSpoils() {}

    /** A soldier fell dead: the killer's player (himself, or the owner of the unit that struck the blow) earns his head price. */
    public static void onKill(ServerLevel level, LivingEntity victim, @Nullable Entity killer) {
        GarrisonTag tag = GarrisonAttachments.get(victim);
        if (tag == null || killer == null) {
            return;
        }
        ServerLevel ow = level.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        VillageRecord v = ledger.get(tag.villageId());
        RosterEntry e = v == null || v.hywRoster == null ? null : v.hywRoster.entry(tag.rosterId());
        if (e == null) {
            return;
        }
        UnitProvider units = Services.units();
        UUID player = killer instanceof ServerPlayer sp ? sp.getUUID() : units != null && units.isUnit(killer) ? units.ownerOf(killer) : null;
        if (player == null) {
            return;
        }
        long now = ow.getGameTime();
        UUID payer = payer(ledger, player, v.villageId, now);
        if (payer == null) {
            return;
        }
        int price = price(v, e);
        String key = v.hywRoster.isArsenal(e) && e.unitKey.endsWith("s") ? e.unitKey.substring(0, e.unitKey.length() - 1) : e.unitKey;
        String what = key.replace('_', ' ') + " of " + v.name;
        String text = "+" + dev.hywmill.recruit.RecruitOffers.money(price) + ": the head price of " + ("aeiou".indexOf(what.charAt(0)) >= 0 ? "an " : "a ") + what + (killer instanceof ServerPlayer ? "" : " (your men)");
        SettlementSource source = Services.settlements();
        ServerPlayer online = AssaultService.online(ow, player);
        if (online != null) {
            if (source != null) {
                source.giveMoney(online, price);
            }
            online.displayClientMessage(Component.literal(text), true);
        } else {
            ledger.pendingPay().add(new PoliticsNbt.PendingPay(player, source != null ? price : 0, text));
        }
        for (Siege s : ledger.sieges()) {
            if (s.outcome == Siege.Outcome.NONE && (SiegeService.side(ledger, s, true).contains(v.villageId) || SiegeService.side(ledger, s, false).contains(v.villageId))) {
                s.merit.merge(player, price, Integer::sum);
            }
        }
        AssaultService.merit(ledger, player, v.villageId, price);
        ledger.setDirty();
        HmLog.info("Head price: {} earns {} deniers for {} (killed by {})", player.toString().substring(0, 8), price, ("aeiou".indexOf(what.charAt(0)) >= 0 ? "an " : "a ") + what,
                killer instanceof ServerPlayer ? "the player" : "his men");
    }

    /** The head price of a soldier (deniers). */
    static int price(VillageRecord v, RosterEntry e) {
        if ("ruler".equals(e.extra)) {
            return Morale.RULER_PRICE;
        }
        if ("royal".equals(e.extra)) {
            return Morale.ROYAL_PRICE;
        }
        if (v.hywRoster.isArsenal(e)) {
            return dev.hywmill.politics.war.ArsenalPlan.isEngine(e.unitKey) ? Morale.ENGINE_PRICE : Morale.headPrice(Morale.Grade.REGULAR);
        }
        return Morale.headPrice(Morale.grade(e.mobilized, e.extra, e.mercLook, e.equipmentLevel));
    }

    /**
     * Who pays a player for a head taken from {@code village}: the village he campaigns with against it, the realm he serves
     * when it is at war with it, the side he fights for in a siege against it, or the plunder of his own assault on it. Null:
     * he is at no war with it (no head price; a killing in peace is the politics' business).
     */
    @Nullable
    static UUID payer(GarrisonLedger ledger, UUID player, UUID village, long now) {
        dev.hywmill.politics.war.Campaign c = dev.hywmill.politics.service.RelationProjector.campaignOf(ledger, player);
        if (c != null && c.active(now) && (c.enemy().equals(village) || dev.hywmill.politics.service.RealmService.sameRealm(ledger, c.enemy(), village, now))) {
            return c.ally();
        }
        dev.hywmill.politics.realm.Service sv = ledger.services().get(player);
        if (sv != null && sv.realm != null && dev.hywmill.politics.service.RelationProjector.atWar(ledger, sv.realm, village)) {
            return sv.realm;
        }
        for (Siege s : ledger.sieges()) {
            if (s.outcome != Siege.Outcome.NONE) {
                continue;
            }
            int side = SiegeService.sideOf(ledger, player, s, now);
            if (side > 0 && SiegeService.side(ledger, s, false).contains(village)) {
                return s.attacker;
            }
            if (side < 0 && SiegeService.side(ledger, s, true).contains(village)) {
                return s.target;
            }
        }
        return AssaultService.target(ledger, player, village);
    }
}

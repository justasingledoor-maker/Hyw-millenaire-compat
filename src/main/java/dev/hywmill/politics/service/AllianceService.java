package dev.hywmill.politics.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.politics.war.Alliance;
import dev.hywmill.politics.war.Campaign;
import dev.hywmill.politics.war.Vassalage;
import dev.hywmill.politics.war.WarRecord;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Calls to arms (post-M5; {@link Alliance}): when a war starts, the attacked village's allies, vassals and overlord choose
 * at once to honour the alliance (they declare war on the attacker, and its allies are called in turn) or to break it
 * (their relation falls to {@link Alliance#BROKEN}). An ally of both sides stays out of that war and helps neither. Each
 * choice is recorded on the war ({@link WarRecord#sides}): a village helps one side of a war at most.
 */
public final class AllianceService {
    private AllianceService() {}

    /** Guards a cascade: the joiners so far and the villages already asked (one chain of declarations at a time). */
    private record Chain(Set<UUID> asked, int[] joins) {}

    /** A war starts: {@code attacker} on {@code attacked} (both are called when nobody declared it: the war just broke out). */
    public static void warStarted(ServerLevel overworld, GarrisonLedger ledger, VillageRecord attacker, VillageRecord attacked, boolean declared,
                                  long now) {
        Chain chain = new Chain(new HashSet<>(List.of(attacker.villageId, attacked.villageId)), new int[1]);
        call(overworld, ledger, attacker, attacked, 0, now, chain);
        if (!declared) {
            call(overworld, ledger, attacked, attacker, 0, now, chain);
        }
    }

    private static void call(ServerLevel overworld, GarrisonLedger ledger, VillageRecord attacker, VillageRecord attacked, int depth, long now,
                             Chain chain) {
        SettlementSource source = Services.settlements();
        if (source == null || depth > Alliance.MAX_DEPTH || attacked.loneBuilding) {
            return;
        }
        WarRecord war = ledger.wars().get(WarRecord.key(attacker.villageId, attacked.villageId));
        if (war == null) {
            return;
        }
        war.sides.put(attacked.villageId, attacked.villageId);
        war.sides.put(attacker.villageId, attacker.villageId);
        List<VillageRecord> allies = new ArrayList<>(ledger.all());
        allies.sort(java.util.Comparator.comparing(v -> v.villageId));
        for (VillageRecord c : allies) {
            if (chain.joins()[0] >= Alliance.MAX_JOINS) {
                break;
            }
            UUID cid = c.villageId;
            if (c.loneBuilding || chain.asked().contains(cid) || war.sides.containsKey(cid)) {
                continue;
            }
            boolean sworn = sworn(ledger, cid, attacked.villageId, now);
            int toAttacked = source.villageRelation(overworld, cid, attacked.villageId).orElse(Integer.MIN_VALUE);
            if (toAttacked < Alliance.ALLY && !sworn) {
                continue; // not an ally
            }
            if (RelationProjector.atWar(ledger, cid, attacker.villageId) || c.politics.truceWith(attacker.villageId, now)
                    || attacker.politics.truceWith(cid, now)) {
                war.sides.put(cid, attacked.villageId); // already fighting the attacker (or bound by a truce with it): on this side
                continue;
            }
            chain.asked().add(cid);
            int toAttacker = source.villageRelation(overworld, cid, attacker.villageId).orElse(0);
            boolean swornToAttacker = sworn(ledger, cid, attacker.villageId, now);
            double draw = new SplittableRandom(UUID.nameUUIDFromBytes((cid + ">" + war.key() + ">" + war.warSince).getBytes(StandardCharsets.UTF_8))
                    .getMostSignificantBits()).nextDouble();
            Alliance.Choice choice = swornToAttacker && !sworn ? Alliance.Choice.NEUTRAL
                    : Alliance.choose(toAttacked, toAttacker, sworn, depth, draw);
            String text;
            switch (choice) {
                case NEUTRAL -> {
                    war.sides.put(cid, WarRecord.NEUTRAL);
                    text = c.name + ", friend of both " + attacked.name + " and " + attacker.name + ", stays out of their war and will help neither";
                }
                case BREAK -> {
                    war.sides.put(cid, WarRecord.NEUTRAL);
                    source.setVillageRelation(overworld, cid, attacked.villageId, Alliance.BROKEN);
                    text = c.name + " will not go to war with " + attacker.name + " for " + attacked.name + ": the alliance is broken";
                }
                default -> {
                    chain.joins()[0]++;
                    text = c.name + " honours its alliance with " + attacked.name + " and declares war on " + attacker.name;
                    tell(overworld, ledger, c, attacked, attacker, text);
                    PoliticsService.chronicle(overworld, source, c, now, text);
                    PoliticsService.chronicle(overworld, source, attacked, now, text);
                    HmLog.info("Alliance: {}", text);
                    WarCounselService.declareWar(overworld, ledger, c, attacker, now, "honouring its alliance with " + attacked.name, false);
                    war.sides.put(cid, attacked.villageId);
                    WarRecord joined = ledger.wars().get(WarRecord.key(cid, attacker.villageId));
                    if (joined != null) {
                        joined.sides.put(attacked.villageId, cid); // the attacked village is on its ally's side in that war too
                    }
                    // the attacker is attacked in turn: its own allies are called
                    call(overworld, ledger, c, attacker, depth + 1, now, chain);
                    continue;
                }
            }
            tell(overworld, ledger, c, attacked, attacker, text);
            PoliticsService.chronicle(overworld, source, c, now, text);
            PoliticsService.chronicle(overworld, source, attacked, now, text);
            HmLog.info("Alliance: {}", text);
        }
        ledger.setDirty();
    }

    /** A vassal of the other, or its overlord (fealty binds both ways while it lasts). */
    static boolean sworn(GarrisonLedger ledger, UUID x, UUID y, long now) {
        for (Vassalage v : ledger.vassalages()) {
            if (!v.over(now) && ((v.vassal.equals(x) && v.overlord.equals(y)) || (v.vassal.equals(y) && v.overlord.equals(x)))) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code helper} may help {@code side} against {@code enemy}: not if it took the other side of their war, or stayed out. */
    public static boolean mayHelp(GarrisonLedger ledger, UUID helper, UUID side, UUID enemy) {
        WarRecord w = ledger.wars().get(WarRecord.key(side, enemy));
        return w == null || w.mayHelp(helper, side);
    }

    /** {@code helper} takes {@code side}'s side in its war with {@code enemy} (by sending it help). */
    public static void pledge(GarrisonLedger ledger, UUID helper, UUID side, UUID enemy) {
        WarRecord w = ledger.wars().get(WarRecord.key(side, enemy));
        if (w != null) {
            w.sides.putIfAbsent(helper, side);
            ledger.setDirty();
        }
    }

    /** Players on campaign with or against any of the three, or near the ally. */
    private static void tell(ServerLevel overworld, GarrisonLedger ledger, VillageRecord ally, VillageRecord attacked, VillageRecord attacker, String text) {
        Set<UUID> to = new HashSet<>();
        for (Campaign c : ledger.campaigns()) {
            for (UUID v : List.of(ally.villageId, attacked.villageId, attacker.villageId)) {
                if (c.ally().equals(v) || c.enemy().equals(v)) {
                    to.add(c.player());
                }
            }
        }
        for (ServerPlayer p : PoliticsService.onlinePlayers(overworld)) {
            if (to.contains(p.getUUID()) || p.position().distanceToSqr(Vec3.atCenterOf(ally.center)) < 256 * 256) {
                p.sendSystemMessage(Component.literal("[War] " + text).withStyle(ChatFormatting.GOLD));
            }
        }
    }
}

package dev.hywmill.politics.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.service.SiegeService;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.RaidCounsel;
import dev.hywmill.politics.Standing;
import dev.hywmill.politics.WarCounsel;
import dev.hywmill.politics.api.PoliticsView;
import dev.hywmill.politics.war.WarRecord;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * War and peace counsel (post-M5). A Patron or Sworn player suggests that {@code home} declare war on {@code other}, or
 * make peace with it. The rules are {@link WarCounsel}'s. A declared war starts at once (both relations set to
 * {@code warRelation}); a peace, and every finished siege, sets both relations to {@code peaceRelation} and ends the war
 * at once, sending any host between the two home. Stateless.
 */
public final class WarCounselService {
    private WarCounselService() {}

    /**
     * @param chance      the council's chance to follow the counsel (0 when refused before the roll)
     * @param enemyChance for a peace, the enemy's chance to accept it (0 otherwise)
     * @param agreed      the war was declared, or the peace made
     * @param spent       the cost was paid (the suggestion was made)
     */
    public record Result(WarCounsel.Refusal refusal, double chance, double enemyChance, boolean agreed, boolean spent, String detail) {
        public boolean ok() {
            return refusal == WarCounsel.Refusal.OK;
        }
    }

    /**
     * With {@code dryRun} every check runs and nothing is spent or rolled (the Politics screen's verdict). {@code force}
     * (operator command) skips the standing, cooldown and points and both rolls: the war is declared or the peace made.
     * {@code forcedDraw} (dev only) replaces both random draws.
     */
    public static Result suggest(ServerLevel overworld, UUID player, UUID homeId, UUID otherId, WarCounsel.Kind kind, boolean dryRun,
                                 @Nullable Double forcedDraw, boolean force) {
        SettlementSource source = Services.settlements();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord home = ledger.get(homeId);
        VillageRecord other = ledger.get(otherId);
        if (source == null || home == null || other == null || homeId.equals(otherId)) {
            return new Result(WarCounsel.Refusal.SAME_VILLAGE, 0, 0, false, false, "unknown village");
        }
        long now = overworld.getGameTime();
        PoliticsTables.WarCounselRule r = PoliticsService.tables(home).warCounsel();
        PoliticsRecord pr = home.politics.peek(player);
        Standing own = pr == null ? Standing.STRANGER : pr.status;
        Standing standing = force ? Standing.SWORN : PoliticsView.effective(overworld, ledger, source, home, player, own, null);
        OptionalInt points = source.diplomacyPoints(overworld, homeId, player);
        boolean atWar = RelationProjector.atWar(ledger, homeId, otherId);
        boolean truce = home.politics.truceWith(otherId, now) || other.politics.truceWith(homeId, now);
        WarCounsel.Facts f = new WarCounsel.Facts(kind, atWar, truce, home.loneBuilding || other.loneBuilding, standing, now,
                force || pr == null ? -1 : pr.lastWarCounsel, force || points.isEmpty() ? -1 : points.getAsInt());
        WarCounsel.Refusal refusal = WarCounsel.check(f, r);
        if (refusal != WarCounsel.Refusal.OK) {
            return new Result(refusal, 0, 0, false, false, refusalText(refusal, kind, home, other, r, f));
        }
        double ours = SiegeService.defense(overworld, home), theirs = SiegeService.defense(overworld, other);
        int relation = source.villageRelation(overworld, homeId, otherId).orElse(0);
        double chance = force ? 1 : WarCounsel.councilChance(kind, standing, relation, ours, theirs, r);
        double enemy = kind == WarCounsel.Kind.PEACE ? (force ? 1 : WarCounsel.enemyAccepts(ours, theirs, r)) : 0;
        int cost = kind == WarCounsel.Kind.WAR ? r.warPoints() : r.peacePoints();
        String armies = "armies " + Math.round(ours) + " against " + Math.round(theirs);
        String odds = "council " + RaidCounsel.band(chance) + (kind == WarCounsel.Kind.PEACE ? ", " + other.name + " " + RaidCounsel.band(enemy) : "")
                + "; " + armies;
        if (dryRun) {
            return new Result(WarCounsel.Refusal.OK, chance, enemy, false, false, "costs " + cost + " diplomacy point" + (cost == 1 ? "" : "s")
                    + " with " + home.name + "; " + odds);
        }
        for (int i = 0; !force && i < cost; i++) {
            if (!source.consumeDiplomacyPoint(overworld, homeId, player)) {
                return new Result(WarCounsel.Refusal.NO_DIPLOMACY_POINT, chance, enemy, false, i > 0, "no Millénaire diplomacy point left with " + home.name);
            }
        }
        if (!force) {
            home.politics.get(player).lastWarCounsel = now;
        }
        ledger.setDirty();
        String who = PoliticsService.playerName(overworld, player);
        String what = kind == WarCounsel.Kind.WAR ? "war on " + other.name : "peace with " + other.name;
        UUID seedId = UUID.nameUUIDFromBytes((player + ">" + homeId + ">" + otherId + ">" + kind + ">" + now).getBytes(StandardCharsets.UTF_8));
        double draw = forcedDraw != null ? forcedDraw : WarCounsel.draw(seedId.getMostSignificantBits());
        if (!force && draw >= chance) {
            HmLog.info("War counsel: {} asks {} to make {}: the council refuses (chance {}, draw {})", who, home.name, what, fmt(chance), fmt(draw));
            return new Result(WarCounsel.Refusal.OK, chance, enemy, false, true, home.name + "'s council will not make " + what + " (" + odds + ")");
        }
        if (kind == WarCounsel.Kind.WAR) {
            declareWar(overworld, ledger, home, other, now, who + " counselled war");
            HmLog.info("War counsel: {} asks {} to make {}: war declared (chance {}, draw {}{})", who, home.name, what, fmt(chance), fmt(draw),
                    force ? ", forced" : "");
            return new Result(WarCounsel.Refusal.OK, chance, enemy, true, true, home.name + " heeds your counsel and declares war on " + other.name);
        }
        double enemyDraw = forcedDraw != null ? forcedDraw : WarCounsel.draw(seedId.getLeastSignificantBits());
        if (!force && enemyDraw >= enemy) {
            HmLog.info("War counsel: {} asks {} to make {}: the council agrees, {} refuses (enemy chance {}, draw {})", who, home.name, what,
                    other.name, fmt(enemy), fmt(enemyDraw));
            return new Result(WarCounsel.Refusal.OK, chance, enemy, false, true, home.name + " offers peace, but " + other.name
                    + " refuses it (" + armies + ")");
        }
        makePeace(overworld, ledger, home, other, now, who + " brokered it");
        HmLog.info("War counsel: {} asks {} to make {}: peace made (chance {}, enemy chance {}{})", who, home.name, what, fmt(chance), fmt(enemy),
                force ? ", forced" : "");
        return new Result(WarCounsel.Refusal.OK, chance, enemy, true, true, home.name + " and " + other.name + " make peace");
    }

    /** The war starts now: both relations at {@code warRelation}, the war record at war. */
    public static void declareWar(ServerLevel overworld, GarrisonLedger ledger, VillageRecord a, VillageRecord b, long now, String why) {
        declareWar(overworld, ledger, a, b, now, why, true);
    }

    /** {@code callAllies}: the attacked village's allies are called to arms (false inside a call to arms: it cascades itself). */
    public static void declareWar(ServerLevel overworld, GarrisonLedger ledger, VillageRecord a, VillageRecord b, long now, String why,
                                  boolean callAllies) {
        SettlementSource source = Services.settlements();
        PoliticsTables.WarCounselRule r = PoliticsService.tables(a).warCounsel();
        if (source != null) {
            source.setVillageRelation(overworld, a.villageId, b.villageId, r.warRelation());
        }
        WarRecord w = ledger.wars().computeIfAbsent(WarRecord.key(a.villageId, b.villageId), k -> new WarRecord(a.villageId, b.villageId));
        if (w.declare(now) == WarRecord.Change.STARTED) {
            String text = a.name + " declares war on " + b.name + " (" + why + ")";
            PoliticsService.chronicle(overworld, source, a, now, text);
            PoliticsService.chronicle(overworld, source, b, now, text);
            HmLog.info("War: {} <-> {} started (declared)", a.name, b.name);
            if (callAllies) {
                AllianceService.warStarted(overworld, ledger, a, b, true, now);
            }
        }
        ledger.setDirty();
    }

    /**
     * Peace now: both relations at {@code peaceRelation} (above open conflict, so the war does not restart unless the
     * villages drift back into it on their own), the war ended, and any host between the two sent home.
     */
    public static void makePeace(ServerLevel overworld, GarrisonLedger ledger, VillageRecord a, VillageRecord b, long now, String why) {
        SettlementSource source = Services.settlements();
        PoliticsTables.WarCounselRule r = PoliticsService.tables(a).warCounsel();
        if (source != null) {
            source.setVillageRelation(overworld, a.villageId, b.villageId, r.peaceRelation());
        }
        WarRecord w = ledger.wars().get(WarRecord.key(a.villageId, b.villageId));
        boolean ended = w != null && w.makePeace() == WarRecord.Change.ENDED;
        if (w != null && w.idle()) {
            ledger.wars().remove(w.key());
        }
        SiegeService.recall(overworld, ledger, a.villageId, b.villageId, now);
        if (ended) {
            String text = a.name + " and " + b.name + " make peace (" + why + "; relation " + r.peaceRelation() + ")";
            PoliticsService.chronicle(overworld, source, a, now, text);
            PoliticsService.chronicle(overworld, source, b, now, text);
            HmLog.info("War: {} <-> {} ended (peace: {})", a.name, b.name, why);
        }
        ledger.setDirty();
    }

    private static String refusalText(WarCounsel.Refusal refusal, WarCounsel.Kind kind, VillageRecord home, VillageRecord other,
                                      PoliticsTables.WarCounselRule r, WarCounsel.Facts f) {
        int cost = kind == WarCounsel.Kind.WAR ? r.warPoints() : r.peacePoints();
        return switch (refusal) {
            case DISABLED -> "war and peace counsel is turned off";
            case SAME_VILLAGE -> "choose another village";
            case LONE_BUILDING -> "bandits and lone buildings take no part in wars";
            case ALREADY_AT_WAR -> home.name + " is already at war with " + other.name;
            case NOT_AT_WAR -> home.name + " is not at war with " + other.name;
            case TRUCE -> "a truce between " + home.name + " and " + other.name + " is in force";
            case STANDING_TOO_LOW -> home.name + " only weighs war and peace on the counsel of a patron or a sworn friend (you are "
                    + f.standing().name().toLowerCase() + ")";
            case COOLDOWN -> "you counselled " + home.name + " on war and peace recently; wait " + (r.cooldown() - (f.now() - f.lastCounsel())) / 20 + " s";
            case NO_DIPLOMACY_POINT -> "needs " + cost + " Millénaire diplomacy point" + (cost == 1 ? "" : "s") + " with " + home.name
                    + ", you have " + Math.max(0, f.points());
            case OK -> "";
        };
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}

package dev.hywmill.politics.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.RaidCounsel;
import dev.hywmill.politics.Standing;
import dev.hywmill.politics.war.Campaign;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Raid counsel (post-M5): a player on campaign with {@code ally} against {@code enemy} suggests that the ally raid the enemy.
 * The rules are {@link RaidCounsel}'s; on agreement the settlement mod plans the raid its usual way (Millénaire: the
 * "planning a raid" announcement, the raid a day later, and the garrison's M4 raid share with it). Stateless.
 */
public final class RaidCounselService {
    private RaidCounselService() {}

    /**
     * @param chance   the chance the village agrees (0 when refused before the roll)
     * @param agreed   the village agreed and the raid is being planned
     * @param spent    the cost was paid (the suggestion was made)
     */
    public record Result(RaidCounsel.Refusal refusal, double chance, boolean agreed, boolean spent, String detail) {
        public boolean ok() {
            return refusal == RaidCounsel.Refusal.OK;
        }
    }

    /**
     * With {@code dryRun} every check runs and nothing is spent or rolled (the Politics screen's verdict). {@code forcedDraw}
     * (dev commands only) replaces the random draw.
     */
    public static Result suggest(ServerLevel overworld, UUID player, UUID allyId, UUID enemyId, boolean dryRun, @Nullable Double forcedDraw) {
        SettlementSource source = Services.settlements();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord ally = ledger.get(allyId);
        VillageRecord enemy = ledger.get(enemyId);
        if (source == null || ally == null || enemy == null || allyId.equals(enemyId)) {
            return new Result(RaidCounsel.Refusal.NOT_AT_WAR, 0, false, false, "unknown village");
        }
        long now = overworld.getGameTime();
        PoliticsTables.RaidCounselRule r = PoliticsService.tables(ally).raidCounsel();
        Campaign c = RelationProjector.campaignOf(ledger, player);
        boolean onCampaign = c != null && c.active(now) && c.ally().equals(allyId) && c.enemy().equals(enemyId);
        Optional<SettlementSource.RaidInfo> mine = source.raidInfo(overworld, allyId);
        boolean raiding = mine.map(i -> i.target() != null).orElse(false);
        boolean underAttack = source.raidInfo(overworld, enemyId).map(SettlementSource.RaidInfo::underAttack).orElse(false);
        int[] str = source.raidStrength(overworld, allyId).orElse(new int[]{0, 0});
        int[] targetStr = source.raidStrength(overworld, enemyId).orElse(new int[]{0, 0});
        PoliticsRecord rec = dryRun ? java.util.Objects.requireNonNullElseGet(ally.politics.peek(player), PoliticsRecord::new) : ally.politics.get(player);
        OptionalInt points = source.diplomacyPoints(overworld, allyId, player);
        RaidCounsel.Facts f = new RaidCounsel.Facts(RelationProjector.atWar(ledger, allyId, enemyId), onCampaign, raiding, underAttack,
                str[0], targetStr[1], now, rec.lastRaidCounsel, points.isPresent() ? points.getAsInt() : -1);
        RaidCounsel.Refusal refusal = RaidCounsel.check(f, r);
        if (refusal != RaidCounsel.Refusal.OK) {
            return new Result(refusal, 0, false, false, detail(refusal, ally, enemy, r, f, mine.orElse(null), ledger));
        }
        Standing standing = EnvoyService.effective(overworld, ledger, source, ally, player);
        double chance = RaidCounsel.chance(standing, str[0], targetStr[1], r);
        String odds = RaidCounsel.band(chance) + (RaidCounsel.tooStrong(str[0], targetStr[1]) ? ", " + enemy.name + " looks too strong" : "");
        if (dryRun) {
            return new Result(RaidCounsel.Refusal.OK, chance, false, false, "costs " + r.pointCost() + " diplomacy point"
                    + (r.pointCost() == 1 ? "" : "s") + " with " + ally.name + "; " + odds);
        }
        for (int i = 0; i < r.pointCost(); i++) {
            if (!source.consumeDiplomacyPoint(overworld, allyId, player)) {
                return new Result(RaidCounsel.Refusal.NO_DIPLOMACY_POINT, chance, false, i > 0, "no Millénaire diplomacy point left with " + ally.name);
            }
        }
        rec.lastRaidCounsel = now;
        ledger.setDirty();
        UUID seedId = UUID.nameUUIDFromBytes((player + ">" + allyId + ">" + enemyId + ">raid>" + now).getBytes(StandardCharsets.UTF_8));
        double draw = forcedDraw != null ? forcedDraw : RaidCounsel.draw(seedId.getMostSignificantBits() ^ seedId.getLeastSignificantBits());
        String who = PoliticsService.playerName(overworld, player);
        if (draw >= chance) {
            HmLog.info("Raid counsel: {} asks {} to raid {}: refused (chance {}, draw {})", who, ally.name, enemy.name, fmt(chance), fmt(draw));
            return new Result(RaidCounsel.Refusal.OK, chance, false, true, ally.name + "'s elders will not raid " + enemy.name + " yet ("
                    + odds + "); ask again in a day");
        }
        if (!source.planRaid(overworld, allyId, enemyId)) {
            HmLog.info("Raid counsel: {} asks {} to raid {}: agreed, but the raid could not be planned", who, ally.name, enemy.name);
            return new Result(RaidCounsel.Refusal.ALREADY_RAIDING, chance, false, true, ally.name + " agreed but could not plan the raid");
        }
        PoliticsService.chronicle(overworld, source, ally, now, who + " counselled a raid on " + enemy.name + "; " + ally.name + " is planning it");
        HmLog.info("Raid counsel: {} asks {} to raid {}: agreed (chance {}, draw {}); raid planned", who, ally.name, enemy.name, fmt(chance), fmt(draw));
        return new Result(RaidCounsel.Refusal.OK, chance, true, true, ally.name + " heeds your counsel: a raid on " + enemy.name
                + " is being planned (it sets out in about a day)");
    }

    private static String detail(RaidCounsel.Refusal refusal, VillageRecord ally, VillageRecord enemy, PoliticsTables.RaidCounselRule r,
                                 RaidCounsel.Facts f, @Nullable SettlementSource.RaidInfo mine, GarrisonLedger ledger) {
        return switch (refusal) {
            case DISABLED -> "raid counsel is turned off";
            case NOT_AT_WAR -> ally.name + " is not at war with " + enemy.name;
            case NOT_ON_CAMPAIGN -> "you must be on campaign with " + ally.name + " against " + enemy.name;
            case ALREADY_RAIDING -> {
                VillageRecord t = mine == null || mine.target() == null ? null : ledger.get(mine.target());
                yield ally.name + " is already " + (mine != null && mine.raidStart() > 0 ? "raiding " : "planning a raid on ")
                        + (t == null ? "another village" : t.name);
            }
            case TARGET_UNDER_ATTACK -> enemy.name + " is already under attack";
            case NO_RAIDERS -> ally.name + " has no raiders to send";
            case COOLDOWN -> "you counselled " + ally.name + " recently; wait " + (r.cooldown() - (f.now() - f.lastCounsel())) / 20 + " s";
            case NO_DIPLOMACY_POINT -> "needs " + r.pointCost() + " Millénaire diplomacy point" + (r.pointCost() == 1 ? "" : "s") + " with "
                    + ally.name + ", you have " + Math.max(0, f.points());
            case OK -> "";
        };
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}

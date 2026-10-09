package dev.hywmill.politics.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.realm.Treaty;
import dev.hywmill.politics.realm.WarSides;
import dev.hywmill.politics.war.Vassalage;
import dev.hywmill.politics.war.WarRecord;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Post-M5 realms (docs/realm-design.md): treaties signed and kept by relation, villages bound to a war taking sides, peace
 * spreading to those who joined for an ally, and the realm queries (sovereigns, subjects) the rest of the mod asks. The
 * ledger is the truth; Millénaire relations carry the consequences (a village that joins a war drops to −100 with the enemy,
 * and the war follows by the existing rule).
 */
public final class RealmService {
    private RealmService() {}

    public static boolean enabled() {
        return HywMillConfig.REALMS_ENABLED.get();
    }

    // ------------------------------------------------------------------ realm queries

    /** The subject tie of {@code v} in force (it is a vassal or a province), or null. */
    @Nullable
    public static Vassalage tieOf(GarrisonLedger ledger, UUID v, long now) {
        for (Vassalage t : ledger.vassalages()) {
            if (t.vassal.equals(v) && !t.over(now)) {
                return t;
            }
        }
        return null;
    }

    /** The sovereign of {@code v}: its overlord if it is a subject, else itself. */
    public static UUID sovereignOf(GarrisonLedger ledger, UUID v, long now) {
        Vassalage t = tieOf(ledger, v, now);
        return t == null ? v : t.overlord;
    }

    /** {@code v} is a province (conquered) of some realm. */
    public static boolean isProvince(GarrisonLedger ledger, UUID v, long now) {
        Vassalage t = tieOf(ledger, v, now);
        return t != null && t.province;
    }

    /** The ties of the subjects of {@code sovereign}, provinces first. */
    public static List<Vassalage> subjectsOf(GarrisonLedger ledger, UUID sovereign, long now) {
        List<Vassalage> out = new ArrayList<>();
        for (Vassalage t : ledger.vassalages()) {
            if (t.overlord.equals(sovereign) && !t.over(now)) {
                out.add(t);
            }
        }
        out.sort((p, q) -> Boolean.compare(q.province, p.province));
        return out;
    }

    public static int provinceCount(GarrisonLedger ledger, UUID sovereign, long now) {
        return (int) subjectsOf(ledger, sovereign, now).stream().filter(t -> t.province).count();
    }

    /** The two belong to one realm (one is the other's sovereign, or both answer to the same one). */
    public static boolean sameRealm(GarrisonLedger ledger, UUID x, UUID y, long now) {
        return !x.equals(y) && sovereignOf(ledger, x, now).equals(sovereignOf(ledger, y, now));
    }

    @Nullable
    public static Treaty treaty(GarrisonLedger ledger, UUID x, UUID y) {
        return ledger.treaties().get(Treaty.key(x, y));
    }

    /** The treaty in force between the two realms' heads (a subject's treaties are its sovereign's). */
    @Nullable
    public static Treaty realmTreaty(GarrisonLedger ledger, UUID x, UUID y, long now) {
        return treaty(ledger, sovereignOf(ledger, x, now), sovereignOf(ledger, y, now));
    }

    /** The pair's relation: the lower of the two directions (null: unknown). */
    @Nullable
    static Integer relation(ServerLevel overworld, SettlementSource source, UUID x, UUID y) {
        OptionalInt a = source.villageRelation(overworld, x, y), b = source.villageRelation(overworld, y, x);
        if (a.isEmpty() && b.isEmpty()) {
            return null;
        }
        return Math.min(a.orElse(Integer.MAX_VALUE), b.orElse(Integer.MAX_VALUE));
    }

    // ------------------------------------------------------------------ treaties

    /**
     * Treaty upkeep and signing (each village AI interval): treaties that no longer hold fall back or end; pairs of
     * independent villages within reach whose relation opens a higher tier sign it, one time in four per day.
     */
    public static void treaties(ServerLevel overworld, GarrisonLedger ledger, long now, long interval) {
        SettlementSource source = Services.settlements();
        if (source == null || !enabled()) {
            return;
        }
        loyalty(overworld, ledger, now);
        for (Treaty t : new ArrayList<>(ledger.treaties().values())) {
            VillageRecord a = ledger.get(t.a), b = ledger.get(t.b);
            Integer rel = a == null || b == null ? null : relation(overworld, source, t.a, t.b);
            String why = null;
            Treaty.Kind kind = rel == null ? null : Treaty.standing(t.kind, rel);
            if (a == null || b == null) {
                why = "a village is gone";
            } else if (RelationProjector.atWar(ledger, t.a, t.b)) {
                why = "they are at war";
            } else if (tieOf(ledger, t.a, now) != null || tieOf(ledger, t.b, now) != null) {
                why = "one of them answers to a sovereign now";
            } else if (kind == null) {
                why = "their relations have soured";
            }
            if (why != null) {
                ledger.treaties().remove(t.key());
                ledger.setDirty();
                if (a != null && b != null) {
                    news(overworld, source, a, b, now, "The " + t.kind.label() + " between " + a.name + " and " + b.name + " is over (" + why + ")");
                }
            } else if (kind != t.kind) {
                Treaty.Kind was = t.kind;
                t.kind = kind;
                ledger.setDirty();
                news(overworld, source, a, b, now, a.name + " and " + b.name + "'s " + was.label() + " cools to a " + kind.label());
            }
        }
        List<VillageRecord> recs = new ArrayList<>();
        for (VillageRecord r : ledger.all()) {
            if (!r.loneBuilding && tieOf(ledger, r.villageId, now) == null) {
                recs.add(r);
            }
        }
        seekProtection(overworld, ledger, now, interval);
        double p = Treaty.SIGN_CHANCE * interval / (double) PoliticsTables.DAY;
        SplittableRandom rnd = new SplittableRandom(now * 31 + ledger.treaties().size());
        for (int i = 0; i < recs.size(); i++) {
            for (int j = i + 1; j < recs.size(); j++) {
                VillageRecord x = recs.get(i), y = recs.get(j);
                if (x.center.distSqr(y.center) > (double) Treaty.REACH * Treaty.REACH || RelationProjector.atWar(ledger, x.villageId, y.villageId)) {
                    continue;
                }
                Integer rel = relation(overworld, source, x.villageId, y.villageId);
                Treaty.Kind open = rel == null ? null : Treaty.qualifies(rel);
                Treaty cur = treaty(ledger, x.villageId, y.villageId);
                if (open == null || (cur != null && cur.kind.ordinal() >= open.ordinal()) || rnd.nextDouble() >= p) {
                    continue;
                }
                sign(overworld, ledger, x, y, open, now);
            }
        }
    }

    /**
     * Vassalage by choice (docs/realm-design.md §1): a village at war, bound by a military alliance to one at least three times
     * its garrison, may swear fealty to it for protection (one time in ten per day).
     */
    static void seekProtection(ServerLevel overworld, GarrisonLedger ledger, long now, long interval) {
        double p = 0.1 * interval / (double) PoliticsTables.DAY;
        for (Treaty t : new ArrayList<>(ledger.treaties().values())) {
            if (!t.military()) {
                continue;
            }
            VillageRecord x = ledger.get(t.a), y = ledger.get(t.b);
            if (x == null || y == null || x.hywRoster == null || y.hywRoster == null) {
                continue;
            }
            VillageRecord weak = x.hywRoster.live() <= y.hywRoster.live() ? x : y, strong = weak == x ? y : x;
            boolean atWar = ledger.wars().values().stream().anyMatch(w -> w.atWar() && w.involves(weak.villageId));
            if (atWar && strong.hywRoster.live() >= 3 * Math.max(1, weak.hywRoster.live()) && tieOf(ledger, strong.villageId, now) == null
                    && new SplittableRandom(now ^ weak.villageId.getMostSignificantBits()).nextDouble() < p) {
                news(overworld, Services.settlements(), weak, strong, now, weak.name + ", hard pressed in war, seeks the protection of its ally " + strong.name);
                dev.hywmill.garrison.service.SiegeService.swearFealty(overworld, ledger, weak, strong, now);
            }
        }
    }

    /** The two sign (or raise their treaty to) {@code kind}. */
    public static void sign(ServerLevel overworld, GarrisonLedger ledger, VillageRecord x, VillageRecord y, Treaty.Kind kind, long now) {
        Treaty cur = treaty(ledger, x.villageId, y.villageId);
        if (cur == null) {
            ledger.treaties().put(Treaty.key(x.villageId, y.villageId), new Treaty(x.villageId, y.villageId, kind, now));
        } else {
            cur.kind = kind;
            cur.since = now;
        }
        ledger.setDirty();
        news(overworld, Services.settlements(), x, y, now, x.name + " and " + y.name + " sign a " + kind.label());
    }

    /** Admin: ends the pair's treaty. */
    public static boolean dissolve(ServerLevel overworld, GarrisonLedger ledger, VillageRecord x, VillageRecord y, long now) {
        Treaty t = ledger.treaties().remove(Treaty.key(x.villageId, y.villageId));
        if (t != null) {
            ledger.setDirty();
            news(overworld, Services.settlements(), x, y, now, "The " + t.kind.label() + " between " + x.name + " and " + y.name + " is dissolved");
        }
        return t != null;
    }

    // ------------------------------------------------------------------ war obligations

    /**
     * A war has started between {@code x} and {@code y} (docs/realm-design.md §2): every village bound to either by a military
     * alliance or a subject tie takes a side. Joining drops its relation with the enemy to −100 (the war follows by the
     * existing rule, a day later, and obliges its own allies in turn); a village bound to both breaks with the weaker bond.
     */
    public static void onWarStarted(ServerLevel overworld, GarrisonLedger ledger, VillageRecord x, VillageRecord y, long now) {
        SettlementSource source = Services.settlements();
        if (source == null || !enabled()) {
            return;
        }
        List<WarSides.Bond> bonds = new ArrayList<>();
        for (VillageRecord c : ledger.all()) {
            if (c == x || c == y || c.loneBuilding) {
                continue;
            }
            WarSides.Tie tx = tie(ledger, c.villageId, x.villageId, now), ty = tie(ledger, c.villageId, y.villageId, now);
            // an ally far from the enemy stays out of it; subjects always answer
            if (tx == WarSides.Tie.ALLIANCE && c.center.distSqr(y.center) > (double) Treaty.REACH * Treaty.REACH) {
                tx = WarSides.Tie.NONE;
            }
            if (ty == WarSides.Tie.ALLIANCE && c.center.distSqr(x.center) > (double) Treaty.REACH * Treaty.REACH) {
                ty = WarSides.Tie.NONE;
            }
            if (tx == WarSides.Tie.NONE && ty == WarSides.Tie.NONE) {
                continue;
            }
            Integer rx = relation(overworld, source, c.villageId, x.villageId), ry = relation(overworld, source, c.villageId, y.villageId);
            bonds.add(new WarSides.Bond(c.villageId, tx, rx == null ? 0 : rx, c.culture.equals(x.culture), ty, ry == null ? 0 : ry,
                    c.culture.equals(y.culture)));
        }
        for (WarSides.Decision d : WarSides.decide(x.villageId, y.villageId, bonds)) {
            VillageRecord c = ledger.get(d.village()), side = ledger.get(d.side()), enemy = ledger.get(d.enemy());
            if (c == null || side == null || enemy == null || RelationProjector.atWar(ledger, c.villageId, enemy.villageId)) {
                continue;
            }
            if (d.brokenWith() != null) {
                breakWith(overworld, ledger, c, enemy, now);
            }
            join(overworld, ledger, c, side, enemy, now, d.brokenWith() != null
                    ? c.name + ", bound to both " + side.name + " and " + enemy.name + ", sides with " + side.name + " and breaks with " + enemy.name
                    : c.name + " honours its " + bondLabel(ledger, c.villageId, side.villageId, now) + " and takes up arms against " + enemy.name);
        }
    }

    /** {@code c} takes {@code side}'s part against {@code enemy}: relation −100, a war record marked as joined for {@code side}. */
    public static void join(ServerLevel overworld, GarrisonLedger ledger, VillageRecord c, VillageRecord side, VillageRecord enemy, long now, String text) {
        SettlementSource source = Services.settlements();
        if (source != null) {
            source.setVillageRelation(overworld, c.villageId, enemy.villageId, -100);
        }
        WarRecord w = ledger.wars().computeIfAbsent(WarRecord.key(c.villageId, enemy.villageId), k -> new WarRecord(c.villageId, enemy.villageId));
        if (w.conflictSince < 0) {
            w.conflictSince = now;
        }
        w.joiner = c.villageId;
        w.joinedFor = side.villageId;
        ledger.setDirty();
        PoliticsService.chronicle(overworld, source, c, now, text);
        PoliticsService.chronicle(overworld, source, enemy, now, text);
        PoliticsService.chronicle(overworld, source, side, now, text);
        HmLog.info("Realm: {}", text);
    }

    /** {@code c} breaks every bond with {@code other}: their treaty ends, a subject tie between them is thrown off. */
    static void breakWith(ServerLevel overworld, GarrisonLedger ledger, VillageRecord c, VillageRecord other, long now) {
        ledger.treaties().remove(Treaty.key(c.villageId, other.villageId));
        Vassalage own = tieOf(ledger, c.villageId, now);
        if (own != null && own.overlord.equals(other.villageId)) {
            ledger.rebels().put(c.villageId, other.villageId);
        }
        ledger.vassalages().removeIf(t -> !t.over(now) && ((t.vassal.equals(c.villageId) && t.overlord.equals(other.villageId))
                || (t.vassal.equals(other.villageId) && t.overlord.equals(c.villageId))));
        ledger.setDirty();
    }

    /** How {@code c} is bound to {@code p} for war (a pact or a defensive pact does not oblige). */
    static WarSides.Tie tie(GarrisonLedger ledger, UUID c, UUID p, long now) {
        Vassalage tc = tieOf(ledger, c, now);
        if (tc != null && tc.overlord.equals(p)) {
            return tc.province ? WarSides.Tie.PROVINCE : WarSides.Tie.VASSAL;
        }
        Vassalage tp = tieOf(ledger, p, now);
        if (tp != null && tp.overlord.equals(c)) {
            return WarSides.Tie.SOVEREIGN;
        }
        Treaty t = treaty(ledger, c, p);
        return t != null && t.military() ? WarSides.Tie.ALLIANCE : WarSides.Tie.NONE;
    }

    static String bondLabel(GarrisonLedger ledger, UUID c, UUID p, long now) {
        return switch (tie(ledger, c, p, now)) {
            case PROVINCE -> "duty as a province";
            case VASSAL -> "oath of fealty";
            case SOVEREIGN -> "duty to its subject";
            case ALLIANCE -> "military alliance";
            case NONE -> "bond";
        };
    }

    /**
     * {@code x} and {@code y} have made peace: villages that joined the war for one of them against the other make peace with
     * it too, and so do their provinces.
     */
    public static void onPeace(ServerLevel overworld, GarrisonLedger ledger, VillageRecord x, VillageRecord y, long now) {
        if (!enabled()) {
            return;
        }
        for (WarRecord w : new ArrayList<>(ledger.wars().values())) {
            if (w.joiner == null || w.joinedFor == null || !(w.atWar() || w.conflictSince >= 0)) {
                continue;
            }
            UUID foe = w.other(w.joiner);
            if ((w.joinedFor.equals(x.villageId) && foe.equals(y.villageId)) || (w.joinedFor.equals(y.villageId) && foe.equals(x.villageId))) {
                VillageRecord j = ledger.get(w.joiner), f = ledger.get(foe);
                if (j != null && f != null) {
                    WarCounselService.makePeace(overworld, ledger, j, f, now, "its war for " + (w.joinedFor.equals(x.villageId) ? x.name : y.name) + " is over");
                }
            }
        }
    }

    // ------------------------------------------------------------------ loyalty and rebellion

    /** A loyalty event for {@code subject} (if it is a subject): {@code delta} points. */
    public static void loyaltyEvent(GarrisonLedger ledger, UUID subject, double delta, long now) {
        Vassalage t = tieOf(ledger, subject, now);
        if (t != null && t.indefinite()) {
            t.loyalty = dev.hywmill.politics.realm.Loyalty.clamp(t.loyalty + delta);
            ledger.setDirty();
        }
    }

    /**
     * Each village AI interval (docs/realm-design.md §5): every subject's loyalty drifts once per Minecraft day (towards its
     * resting point, less for a foreign culture and for distance), and a disloyal subject may rebel that day.
     */
    public static void loyalty(ServerLevel overworld, GarrisonLedger ledger, long now) {
        if (!enabled()) {
            return;
        }
        long day = now / PoliticsTables.DAY;
        for (Vassalage legacy : new ArrayList<>(ledger.vassalages())) {
            if (!legacy.indefinite() && !legacy.over(now)) {
                // a 21-day fealty from before realms: it lasts until it rebels now
                ledger.vassalages().remove(legacy);
                ledger.vassalages().add(Vassalage.subject(legacy.vassal, legacy.overlord, now, false));
                ledger.setDirty();
            }
        }
        for (Vassalage t : new ArrayList<>(ledger.vassalages())) {
            if (!t.indefinite() || t.loyaltyDay >= day) {
                continue;
            }
            VillageRecord sub = ledger.get(t.vassal), sov = ledger.get(t.overlord);
            if (sub == null || sov == null) {
                ledger.vassalages().remove(t);
                ledger.setDirty();
                continue;
            }
            long days = Math.min(5, day - Math.max(t.loyaltyDay, day - 5));
            double dist = Math.sqrt(sub.center.distSqr(sov.center));
            for (long i = 0; i < days; i++) {
                t.loyalty = dev.hywmill.politics.realm.Loyalty.daily(t.loyalty, t.province, sub.culture.equals(sov.culture), dist);
            }
            t.loyaltyDay = day;
            ledger.setDirty();
            if (t.province) {
                // a province has no wars of its own: only its sovereign's (so it never fights its sovereign's friends)
                for (WarRecord w : new ArrayList<>(ledger.wars().values())) {
                    UUID other = w.atWar() && w.involves(sub.villageId) ? w.other(sub.villageId) : null;
                    VillageRecord o = other == null ? null : ledger.get(other);
                    if (o != null && !other.equals(sov.villageId) && !RelationProjector.atWar(ledger, sov.villageId, other)) {
                        WarCounselService.makePeace(overworld, ledger, sub, o, now, sub.name + " is a province of " + sov.name + " and has no wars of its own");
                    }
                }
            }
            int subLive = sub.hywRoster == null ? 0 : sub.hywRoster.live(), sovLive = sov.hywRoster == null ? 0 : sov.hywRoster.live();
            double p = dev.hywmill.politics.realm.Loyalty.rebelChance(t.loyalty, t.province, sovLive < subLive);
            if (p > 0 && new SplittableRandom(day * 31 + t.vassal.getLeastSignificantBits()).nextDouble() < p) {
                rebel(overworld, ledger, t, now);
            }
        }
    }

    /** {@code t}'s subject rebels: it is free, its relation with its old sovereign falls to −100 and a war follows. */
    public static void rebel(ServerLevel overworld, GarrisonLedger ledger, Vassalage t, long now) {
        SettlementSource source = Services.settlements();
        VillageRecord sub = ledger.get(t.vassal), sov = ledger.get(t.overlord);
        ledger.vassalages().remove(t);
        ledger.rebels().put(t.vassal, t.overlord);
        if (source != null) {
            source.setVillageRelation(overworld, t.vassal, t.overlord, -100);
        }
        ledger.setDirty();
        if (sub != null && sov != null) {
            news(overworld, source, sub, sov, now, sub.name + " rises against " + sov.name + " (loyalty " + Math.round(t.loyalty) + "): the "
                    + t.kindLabel() + " throws off its sovereign" + (t.province ? ", and its garrison takes its own colours again" : ""));
        }
    }

    // ------------------------------------------------------------------ siege aims and their outcomes

    /** The attacker's aim for a siege of {@code t} (docs/realm-design.md §3), drawn with {@code seed}. */
    public static dev.hywmill.politics.realm.SiegeAims.Aim chooseAim(GarrisonLedger ledger, VillageRecord a, VillageRecord t, long now, long seed) {
        int grudge = 0;
        for (dev.hywmill.politics.war.BattleReport b : ledger.battles()) {
            if ((b.attackerId.equals(a.villageId) && b.targetId.equals(t.villageId)) || (b.attackerId.equals(t.villageId) && b.targetId.equals(a.villageId))) {
                grudge++;
            }
        }
        UUID sov = sovereignOf(ledger, a.villageId, now);
        boolean rebelled = sov.equals(ledger.rebels().get(t.villageId));
        dev.hywmill.politics.realm.SiegeAims.Facts f = new dev.hywmill.politics.realm.SiegeAims.Facts(a.tier.ordinal(), t.tier.ordinal(),
                a.culture.equals(t.culture), Math.sqrt(a.center.distSqr(t.center)), grudge, rebelled, provinceCount(ledger, sov, now),
                dev.hywmill.politics.realm.SiegeAims.capacity(a.tier.ordinal()), HywMillConfig.RAZE_ENABLED.get() && t.controllerPlayerId == null,
                enabled() && !isProvince(ledger, a.villageId, now));
        return dev.hywmill.politics.realm.SiegeAims.choose(f, seed);
    }

    /**
     * {@code province} is annexed to {@code sovereign}'s realm (docs/realm-design.md §4): its own subjects pass to the
     * conqueror, its treaties and old ties end, it is at peace with all but its sovereign's enemies, and its garrison is
     * disbanded: a new one, mostly of the sovereign's culture and in its colours, is raised at once by the garrison slot.
     */
    public static void annex(ServerLevel overworld, GarrisonLedger ledger, VillageRecord province, VillageRecord sovereign, long now) {
        SettlementSource source = Services.settlements();
        UUID sov = sovereignOf(ledger, sovereign.villageId, now); // a province's conquests go to its own sovereign
        VillageRecord head = ledger.get(sov) != null ? ledger.get(sov) : sovereign;
        for (Vassalage t : subjectsOf(ledger, province.villageId, now)) {
            ledger.vassalages().remove(t);
            Vassalage moved = Vassalage.subject(t.vassal, head.villageId, now, t.province);
            moved.loyalty = t.loyalty;
            ledger.vassalages().add(moved);
        }
        ledger.vassalages().removeIf(t -> t.vassal.equals(province.villageId));
        ledger.treaties().values().removeIf(t -> t.involves(province.villageId));
        ledger.vassalages().add(Vassalage.subject(province.villageId, head.villageId, now, true));
        ledger.rebels().remove(province.villageId);
        if (source != null) {
            source.setVillageRelation(overworld, province.villageId, head.villageId, 100);
        }
        for (WarRecord w : new ArrayList<>(ledger.wars().values())) {
            if (!w.involves(province.villageId) || !(w.atWar() || w.conflictSince >= 0)) {
                continue;
            }
            UUID other = w.other(province.villageId);
            VillageRecord o = ledger.get(other);
            if (o != null && !RelationProjector.atWar(ledger, head.villageId, other)) {
                WarCounselService.makePeace(overworld, ledger, province, o, now, province.name + " is a province of " + head.name + " now");
            }
        }
        for (WarRecord w : new ArrayList<>(ledger.wars().values())) {
            if (w.atWar() && w.involves(head.villageId) && !w.involves(province.villageId)) {
                VillageRecord enemy = ledger.get(w.other(head.villageId));
                if (enemy != null) {
                    join(overworld, ledger, province, head, enemy, now, province.name + ", a province of " + head.name + " now, takes up its war against " + enemy.name);
                }
            }
        }
        int disbanded = dev.hywmill.garrison.service.GarrisonService.disband(overworld, province, now);
        ledger.setDirty();
        news(overworld, source, province, head, now, province.name + " is annexed to the realm of " + head.name + ": its old garrison ("
                + disbanded + ") is disbanded and a garrison of " + head.name + " takes its place");
    }

    /**
     * {@code v} is razed by {@code by}: Millénaire's record and villagers go (the negation wand's mechanics; the buildings stay
     * as ruins), its garrison is lost, and every bond, war and siege it had ends; its subjects are free.
     */
    public static void raze(ServerLevel overworld, GarrisonLedger ledger, VillageRecord v, VillageRecord by, long now) {
        SettlementSource source = Services.settlements();
        String text = v.name + " is razed by " + by.name + ": its people are scattered and only ruins remain";
        PoliticsService.chronicle(overworld, source, by, now, text);
        int removed = source != null ? source.raze(overworld, v.villageId) : -1;
        dev.hywmill.garrison.service.GarrisonService.disband(overworld, v, now);
        if (v.hywRoster != null) {
            for (dev.hywmill.garrison.RosterEntry e : new ArrayList<>(v.hywRoster.arsenal())) {
                dev.hywmill.garrison.service.GarrisonService.stow(overworld, e);
                v.hywRoster.disarm(e);
            }
        }
        ledger.treaties().values().removeIf(t -> t.involves(v.villageId));
        ledger.vassalages().removeIf(t -> t.vassal.equals(v.villageId) || t.overlord.equals(v.villageId));
        ledger.wars().values().removeIf(w -> w.involves(v.villageId));
        ledger.campaigns().removeIf(c -> c.ally().equals(v.villageId) || c.enemy().equals(v.villageId));
        ledger.tributes().removeIf(t -> t.payer.equals(v.villageId) || t.payee.equals(v.villageId));
        ledger.rebels().remove(v.villageId);
        ledger.remove(v.villageId);
        HmLog.info("Realm: {} ({} villager(s) removed)", text, removed);
    }

    /** The loser of a punitive siege is marked broken: it sues for peace within a day (see {@link #sueForPeace}). */
    public static void punished(GarrisonLedger ledger, VillageRecord loser, VillageRecord winner, long now) {
        WarRecord w = ledger.wars().get(WarRecord.key(loser.villageId, winner.villageId));
        if (w != null) {
            w.punished = loser.villageId;
            w.punishedAt = now;
            ledger.setDirty();
        }
    }

    /** Whether {@code v} lies broken after a punitive siege (no siege of its own while it does). */
    public static boolean broken(GarrisonLedger ledger, UUID v, long now) {
        for (WarRecord w : ledger.wars().values()) {
            if (v.equals(w.punished) && now - w.punishedAt < PoliticsTables.DAY) {
                return true;
            }
        }
        return false;
    }

    /** Each village AI interval: a village broken by a punitive siege sues for peace (about four times in five within a day). */
    public static void sueForPeace(ServerLevel overworld, GarrisonLedger ledger, long now, long interval) {
        double p = 1 - Math.pow(0.2, interval / (double) PoliticsTables.DAY);
        for (WarRecord w : new ArrayList<>(ledger.wars().values())) {
            if (w.punished == null || !w.atWar()) {
                continue;
            }
            if (now - w.punishedAt >= PoliticsTables.DAY) {
                w.punished = null;
                continue;
            }
            if (new SplittableRandom(now ^ w.punished.getLeastSignificantBits()).nextDouble() < p) {
                VillageRecord loser = ledger.get(w.punished), winner = ledger.get(w.other(w.punished));
                if (loser != null && winner != null) {
                    WarCounselService.makePeace(overworld, ledger, loser, winner, now, loser.name + ", broken by its punishment, sued for peace");
                }
            }
        }
    }

    static void news(ServerLevel overworld, @Nullable SettlementSource source, VillageRecord a, VillageRecord b, long now, String text) {
        PoliticsService.chronicle(overworld, source, a, now, text);
        PoliticsService.chronicle(overworld, source, b, now, text);
        HmLog.info("Realm: {}", text);
    }
}

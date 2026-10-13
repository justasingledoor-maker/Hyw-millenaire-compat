package dev.hywmill.politics.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.realm.NationalArmy;
import dev.hywmill.politics.realm.RealmCall;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /** 1.10: how many vassals (not provinces) a sovereign has. */
    public static int vassalCount(GarrisonLedger ledger, UUID sovereign, long now) {
        return (int) subjectsOf(ledger, sovereign, now).stream().filter(t -> !t.province).count();
    }

    /** 1.10: whether a sovereign has room for another province (by its tier). */
    public static boolean provinceRoom(GarrisonLedger ledger, VillageRecord sovereign, long now) {
        return provinceCount(ledger, sovereign.villageId, now) < dev.hywmill.politics.realm.SiegeAims.capacity(sovereign.tier.ordinal());
    }

    /** 1.10: a vassal this loyal, sworn this long and this near its sovereign may be made a province of the realm. */
    public static final double INTEGRATE_LOYALTY = 45, INTEGRATE_REACH = 1200, INTEGRATE_DAILY = 0.3;
    public static final long INTEGRATE_AFTER = 2 * PoliticsTables.DAY;

    /**
     * 1.10: a realm folds its loyal vassals in: one sworn two days or more, loyal enough and within reach of its sovereign
     * becomes a province (three times in ten a day each) while the sovereign has room for one. One a sovereign at a time.
     */
    static void integrate(ServerLevel overworld, GarrisonLedger ledger, long now, long interval) {
        integrate(overworld, ledger, now, interval, false);
    }

    /** {@code force} (an admin's): any loyal vassal within reach, now, however long sworn. Returns how many were folded in. */
    public static int integrate(ServerLevel overworld, GarrisonLedger ledger, long now, long interval, boolean force) {
        double p = force ? 1 : INTEGRATE_DAILY * interval / (double) PoliticsTables.DAY;
        java.util.Set<UUID> done = new java.util.HashSet<>();
        for (Vassalage t : new ArrayList<>(ledger.vassalages())) {
            if (t.province || t.over(now) || (!force && (!t.indefinite() || now - t.since < INTEGRATE_AFTER)) || t.loyalty < INTEGRATE_LOYALTY
                    || done.contains(t.overlord)) {
                continue;
            }
            VillageRecord v = ledger.get(t.vassal), o = ledger.get(t.overlord);
            if (v == null || o == null || v.loneBuilding || Math.sqrt(v.center.distSqr(o.center)) > INTEGRATE_REACH || !provinceRoom(ledger, o, now)
                    || dev.hywmill.garrison.service.SiegeService.besieged(ledger, v.villageId)
                    || new SplittableRandom(now ^ v.villageId.getMostSignificantBits() ^ 0x696E74L).nextDouble() >= p) {
                continue;
            }
            done.add(t.overlord);
            news(overworld, Services.settlements(), v, o, now, o.name + " folds its vassal " + v.name + " into the realm: " + v.name + " is a province now");
            HmLog.info("Realm: {} folds its vassal {} into the realm (loyalty {})", o.name, v.name, Math.round(t.loyalty));
            annex(overworld, ledger, v, o, now);
        }
        return done.size();
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
        unity(overworld, ledger, now);
        if (now / PoliticsTables.DAY != (now - interval) / PoliticsTables.DAY) {
            RealmDiplomacy.heal(overworld, ledger, source, now); // 1.7: old grudges fade at peace (§12)
        }
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
        integrate(overworld, ledger, now, interval); // 1.10: loyal vassals become provinces
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
        double p = 0.04 * interval / (double) PoliticsTables.DAY; // 1.10: rarer (vassals are rarer than provinces)
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
     * A war has started between {@code x} and {@code y}. Realm unity (docs/realm-design.md §8): it is a war of their whole realms
     * at once (two members of one realm at war go to its council instead); then the villages bound to either realm's head by a
     * military alliance take a side (§2). Lone buildings take no part in realm wars.
     */
    public static void onWarStarted(ServerLevel overworld, GarrisonLedger ledger, VillageRecord x, VillageRecord y, long now) {
        SettlementSource source = Services.settlements();
        if (source == null || !enabled()) {
            return;
        }
        VillageRecord ax = x, ay = y;
        if (!x.loneBuilding && !y.loneBuilding) {
            UUID hx = sovereignOf(ledger, x.villageId, now), hy = sovereignOf(ledger, y.villageId, now);
            if (hx.equals(hy)) {
                feud(overworld, ledger, x, y, now);
                return;
            }
            realmWar(overworld, ledger, hx, hy, now);
            ax = ledger.get(hx) != null ? ledger.get(hx) : x;
            ay = ledger.get(hy) != null ? ledger.get(hy) : y;
        }
        alliesTakeSides(overworld, ledger, ax, ay, now);
    }

    /**
     * Every village bound to {@code x} or {@code y} by a military alliance (and not of either's realm, which is already at war)
     * takes a side (docs/realm-design.md §2). Joining drops its relation with the enemy to −100 (the war follows by the existing
     * rule, a day later, and obliges its own allies in turn); a village bound to both breaks with the weaker bond.
     */
    static void alliesTakeSides(ServerLevel overworld, GarrisonLedger ledger, VillageRecord x, VillageRecord y, long now) {
        SettlementSource source = Services.settlements();
        Set<UUID> realms = new HashSet<>(realmOf(ledger, x.villageId, now));
        realms.addAll(realmOf(ledger, y.villageId, now));
        List<WarSides.Bond> bonds = new ArrayList<>();
        for (VillageRecord c : ledger.all()) {
            if (c == x || c == y || c.loneBuilding || realms.contains(c.villageId)) {
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
        realmPeace(overworld, ledger, x, y, now);
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
            warWithRealm(overworld, ledger, sov.villageId, sub, now); // realm unity: the whole realm is at war with it, at once
        }
    }

    // ------------------------------------------------------------------ realm unity (docs/realm-design.md §8)

    /** The realm of {@code head}: the head first, then its subjects (provinces first). */
    public static List<UUID> members(GarrisonLedger ledger, UUID head, long now) {
        List<UUID> out = new ArrayList<>();
        out.add(head);
        subjectsOf(ledger, head, now).forEach(t -> out.add(t.vassal));
        return out;
    }

    /** The realm {@code v} belongs to: its sovereign's (just itself when it neither has nor is a subject). */
    public static List<UUID> realmOf(GarrisonLedger ledger, UUID v, long now) {
        return members(ledger, sovereignOf(ledger, v, now), now);
    }

    static int soldiers(@Nullable VillageRecord v) {
        return v == null || v.hywRoster == null ? 0 : v.hywRoster.live();
    }

    static boolean atWarWithAny(GarrisonLedger ledger, UUID v, List<UUID> others) {
        for (UUID o : others) {
            if (RelationProjector.atWar(ledger, v, o)) {
                return true;
            }
        }
        return false;
    }

    /** Some member of the one realm is at war with some member of the other. */
    static boolean realmsAtWar(GarrisonLedger ledger, UUID hx, UUID hy, long now) {
        List<UUID> my = members(ledger, hy, now);
        for (UUID a : members(ledger, hx, now)) {
            if (atWarWithAny(ledger, a, my)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code newcomer} has entered the realm of {@code head} (sworn or annexed): it makes peace with every member it was at war
     * with and is friends with all of them; the realm's wars are its own at once, and its own wars the realm's.
     */
    public static void welcome(ServerLevel overworld, GarrisonLedger ledger, VillageRecord newcomer, UUID head, long now) {
        SettlementSource source = Services.settlements();
        VillageRecord h = ledger.get(head);
        if (source == null || !enabled() || h == null) {
            return;
        }
        ledger.excused().remove(newcomer.villageId);
        List<String> names = new ArrayList<>();
        for (UUID m : members(ledger, head, now)) {
            VillageRecord o = ledger.get(m);
            if (o == null || o == newcomer) {
                continue;
            }
            if (RelationProjector.atWar(ledger, newcomer.villageId, m)) {
                WarCounselService.makePeace(overworld, ledger, newcomer, o, now, newcomer.name + " enters the realm of " + h.name, false);
            }
            Integer r = relation(overworld, source, newcomer.villageId, m);
            if (r == null || r < RealmCall.FRIENDLY) {
                source.setVillageRelation(overworld, newcomer.villageId, m, RealmCall.FRIENDLY);
            }
            names.add(o.name);
        }
        ledger.setDirty();
        String text = newcomer.name + " enters the realm of " + h.name + (names.isEmpty() ? "" : ": at peace and friends with " + String.join(", ", names));
        PoliticsService.chronicle(overworld, source, newcomer, now, text);
        HmLog.info("Realm: {}", text);
        Set<String> done = new HashSet<>();
        List<UUID> realm = members(ledger, head, now);
        for (WarRecord w : new ArrayList<>(ledger.wars().values())) {
            for (UUID m : realm) {
                if (w.atWar() && w.involves(m) && !realm.contains(w.other(m))) {
                    VillageRecord e = ledger.get(w.other(m));
                    UUID eh = e == null || e.loneBuilding ? null : sovereignOf(ledger, e.villageId, now);
                    if (eh != null && done.add(eh.toString())) {
                        realmWar(overworld, ledger, head, eh, now);
                    }
                }
            }
        }
    }

    /**
     * The realms of {@code hx} and {@code hy} are at war, whole against whole, from now: each realm's vassals are called first
     * (each marches, breaks away, or is dealt with by the council), then every member of one is at war with every member of
     * the other (a truce between a pair holds). Returns the number of new wars.
     */
    public static int realmWar(ServerLevel overworld, GarrisonLedger ledger, UUID hx, UUID hy, long now) {
        SettlementSource source = Services.settlements();
        VillageRecord x = ledger.get(hx), y = ledger.get(hy);
        if (source == null || !enabled() || hx.equals(hy) || x == null || y == null || x.loneBuilding || y.loneBuilding) {
            return 0;
        }
        List<UUID> mx = answerCall(overworld, ledger, source, hx, hy, now);
        List<UUID> my = answerCall(overworld, ledger, source, hy, hx, now);
        List<VillageRecord> fresh = new ArrayList<>();
        int n = 0;
        for (UUID a : mx) {
            for (UUID b : my) {
                VillageRecord va = ledger.get(a), vb = ledger.get(b);
                if (va != null && vb != null && startWar(overworld, ledger, source, va, vb, hx, now)) {
                    n++;
                    if (!fresh.contains(va)) {
                        fresh.add(va);
                    }
                    if (!fresh.contains(vb)) {
                        fresh.add(vb);
                    }
                }
            }
        }
        if (n > 0 && (mx.size() > 1 || my.size() > 1)) {
            String text = "The realm of " + x.name + " (" + mx.size() + " village" + (mx.size() == 1 ? "" : "s") + ") and the realm of " + y.name
                    + " (" + my.size() + ") are at war, every village of one against every village of the other";
            fresh.forEach(v -> PoliticsService.chronicle(overworld, source, v, now, text));
            HmLog.info("Realm: {} ({} new war(s))", text, n);
        }
        return n;
    }

    /** {@code outsider} (a vassal that broke away, a rebel) is at war with every member of {@code head}'s realm, at once. */
    static void warWithRealm(ServerLevel overworld, GarrisonLedger ledger, UUID head, VillageRecord outsider, long now) {
        SettlementSource source = Services.settlements();
        if (source == null || outsider.loneBuilding) {
            return;
        }
        for (UUID m : members(ledger, head, now)) {
            VillageRecord v = ledger.get(m);
            if (v != null && v != outsider) {
                startWar(overworld, ledger, source, v, outsider, head, now);
            }
        }
    }

    /** {@code m} is at war with {@code n} now, in its realm's war: both relations at the war relation, the war record at war. */
    static boolean startWar(ServerLevel overworld, GarrisonLedger ledger, SettlementSource source, VillageRecord m, VillageRecord n, UUID joinedFor,
                            long now) {
        if (RelationProjector.atWar(ledger, m.villageId, n.villageId) || m.politics.truceWith(n.villageId, now) || n.politics.truceWith(m.villageId, now)) {
            return false;
        }
        source.setVillageRelation(overworld, m.villageId, n.villageId, PoliticsService.tables(m).warCounsel().warRelation());
        WarRecord w = ledger.wars().computeIfAbsent(WarRecord.key(m.villageId, n.villageId), k -> new WarRecord(m.villageId, n.villageId));
        w.declare(now);
        w.joiner = m.villageId;
        w.joinedFor = joinedFor;
        ledger.setDirty();
        HmLog.info("War: {} <-> {} started (realm war)", m.name, n.name);
        return true;
    }

    /**
     * The members of {@code head}'s realm that march against {@code enemyHead}'s: the head, its provinces (always), those already
     * at war with it, and each vassal that answers the call ({@link RealmCall#call}). A vassal excused for scutage from this war
     * stays home; one that refuses, or that the council casts out, is free and at war with the whole realm.
     */
    static List<UUID> answerCall(ServerLevel overworld, GarrisonLedger ledger, SettlementSource source, UUID head, UUID enemyHead, long now) {
        List<UUID> out = new ArrayList<>();
        out.add(head);
        List<UUID> enemies = members(ledger, enemyHead, now);
        List<Vassalage> leaving = new ArrayList<>();
        List<String> why = new ArrayList<>();
        VillageRecord h = ledger.get(head), eh = ledger.get(enemyHead);
        for (Vassalage t : subjectsOf(ledger, head, now)) {
            VillageRecord v = ledger.get(t.vassal);
            if (v == null || v.loneBuilding) {
                continue;
            }
            if (t.province || atWarWithAny(ledger, t.vassal, enemies)) {
                out.add(t.vassal);
                continue;
            }
            if (enemyHead.equals(ledger.excused().get(t.vassal))) {
                continue;
            }
            boolean hates = false;
            int rest = 0;
            List<VillageRecord> hated = new ArrayList<>();
            for (UUID m : members(ledger, head, now)) {
                if (m.equals(t.vassal)) {
                    continue;
                }
                rest += soldiers(ledger.get(m));
                Integer r = relation(overworld, source, t.vassal, m);
                if (r != null && r <= RealmCall.HATE && ledger.get(m) != null) {
                    hates = true;
                    hated.add(ledger.get(m));
                }
            }
            int best = Integer.MIN_VALUE;
            for (UUID e : enemies) {
                Integer r = relation(overworld, source, t.vassal, e);
                if (r != null) {
                    best = Math.max(best, r);
                }
            }
            RealmCall.Answer a = RealmCall.call(t.loyalty, hates, best == Integer.MIN_VALUE ? 0 : best, soldiers(v), rest);
            String names = hated.stream().map(o -> o.name).reduce((p, q) -> p + ", " + q).orElse("");
            String foe = eh != null ? eh.name : "the enemy";
            String lord = h != null ? h.name : "its sovereign";
            switch (a) {
                case JOIN -> out.add(t.vassal);
                case RECONCILE -> {
                    for (VillageRecord o : hated) {
                        reconcile(overworld, ledger, source, v, o, now);
                    }
                    loyaltyEvent(ledger, t.vassal, RealmCall.DECREE_LOYALTY, now);
                    news(overworld, source, v, h != null ? h : v, now, "Called to war against " + foe + ", " + v.name + " would not march beside " + names
                            + ": the council of " + lord + " ends the feud by decree, and " + v.name + " marches");
                    out.add(t.vassal);
                }
                case EXCUSE -> excuse(overworld, ledger, source, t, v, h, eh, names, now);
                case BREAK -> {
                    leaving.add(t);
                    why.add(v.name + " (loyalty " + Math.round(t.loyalty) + ") refuses " + lord + "'s call to war against " + foe
                            + ": it throws off its sovereign and is at war with the realm");
                }
                case CAST_OUT -> {
                    leaving.add(t);
                    why.add(v.name + " (loyalty " + Math.round(t.loyalty) + ") will not march beside " + names + ": the council of " + lord
                            + " casts it out of the realm, and the realm is at war with it");
                }
            }
        }
        for (int i = 0; i < leaving.size(); i++) {
            breakAway(overworld, ledger, leaving.get(i), now, why.get(i));
        }
        return out;
    }

    /** Two members are reconciled: any war between them ends, and they are friends again. */
    static void reconcile(ServerLevel overworld, GarrisonLedger ledger, SettlementSource source, VillageRecord a, VillageRecord b, long now) {
        if (RelationProjector.atWar(ledger, a.villageId, b.villageId)) {
            WarCounselService.makePeace(overworld, ledger, a, b, now, "reconciled by their realm's council", false);
        }
        source.setVillageRelation(overworld, a.villageId, b.villageId, RealmCall.FRIENDLY);
        ledger.setDirty();
    }

    /** {@code t}'s vassal stays out of the war with {@code enemyHead}'s realm and pays its sovereign scutage for {@link RealmCall#SCUTAGE_DAYS} days. */
    static void excuse(ServerLevel overworld, GarrisonLedger ledger, SettlementSource source, Vassalage t, VillageRecord v, @Nullable VillageRecord h,
                       @Nullable VillageRecord eh, String hated, long now) {
        if (h == null || eh == null) {
            return;
        }
        ledger.excused().put(t.vassal, eh.villageId);
        int due = RealmCall.scutage(soldiers(v));
        ledger.tributes().add(new dev.hywmill.politics.war.Tribute(v.villageId, h.villageId, due,
                dev.hywmill.politics.war.SiegeMath.levy(due, dev.hywmill.garrison.service.SiegeService.rule(h)), 0, RealmCall.SCUTAGE_DAYS, now));
        loyaltyEvent(ledger, t.vassal, RealmCall.SCUTAGE_LOYALTY, now);
        ledger.setDirty();
        news(overworld, source, v, h, now, "Called to war against " + eh.name + ", " + v.name + " will not march beside " + hated + ": the council of "
                + h.name + " lets it stay home for scutage (" + dev.hywmill.recruit.RecruitOffers.money(due) + " a day for " + RealmCall.SCUTAGE_DAYS + " days)");
    }

    /** {@code t}'s vassal leaves the realm (it refused the call, or the council cast it out): free, and at war with the whole realm at once. */
    static void breakAway(ServerLevel overworld, GarrisonLedger ledger, Vassalage t, long now, String text) {
        SettlementSource source = Services.settlements();
        VillageRecord sub = ledger.get(t.vassal), sov = ledger.get(t.overlord);
        ledger.vassalages().remove(t);
        ledger.rebels().put(t.vassal, t.overlord);
        ledger.excused().remove(t.vassal);
        ledger.setDirty();
        if (sub == null || sov == null) {
            return;
        }
        news(overworld, source, sub, sov, now, text);
        warWithRealm(overworld, ledger, sov.villageId, sub, now);
    }

    /**
     * Two members of one realm at feud (at war, or at open conflict): the council steps in. Neither the capital nor a province is
     * ever at fault (a province's diplomacy is the realm's); of two vassals the less loyal is. It ends the feud by decree when it
     * can make it stick, else it casts the culprit out (free, and at war with the realm).
     */
    static void feud(ServerLevel overworld, GarrisonLedger ledger, VillageRecord x, VillageRecord y, long now) {
        SettlementSource source = Services.settlements();
        if (source == null) {
            return;
        }
        Vassalage tx = tieOf(ledger, x.villageId, now), ty = tieOf(ledger, y.villageId, now);
        boolean vx = tx != null && !tx.province, vy = ty != null && !ty.province;
        Vassalage culprit = vx && vy ? (RealmCall.blameFirst(tx.loyalty, ty.loyalty) ? tx : ty) : vx ? tx : vy ? ty : null;
        UUID head = sovereignOf(ledger, x.villageId, now);
        VillageRecord h = ledger.get(head);
        if (culprit == null) {
            reconcile(overworld, ledger, source, x, y, now);
            return;
        }
        VillageRecord c = ledger.get(culprit.vassal), other = c == x ? y : x;
        int rest = 0;
        for (UUID m : members(ledger, head, now)) {
            if (!m.equals(culprit.vassal)) {
                rest += soldiers(ledger.get(m));
            }
        }
        RealmCall.Answer a = RealmCall.council(culprit.loyalty, soldiers(c), rest, false);
        String lord = h != null ? h.name : "their sovereign";
        if (a == RealmCall.Answer.RECONCILE) {
            reconcile(overworld, ledger, source, x, y, now);
            loyaltyEvent(ledger, culprit.vassal, RealmCall.DECREE_LOYALTY, now);
            news(overworld, source, x, y, now, "The council of " + lord + " ends the feud between " + x.name + " and " + y.name + " by decree");
        } else {
            breakAway(overworld, ledger, culprit, now, c.name + " (loyalty " + Math.round(culprit.loyalty) + ") will not end its feud with " + other.name
                    + ": the council of " + lord + " casts it out of the realm, and the realm is at war with it");
        }
    }

    /** A peace between two villages of two realms is their realms' peace: every war between them ends (and its excusals). */
    static void realmPeace(ServerLevel overworld, GarrisonLedger ledger, VillageRecord x, VillageRecord y, long now) {
        if (x.loneBuilding || y.loneBuilding) {
            return;
        }
        SettlementSource source = Services.settlements();
        UUID hx = sovereignOf(ledger, x.villageId, now), hy = sovereignOf(ledger, y.villageId, now);
        List<UUID> mx = members(ledger, hx, now), my = members(ledger, hy, now);
        if (hx.equals(hy) || (mx.size() < 2 && my.size() < 2)) {
            return;
        }
        Set<VillageRecord> told = new LinkedHashSet<>();
        for (UUID a : mx) {
            for (UUID b : my) {
                VillageRecord va = ledger.get(a), vb = ledger.get(b);
                if (va != null && vb != null && RelationProjector.atWar(ledger, a, b)) {
                    WarCounselService.makePeace(overworld, ledger, va, vb, now, "the peace of their realms", false);
                    told.add(va);
                    told.add(vb);
                }
            }
        }
        ledger.excused().entrySet().removeIf(e -> (mx.contains(e.getKey()) && e.getValue().equals(hy)) || (my.contains(e.getKey()) && e.getValue().equals(hx)));
        VillageRecord rx = ledger.get(hx), ry = ledger.get(hy);
        if (!told.isEmpty() && rx != null && ry != null) {
            String text = "The realm of " + rx.name + " and the realm of " + ry.name + " make peace: every war between their villages ends";
            told.forEach(v -> PoliticsService.chronicle(overworld, source, v, now, text));
            HmLog.info("Realm: {}", text);
        }
    }

    /**
     * Realm unity upkeep (each village AI interval): provinces are kept friends with their realm; a feud between members at open
     * conflict goes to the council; a realm at war with any village of another realm is at war with all of it (vassals called,
     * newcomers included); excusals end with their wars; and a hatred any member holds for an outsider is the whole realm's.
     */
    public static void unity(ServerLevel overworld, GarrisonLedger ledger, long now) {
        SettlementSource source = Services.settlements();
        if (source == null || !enabled()) {
            return;
        }
        Set<UUID> heads = new LinkedHashSet<>();
        for (Vassalage t : ledger.vassalages()) {
            if (t.indefinite() && !t.over(now) && ledger.get(t.overlord) != null && tieOf(ledger, t.overlord, now) == null) {
                heads.add(t.overlord);
            }
        }
        // 1. friends, and feuds
        for (UUID h : heads) {
            List<UUID> m = members(ledger, h, now);
            for (int i = 0; i < m.size(); i++) {
                for (int j = i + 1; j < m.size(); j++) {
                    VillageRecord p = ledger.get(m.get(i)), q = ledger.get(m.get(j));
                    if (p == null || q == null || !sameRealm(ledger, p.villageId, q.villageId, now)) {
                        continue;
                    }
                    Integer r = relation(overworld, source, p.villageId, q.villageId);
                    boolean war = RelationProjector.atWar(ledger, p.villageId, q.villageId);
                    if (isProvince(ledger, p.villageId, now) || isProvince(ledger, q.villageId, now)) {
                        if (war || r == null || r < RealmCall.FRIENDLY) {
                            reconcile(overworld, ledger, source, p, q, now);
                        }
                    } else if (war || (r != null && r <= RelationProjector.OPEN_CONFLICT)) {
                        feud(overworld, ledger, p, q, now);
                    }
                }
            }
        }
        // 2. a realm at war with any village of another realm is at war with all of it
        Set<String> done = new HashSet<>();
        for (WarRecord w : new ArrayList<>(ledger.wars().values())) {
            VillageRecord a = ledger.get(w.a), b = ledger.get(w.b);
            if (!w.atWar() || a == null || b == null || a.loneBuilding || b.loneBuilding) {
                continue;
            }
            UUID ha = sovereignOf(ledger, a.villageId, now), hb = sovereignOf(ledger, b.villageId, now);
            if (ha.equals(hb) || (members(ledger, ha, now).size() < 2 && members(ledger, hb, now).size() < 2)) {
                continue;
            }
            if (done.add(ha.compareTo(hb) < 0 ? ha + ">" + hb : hb + ">" + ha)) {
                realmWar(overworld, ledger, ha, hb, now);
            }
        }
        // excusals end with their wars, or when the vassal is no longer one
        ledger.excused().entrySet().removeIf(e -> tieOf(ledger, e.getKey(), now) == null
                || !realmsAtWar(ledger, sovereignOf(ledger, e.getKey(), now), e.getValue(), now));
        // 3. shared hatred: the realm's feeling for an outsider is its worst member's, when that is hatred
        for (UUID h : heads) {
            List<UUID> m = members(ledger, h, now);
            for (VillageRecord x : new ArrayList<>(ledger.all())) {
                if (x.loneBuilding || m.contains(x.villageId) || !RealmDiplomacy.near(ledger, m, x)) {
                    continue;
                }
                List<Integer> rs = new ArrayList<>();
                Integer[] own = new Integer[m.size()];
                for (int i = 0; i < m.size(); i++) {
                    own[i] = relation(overworld, source, m.get(i), x.villageId);
                    if (own[i] != null) {
                        rs.add(own[i]);
                    }
                }
                Integer worst = RealmCall.shared(rs.stream().mapToInt(Integer::intValue).toArray());
                if (worst == null) {
                    continue;
                }
                UUID xh = sovereignOf(ledger, x.villageId, now);
                for (int i = 0; i < m.size(); i++) {
                    VillageRecord mi = ledger.get(m.get(i));
                    if (mi == null || mi.center.distSqr(x.center) > (double) Treaty.REACH * Treaty.REACH) {
                        continue; // 1.8: only its members within reach of it take up the hatred
                    }
                    if (own[i] != null && own[i] > worst && !xh.equals(ledger.excused().get(m.get(i)))) {
                        source.setVillageRelation(overworld, m.get(i), x.villageId, worst);
                        VillageRecord v = ledger.get(m.get(i));
                        HmLog.info("Realm: {} shares its realm's hatred of {} ({} -> {})", v != null ? v.name : m.get(i), x.name, own[i], worst);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ the national army (docs/realm-design.md §9)

    /** {@code head} is the capital of a realm that raises a national army (at least two subjects). */
    public static boolean national(GarrisonLedger ledger, UUID head, long now) {
        return enabled() && tieOf(ledger, head, now) == null && NationalArmy.raised(subjectsOf(ledger, head, now).size());
    }

    /** The share of its free garrison {@code member} pledges to its realm's army. */
    public static double pledgeShare(GarrisonLedger ledger, UUID member, long now) {
        Vassalage t = tieOf(ledger, member, now);
        return NationalArmy.share(t == null, t != null && t.province, t == null ? 0 : t.loyalty);
    }

    /** {@code vassal} is excused (for scutage) from its realm's war with {@code enemy}'s realm. */
    public static boolean excusedFrom(GarrisonLedger ledger, UUID vassal, UUID enemy, long now) {
        UUID e = ledger.excused().get(vassal);
        return e != null && e.equals(sovereignOf(ledger, enemy, now));
    }

    /** The men pledged to {@code head}'s army now, from its free garrisons: the capital's, the provinces', the vassals'. */
    public record Army(int capital, int provinces, int vassals, int villages) {
        public int total() {
            return capital + provinces + vassals;
        }
    }

    public static Army army(GarrisonLedger ledger, UUID head, long now) {
        int cap = 0, prov = 0, vas = 0, n = 0;
        for (UUID m : members(ledger, head, now)) {
            VillageRecord v = ledger.get(m);
            if (v == null || v.hywRoster == null) {
                continue;
            }
            int men = NationalArmy.pledge(dev.hywmill.garrison.service.ReliefService.freeAtHome(v), pledgeShare(ledger, m, now));
            Vassalage t = tieOf(ledger, m, now);
            if (t == null) {
                cap += men;
            } else if (t.province) {
                prov += men;
            } else {
                vas += men;
            }
            n++;
        }
        return new Army(cap, prov, vas, n);
    }

    /** "the army of the realm of X" for the capital of a national army, else the village's name. */
    public static String hostName(GarrisonLedger ledger, VillageRecord v, long now) {
        return national(ledger, v.villageId, now) ? "the army of the realm of " + v.name : v.name;
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
                enabled() && !isProvince(ledger, a.villageId, now), vassalCount(ledger, sov, now));
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
        List<UUID> moved = new ArrayList<>();
        for (Vassalage t : subjectsOf(ledger, province.villageId, now)) {
            ledger.vassalages().remove(t);
            Vassalage m = Vassalage.subject(t.vassal, head.villageId, now, t.province);
            m.loyalty = t.loyalty;
            ledger.vassalages().add(m);
            moved.add(t.vassal);
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
        welcome(overworld, ledger, province, head.villageId, now); // realm unity: friends with all, and in all the realm's wars
        for (UUID m : moved) {
            VillageRecord mv = ledger.get(m);
            if (mv != null) {
                welcome(overworld, ledger, mv, head.villageId, now);
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

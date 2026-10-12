package dev.hywmill.politics.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.garrison.service.ReliefService;
import dev.hywmill.garrison.service.SiegeService;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.realm.RealmPlan;
import dev.hywmill.politics.realm.Strategy;
import dev.hywmill.politics.realm.Treaty;
import dev.hywmill.politics.war.SiegeMath;
import dev.hywmill.politics.war.Tribute;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Realms reading and answering each other (docs/realm-design.md §12). Its scouts tell a council which of its villages an
 * enemy council means to take, and those keep their men home. Before going to war for a claim it demands fealty, which a much
 * weaker village may give. A realm losing a war offers tribute for peace. And when a realm grows great, its neighbours fear
 * it: they league against it in defensive pacts, and when together they are a match for it, they go to war with it.
 */
public final class RealmDiplomacy {
    private RealmDiplomacy() {}

    public static final long DAY = PoliticsTables.DAY;
    /** A realm this large (capital and subjects) alarms its neighbours. */
    public static final int FEARED = 4;
    /** A neighbour this alarmed joins the league against it; below {@link #CALM} it leaves. */
    public static final int ALARM = 50, CALM = 35;
    /** The league goes to war when its strength is this share of the feared realm's. */
    public static final double LEAGUE_WAR = 1.1;
    /** A realm on the defensive offers peace when its army is under this share of its enemies'. */
    public static final double SUE = 0.6;

    public static boolean enabled() {
        return RealmService.enabled() && HywMillConfig.REALM_DIPLOMACY.get();
    }

    // ------------------------------------------------------------------ scouts

    /**
     * The members of {@code realm} that an enemy council means to take (a goal on its list), and that council's capital: its
     * scouts see the enemy gather. A goal it is ready to march on makes the member threatened.
     */
    public static Map<UUID, UUID> eyed(GarrisonLedger ledger, UUID head, List<UUID> realm, long now) {
        Map<UUID, UUID> out = new LinkedHashMap<>();
        if (!enabled()) {
            return out;
        }
        for (Map.Entry<UUID, RealmPlan> e : ledger.plans().entrySet()) {
            if (e.getKey().equals(head)) {
                continue;
            }
            for (Strategy.Goal g : e.getValue().goals) {
                if (g.kind() != Strategy.Kind.DEFEND && realm.contains(g.target()) && g.ready()) {
                    out.putIfAbsent(g.target(), e.getKey());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ demands

    /**
     * Before declaring war for a claim, the council demands that {@code t} swear fealty. A village that rules or answers to
     * no one gives in when the realm's army is much the stronger (more likely the stronger it is); then it is the realm's
     * vassal, and there is no war. Returns true if it gave in.
     */
    public static boolean demand(ServerLevel ow, GarrisonLedger ledger, VillageRecord capital, RealmPlan p, VillageRecord t, double ours, long now) {
        if (!enabled() || RealmService.tieOf(ledger, t.villageId, now) != null || !RealmService.subjectsOf(ledger, t.villageId, now).isEmpty()) {
            return false;
        }
        p.lastDemand = now;
        double chance = yieldChance(ours, SiegeService.defense(ow, t));
        boolean yields = new Random(now ^ t.villageId.getLeastSignificantBits()).nextDouble() < chance;
        HmLog.info("Realm council of {}: demands fealty of {} (chance {}): {}", capital.name, t.name, String.format("%.2f", chance), yields);
        if (yields) {
            SiegeService.swearFealty(ow, ledger, t, capital, now);
        }
        return yields;
    }

    /** The chance a village gives in to a demand: none unless the realm's army is 1.2 times its defence, at most 0.7. */
    public static double yieldChance(double ours, double theirDefense) {
        double r = ours / Math.max(1, theirDefense);
        return Math.max(0, Math.min(0.7, (r - 1.2) * 0.5));
    }

    // ------------------------------------------------------------------ suing for peace

    /**
     * A realm on the defensive, its army under {@link #SUE} of its enemies', offers each enemy realm tribute for peace (once a
     * day each). An enemy whose council is on the attack with an army ready seldom takes it; others often do. Taken: peace,
     * and the tribute is paid over three days.
     */
    public static void sue(ServerLevel ow, GarrisonLedger ledger, SettlementSource source, VillageRecord capital, RealmPlan p, List<UUID> realm,
                           List<Strategy.Target> enemies, long now) {
        if (!enabled() || p.stance != Strategy.Stance.DEFENSIVE || p.ratio >= SUE) {
            return;
        }
        Set<UUID> heads = new LinkedHashSet<>();
        enemies.forEach(e -> heads.add(RealmService.sovereignOf(ledger, e.id(), now)));
        for (UUID h : heads) {
            VillageRecord eh = ledger.get(h);
            Long last = p.offered.get(h);
            if (eh == null || (last != null && now - last < DAY)) {
                continue;
            }
            p.offered.put(h, now);
            int perDay = tribute(realm.size());
            RealmPlan theirs = ledger.plans().get(h);
            boolean eager = theirs != null && theirs.stance == Strategy.Stance.AGGRESSIVE && theirs.goals.stream().anyMatch(Strategy.Goal::ready);
            double accept = eager ? 0.25 : 0.6;
            boolean taken = new Random(now ^ h.getMostSignificantBits()).nextDouble() < accept;
            String offer = "offers " + eh.name + " " + dev.hywmill.recruit.RecruitOffers.money(perDay) + " a day for three days for peace";
            if (!taken) {
                RealmCouncil.note(ow, ledger, source, capital, p, now, "the council " + offer + "; " + eh.name + " refuses");
                continue;
            }
            RealmCouncil.note(ow, ledger, source, capital, p, now, "the council " + offer + "; " + eh.name + " accepts: peace");
            WarCounselService.makePeace(ow, ledger, capital, eh, now, "the realm of " + capital.name + " buys peace", true);
            ledger.tributes().add(new Tribute(capital.villageId, h, perDay, SiegeMath.levy(perDay, SiegeService.rule(capital)), 0, 3, now));
            ledger.setDirty();
        }
    }

    /** Tribute offered for peace, a day: one denier or for each village of the realm. */
    public static int tribute(int villages) {
        return 4096 * Math.max(1, villages);
    }

    // ------------------------------------------------------------------ time heals

    /** Each day at peace, a grudge between two villages within reach fades this much (Millénaire's relation scale). */
    public static final int HEAL = 2;

    /**
     * Once a day: between two villages within reach that are at peace with each other (no war, no siege), a relation below
     * zero but short of open conflict rises by {@link #HEAL}: old grudges fade, and fewer quarrels turn into wars.
     */
    public static void heal(ServerLevel ow, GarrisonLedger ledger, SettlementSource source, long now) {
        if (!enabled()) {
            return;
        }
        List<VillageRecord> vs = new ArrayList<>();
        for (VillageRecord v : ledger.all()) {
            if (!v.loneBuilding) {
                vs.add(v);
            }
        }
        int n = 0;
        for (int i = 0; i < vs.size(); i++) {
            for (int j = i + 1; j < vs.size(); j++) {
                VillageRecord x = vs.get(i), y = vs.get(j);
                if (x.center.distSqr(y.center) > (double) Treaty.REACH * Treaty.REACH || RelationProjector.atWar(ledger, x.villageId, y.villageId)
                        || SiegeService.byAttacker(ledger, x.villageId) != null && y.villageId.equals(SiegeService.byAttacker(ledger, x.villageId).target)
                        || SiegeService.byAttacker(ledger, y.villageId) != null && x.villageId.equals(SiegeService.byAttacker(ledger, y.villageId).target)) {
                    continue;
                }
                for (VillageRecord[] d : new VillageRecord[][]{{x, y}, {y, x}}) {
                    java.util.OptionalInt r = source.villageRelation(ow, d[0].villageId, d[1].villageId);
                    if (r.isPresent() && r.getAsInt() < 0 && r.getAsInt() > RelationProjector.OPEN_CONFLICT) {
                        source.setVillageRelation(ow, d[0].villageId, d[1].villageId, Math.min(0, r.getAsInt() + HEAL));
                        n++;
                    }
                }
            }
        }
        if (n > 0) {
            dev.hywmill.core.HmLog.info("Realms: {} grudge(s) between villages at peace fade a little", n);
        }
    }

    // ------------------------------------------------------------------ coalitions

    /** How alarmed a neighbour is by a realm of {@code size} villages {@code ratio} times as strong as it. */
    public static int alarm(int size, double ratio) {
        return Math.max(0, (size - (FEARED - 1)) * 15 + (int) Math.round(20 * Math.min(4, ratio)));
    }

    static double strength(GarrisonLedger ledger, UUID head, long now) {
        double s = 0;
        for (UUID m : RealmService.members(ledger, head, now)) {
            VillageRecord v = ledger.get(m);
            if (v != null && v.hywRoster != null) {
                s += ReliefService.freeStrength(v);
            }
        }
        return s;
    }

    /**
     * Once a day for each great realm: its alarmed neighbours (the heads of the villages and realms within reach that are not
     * its friends) league against it: each pair signs a defensive pact and their friendship grows; their liking for it falls.
     * When the league together is a match for it, its members go to war with it.
     */
    public static void leagues(ServerLevel ow, GarrisonLedger ledger, SettlementSource source, VillageRecord great, RealmPlan p, long now) {
        if (!enabled()) {
            return;
        }
        UUID r = great.villageId;
        List<UUID> realm = RealmService.members(ledger, r, now);
        Set<UUID> league = ledger.coalitions().computeIfAbsent(r, k -> new LinkedHashSet<>());
        double ours = strength(ledger, r, now);
        Set<UUID> before = new LinkedHashSet<>(league);
        for (VillageRecord v : ledger.all()) {
            UUID h = v.villageId;
            if (v.loneBuilding || realm.contains(h) || !RealmService.sovereignOf(ledger, h, now).equals(h) || !near(ledger, realm, v)) {
                league.remove(h);
                continue;
            }
            Treaty tr = RealmService.treaty(ledger, h, r);
            Integer rel = RealmService.relation(ow, source, h, r);
            int a = realm.size() >= FEARED ? alarm(realm.size(), ours / Math.max(1, strength(ledger, h, now))) : 0;
            boolean friend = (tr != null && tr.kind == Treaty.Kind.ALLIANCE) || (rel != null && rel >= 50);
            if (!friend && a >= ALARM) {
                league.add(h);
            } else if (friend || a < CALM) {
                league.remove(h);
            }
        }
        if (league.isEmpty()) {
            ledger.coalitions().remove(r);
            return;
        }
        List<VillageRecord> members = new ArrayList<>();
        league.forEach(h -> {
            VillageRecord m = ledger.get(h);
            if (m != null) {
                members.add(m);
            }
        });
        for (VillageRecord m : members) {
            if (!before.contains(m.villageId)) {
                RealmCouncil.note(ow, ledger, source, great, p, now, m.name + " fears the realm and leagues against it");
            }
            Integer rel = RealmService.relation(ow, source, m.villageId, r);
            if (rel != null && rel > -40) {
                source.setVillageRelation(ow, m.villageId, r, Math.max(-40, rel - 5));
            }
        }
        // the league holds together: friendship between its members, and a defensive pact for each pair within reach
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                VillageRecord x = members.get(i), y = members.get(j);
                if (x.center.distSqr(y.center) > (double) Treaty.REACH * Treaty.REACH || RelationProjector.atWar(ledger, x.villageId, y.villageId)) {
                    continue;
                }
                Integer rel = RealmService.relation(ow, source, x.villageId, y.villageId);
                if (rel == null || rel < Treaty.Kind.DEFENSIVE.threshold + 5) {
                    source.setVillageRelation(ow, x.villageId, y.villageId, Treaty.Kind.DEFENSIVE.threshold + 5);
                    source.setVillageRelation(ow, y.villageId, x.villageId, Treaty.Kind.DEFENSIVE.threshold + 5);
                }
                Treaty tr = RealmService.treaty(ledger, x.villageId, y.villageId);
                if (tr == null || tr.kind == Treaty.Kind.PACT) {
                    RealmService.sign(ow, ledger, x, y, Treaty.Kind.DEFENSIVE, now);
                }
            }
        }
        // a match for it together: war
        double theirs = 0;
        boolean atWar = false;
        VillageRecord strongest = null;
        double best = -1;
        for (VillageRecord m : members) {
            double s = strength(ledger, m.villageId, now);
            theirs += s;
            if (s > best) {
                best = s;
                strongest = m;
            }
            atWar |= RelationProjector.atWar(ledger, m.villageId, r);
        }
        if (!atWar && strongest != null && members.size() >= 2 && theirs >= LEAGUE_WAR * ours) {
            RealmCouncil.note(ow, ledger, source, great, p, now, "the league against the realm (" + names(members) + ") declares war");
            for (VillageRecord m : members) {
                if (!RelationProjector.atWar(ledger, m.villageId, r) && !m.politics.truceWith(r, now)) {
                    WarCounselService.declareWar(ow, ledger, m, great, now, "the league against the realm of " + great.name);
                }
            }
        }
        ledger.setDirty();
    }

    static boolean near(GarrisonLedger ledger, List<UUID> realm, VillageRecord v) {
        for (UUID m : realm) {
            VillageRecord x = ledger.get(m);
            if (x != null && x.center.distSqr(v.center) <= (double) Treaty.REACH * Treaty.REACH) {
                return true;
            }
        }
        return false;
    }

    static String names(List<VillageRecord> vs) {
        List<String> n = new ArrayList<>();
        vs.forEach(v -> n.add(v.name));
        return String.join(", ", n);
    }

    /** The Realm tab: the league against this realm, and the leagues this village belongs to. */
    public static List<String> lines(GarrisonLedger ledger, UUID head, long now) {
        List<String> out = new ArrayList<>();
        Set<UUID> against = ledger.coalitions().get(head);
        if (against != null && !against.isEmpty()) {
            List<VillageRecord> vs = new ArrayList<>();
            against.forEach(h -> {
                VillageRecord v = ledger.get(h);
                if (v != null) {
                    vs.add(v);
                }
            });
            double theirs = 0;
            for (VillageRecord v : vs) {
                theirs += strength(ledger, v.villageId, now);
            }
            out.add(" Its neighbours fear it: " + names(vs) + " league against it (their armies " + String.format("%.1f", theirs / Math.max(1,
                    strength(ledger, head, now))) + " to its 1; at " + LEAGUE_WAR + " they go to war).");
        }
        for (Map.Entry<UUID, Set<UUID>> e : ledger.coalitions().entrySet()) {
            VillageRecord g = ledger.get(e.getKey());
            if (g != null && e.getValue().contains(head)) {
                out.add(" It leagues with " + (e.getValue().size() - 1) + " other(s) against the realm of " + g.name + ".");
            }
        }
        return out;
    }
}

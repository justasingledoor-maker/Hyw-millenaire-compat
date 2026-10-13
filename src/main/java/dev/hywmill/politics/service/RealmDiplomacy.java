package dev.hywmill.politics.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.garrison.service.ReliefService;
import dev.hywmill.garrison.service.SiegeService;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.realm.RealmPlan;
import dev.hywmill.politics.realm.Strategy;
import dev.hywmill.politics.realm.Treaty;
import dev.hywmill.politics.war.Siege;
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
 * it: they league against it in defensive pacts and keep their garrisons on a war footing, while each seeks peace with it
 * (1.8: a league no longer goes to war; it only defends). A great realm keeps a few wars at a time and makes peace on the rest.
 */
public final class RealmDiplomacy {
    private RealmDiplomacy() {}

    public static final long DAY = PoliticsTables.DAY;
    /** A realm this large (capital and subjects) alarms its neighbours. */
    public static final int FEARED = 4;
    /** A neighbour this alarmed joins the league against it; below {@link #CALM} it leaves. */
    public static final int ALARM = 50, CALM = 35;
    /** 1.8: a state this many times weaker than a great realm seeks peace with it (and keeps its guard up). */
    public static final double AWED = 1.5;
    /** 1.8: a realm at war with more states than this makes peace with those it is not fighting for. */
    public static final int MAX_WARS = 3;
    /** 1.8: a day's courting: the feelings between a weak state and a great realm rise this much (half while the realm covets it), up to {@link #COURT_CAP}. */
    public static final int COURT = 6, COURT_CAP = 35;
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
        // 1.10: a near village with room for it submits as a province (a little less readily); a far one swears fealty, while
        // the realm has few vassals; a realm with its fill of vassals demands nothing of a far one and goes to war for it
        boolean province = Math.sqrt(capital.center.distSqr(t.center)) <= SUBMIT_REACH && RealmService.provinceRoom(ledger, capital, now);
        if (!province && RealmService.vassalCount(ledger, capital.villageId, now) >= vassalCap(capital.tier.ordinal())) {
            return false;
        }
        p.lastDemand = now;
        double chance = yieldChance(ours, SiegeService.defense(ow, t)) * (province ? 0.8 : 1);
        boolean yields = new java.util.SplittableRandom(now * 0x9E3779B97F4A7C15L ^ t.villageId.getLeastSignificantBits()).nextDouble() < chance;
        HmLog.info("Realm council of {}: demands {} of {} (chance {}): {}", capital.name, province ? "submission" : "fealty", t.name,
                String.format("%.2f", chance), yields);
        if (yields && province) {
            RealmService.annex(ow, ledger, t, capital, now);
        } else if (yields) {
            SiegeService.swearFealty(ow, ledger, t, capital, now);
        }
        return yields;
    }

    /** 1.10: a village this near the capital (blocks) is demanded as a province, not a vassal, while the realm has room. */
    public static final double SUBMIT_REACH = 1000;

    /** 1.10: how many vassals a realm of this tier keeps before it stops asking for fealty (provinces are what it wants). */
    public static int vassalCap(int tier) {
        return 1 + tier / 2;
    }

    /** The chance a village gives in to a demand: none unless the realm's army is 1.2 times its defence, at most 0.55. */
    public static double yieldChance(double ours, double theirDefense) {
        double r = ours / Math.max(1, theirDefense);
        return Math.max(0, Math.min(0.55, (r - 1.2) * 0.5));
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
            boolean taken = new java.util.SplittableRandom(now * 0x9E3779B97F4A7C15L ^ h.getMostSignificantBits()).nextDouble() < accept;
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
                // both feelings read first: a source that keeps one relation for the pair must not heal it twice
                java.util.OptionalInt rxy = source.villageRelation(ow, x.villageId, y.villageId), ryx = source.villageRelation(ow, y.villageId, x.villageId);
                boolean hx = rxy.isPresent() && rxy.getAsInt() < 0 && rxy.getAsInt() > RelationProjector.OPEN_CONFLICT;
                boolean hy = ryx.isPresent() && ryx.getAsInt() < 0 && ryx.getAsInt() > RelationProjector.OPEN_CONFLICT;
                if (hx) {
                    source.setVillageRelation(ow, x.villageId, y.villageId, Math.min(0, rxy.getAsInt() + HEAL));
                    n++;
                }
                if (hy) {
                    source.setVillageRelation(ow, y.villageId, x.villageId, Math.min(0, ryx.getAsInt() + HEAL));
                    n++;
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
     * its friends) league against it: each pair signs a defensive pact (each must relieve the other when besieged) and their
     * friendship grows, and their garrisons stand on a war footing ({@link #braced}). 1.8: the league only defends; it does not
     * go to war.
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
                RealmCouncil.note(ow, ledger, source, great, p, now, m.name + " fears the realm: it leagues with its neighbours for their defence and arms");
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
        ledger.setDirty();
    }

    // ------------------------------------------------------------------ 1.8: the weak seek peace; the great keep few wars

    /** {@code v}'s state leagues against a great realm: its garrison stands on a war footing (mobilized) though at peace. */
    public static boolean braced(GarrisonLedger ledger, UUID v, long now) {
        if (!enabled() || ledger.coalitions().isEmpty()) {
            return false;
        }
        UUID h = RealmService.sovereignOf(ledger, v, now);
        for (Set<UUID> league : ledger.coalitions().values()) {
            if (league.contains(h)) {
                return true;
            }
        }
        return false;
    }

    /** The heads of the states the council of {@code p} means to take: its goals and its claim. */
    static Set<UUID> coveted(GarrisonLedger ledger, RealmPlan p, long now) {
        Set<UUID> out = new LinkedHashSet<>();
        for (Strategy.Goal g : p.goals) {
            if (g.kind() != Strategy.Kind.DEFEND) {
                out.add(RealmService.sovereignOf(ledger, g.target(), now));
            }
        }
        if (p.covets != null) {
            out.add(RealmService.sovereignOf(ledger, p.covets, now));
        }
        return out;
    }

    /** The heads of the states at war with {@code realm}. */
    static Set<UUID> enemyHeads(GarrisonLedger ledger, List<UUID> realm, long now) {
        Set<UUID> out = new LinkedHashSet<>();
        for (dev.hywmill.politics.war.WarRecord w : ledger.wars().values()) {
            if (!w.atWar()) {
                continue;
            }
            for (UUID m : realm) {
                if (w.involves(m) && !realm.contains(w.other(m))) {
                    VillageRecord e = ledger.get(w.other(m));
                    if (e != null && !e.loneBuilding) {
                        out.add(RealmService.sovereignOf(ledger, e.villageId, now));
                    }
                }
            }
        }
        return out;
    }

    /** The chance a great realm takes a weak state's offer of peace: seldom if it means to take it; readily if it has many wars. */
    public static double peaceChance(boolean coveted, int wars) {
        return coveted ? 0.2 : wars > MAX_WARS ? 0.95 : 0.75;
    }

    /**
     * Once a day for each great realm: each neighbouring state {@link #AWED} by it wants peace with it. At war, it sues for peace
     * with tribute (taken unless the realm means to take it); at peace, it courts the realm (their feelings for each other rise
     * a little each day) and offers a non-aggression pact once both are warm enough, which the realm signs unless it covets it
     * (a pact keeps its ambition off). Meanwhile it leagues and arms ({@link #leagues}, {@link #braced}).
     */
    public static void appease(ServerLevel ow, GarrisonLedger ledger, SettlementSource source, VillageRecord great, RealmPlan p, long now) {
        if (!enabled()) {
            return;
        }
        UUID r = great.villageId;
        List<UUID> realm = RealmService.members(ledger, r, now);
        double ours = strength(ledger, r, now);
        Set<UUID> coveted = coveted(ledger, p, now);
        int wars = enemyHeads(ledger, realm, now).size();
        for (VillageRecord h : new ArrayList<>(ledger.all())) {
            UUID hid = h.villageId;
            if (h.loneBuilding || realm.contains(hid) || !RealmService.sovereignOf(ledger, hid, now).equals(hid) || !near(ledger, realm, h)) {
                continue;
            }
            List<UUID> theirs = RealmService.members(ledger, hid, now);
            if (ours < AWED * Math.max(1, strength(ledger, hid, now))) {
                continue;
            }
            boolean wanted = coveted.contains(hid);
            Random rnd = new Random(now ^ hid.getMostSignificantBits() ^ r.getLeastSignificantBits());
            if (RealmService.realmsAtWar(ledger, hid, r, now)) {
                Long last = p.pleas.get(hid);
                if (last != null && now - last < DAY) {
                    continue;
                }
                p.pleas.put(hid, now);
                int perDay = tribute(theirs.size());
                String offer = h.name + ", outmatched, sues for peace and offers " + dev.hywmill.recruit.RecruitOffers.money(perDay) + " a day for three days";
                if (rnd.nextDouble() >= peaceChance(wanted, wars)) {
                    RealmCouncil.note(ow, ledger, source, great, p, now, offer + "; the council refuses" + (wanted ? " (it means to take it)" : ""));
                    continue;
                }
                RealmCouncil.note(ow, ledger, source, great, p, now, offer + "; the council accepts: peace and a non-aggression pact");
                WarCounselService.makePeace(ow, ledger, h, great, now, h.name + " buys peace from the realm of " + great.name, true);
                ledger.tributes().add(new Tribute(hid, r, perDay, SiegeMath.levy(perDay, SiegeService.rule(h)), 0, 3, now));
                settle(ow, source, realm, theirs);
                RealmService.sign(ow, ledger, h, great, Treaty.Kind.PACT, now);
                wars--;
                ledger.setDirty();
                continue;
            }
            if (RealmService.treaty(ledger, hid, r) != null) {
                continue;
            }
            warm(ow, source, realm, theirs, wanted ? COURT / 2 : COURT);
            Integer a = RealmService.relation(ow, source, hid, r), b = RealmService.relation(ow, source, r, hid);
            if (!wanted && a != null && b != null && a >= Treaty.Kind.PACT.threshold && b >= Treaty.Kind.PACT.threshold && rnd.nextDouble() < 0.5) {
                RealmCouncil.note(ow, ledger, source, great, p, now, h.name + ", wary of the realm, seeks its friendship: they sign a non-aggression pact");
                RealmService.sign(ow, ledger, h, great, Treaty.Kind.PACT, now);
            }
        }
        ledger.setDirty();
    }

    /**
     * Feelings warm between a weak state ({@code theirs}) and a great realm ({@code realm}): between each member of the one
     * and each of the other they rise by {@code by}, up to {@link #COURT_CAP} (both read first, so a source that keeps one
     * relation for the pair raises it once).
     */
    static void warm(ServerLevel ow, SettlementSource source, List<UUID> realm, List<UUID> theirs, int by) {
        for (UUID x : theirs) {
            for (UUID m : realm) {
                java.util.OptionalInt a = source.villageRelation(ow, x, m), b = source.villageRelation(ow, m, x);
                if (a.isPresent() && a.getAsInt() < COURT_CAP) {
                    source.setVillageRelation(ow, x, m, Math.min(COURT_CAP, a.getAsInt() + by));
                }
                if (b.isPresent() && b.getAsInt() < COURT_CAP) {
                    source.setVillageRelation(ow, m, x, Math.min(COURT_CAP, b.getAsInt() + by));
                }
            }
        }
    }

    /** Peace bought: every member of each side at {@link #COURT_CAP} with every member of the other (a pact's warmth). */
    static void settle(ServerLevel ow, SettlementSource source, List<UUID> realm, List<UUID> theirs) {
        for (UUID x : theirs) {
            for (UUID m : realm) {
                source.setVillageRelation(ow, x, m, COURT_CAP);
                source.setVillageRelation(ow, m, x, COURT_CAP);
            }
        }
    }

    /**
     * Once a session: a great realm at war with more than {@link #MAX_WARS} states offers peace to those it does not mean to
     * take and that do not march on it (one a day each, the farthest first); a state on the attack against it seldom takes it.
     */
    public static void focus(ServerLevel ow, GarrisonLedger ledger, SettlementSource source, VillageRecord capital, RealmPlan p, List<UUID> realm, long now) {
        if (!enabled()) {
            return;
        }
        Set<UUID> heads = enemyHeads(ledger, realm, now);
        int excess = heads.size() - MAX_WARS;
        if (excess <= 0) {
            return;
        }
        Set<UUID> coveted = coveted(ledger, p, now);
        List<VillageRecord> spare = new ArrayList<>();
        for (UUID h : heads) {
            VillageRecord e = ledger.get(h);
            Siege theirs = SiegeService.byAttacker(ledger, h);
            if (e != null && !coveted.contains(h) && (theirs == null || !realm.contains(theirs.target))) {
                spare.add(e);
            }
        }
        spare.sort(java.util.Comparator.comparingDouble((VillageRecord e) -> e.center.distSqr(capital.center)).reversed());
        for (VillageRecord e : spare) {
            if (excess <= 0) {
                break;
            }
            Long last = p.offered.get(e.villageId);
            if (last != null && now - last < DAY) {
                continue;
            }
            p.offered.put(e.villageId, now);
            RealmPlan their = ledger.plans().get(e.villageId);
            boolean eager = their != null && their.stance == Strategy.Stance.AGGRESSIVE && coveted(ledger, their, now).contains(capital.villageId);
            boolean taken = new java.util.SplittableRandom(now * 0x9E3779B97F4A7C15L ^ e.villageId.getLeastSignificantBits()).nextDouble() < (eager ? 0.15 : 0.6);
            RealmCouncil.note(ow, ledger, source, capital, p, now, "the council, with " + heads.size() + " wars on its hands, offers " + e.name + " peace: "
                    + (taken ? "it accepts" : "it refuses"));
            if (taken) {
                WarCounselService.makePeace(ow, ledger, capital, e, now, "the realm of " + capital.name + " makes peace to fight its other wars", true);
                excess--;
            }
        }
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

    /** 1.10: the great realms {@code head}'s state leagues against (their heads); empty when it is in no league. */
    public static List<UUID> leaguedAgainst(GarrisonLedger ledger, UUID head) {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Set<UUID>> e : ledger.coalitions().entrySet()) {
            if (e.getValue().contains(head)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** 1.10: the Realm tab's world section: every league, against whom, its members and their armies against the feared realm's. */
    public static List<String> worldLines(GarrisonLedger ledger, long now) {
        List<String> out = new ArrayList<>();
        out.add("Coalitions of the known world:");
        int n = 0;
        for (Map.Entry<UUID, Set<UUID>> e : ledger.coalitions().entrySet()) {
            VillageRecord g = ledger.get(e.getKey());
            List<VillageRecord> vs = new ArrayList<>();
            e.getValue().forEach(h -> {
                VillageRecord v = ledger.get(h);
                if (v != null) {
                    vs.add(v);
                }
            });
            if (g == null || vs.isEmpty()) {
                continue;
            }
            double theirs = 0;
            for (VillageRecord v : vs) {
                theirs += strength(ledger, v.villageId, now);
            }
            out.add(" Against the realm of " + g.name + " (" + RealmService.members(ledger, g.villageId, now).size() + " villages): " + names(vs)
                    + "; their armies " + String.format("%.1f", theirs / Math.max(1, strength(ledger, g.villageId, now))) + " to its 1.");
            n++;
        }
        if (n == 0) {
            out.add(" None: no realm is feared enough.");
        }
        return out;
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
            out.add(" Its neighbours fear it: " + names(vs) + " league against it for their defence (their armies " + String.format("%.1f",
                    theirs / Math.max(1, strength(ledger, head, now))) + " to its 1).");
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

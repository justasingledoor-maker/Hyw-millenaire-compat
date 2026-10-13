package dev.hywmill.politics.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.service.ReliefService;
import dev.hywmill.garrison.service.SiegeService;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.realm.NationalArmy;
import dev.hywmill.politics.realm.RealmCall;
import dev.hywmill.politics.realm.RealmPlan;
import dev.hywmill.politics.realm.SiegeAims;
import dev.hywmill.politics.realm.Strategy;
import dev.hywmill.politics.war.Siege;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The realm's council (docs/realm-design.md §10): one mind for a realm with a national army. It sits every tenth of a day:
 * it sees its members (what each pledges, which is threatened or besieged, which can lead an army) and its enemies (their
 * strength at home, whether their armies are away, whether they were once its own, its people, or hated), takes a stance,
 * holds back a reserve, and raises field armies, one new army a session, each led by the member nearest its target with the
 * members around it. At peace it covets a neighbour it has a claim on, and goes to war for it a day later. Its members no
 * longer besiege on their own. Its stance, goals, armies and decisions are on the Realm tab.
 */
public final class RealmCouncil {
    private RealmCouncil() {}

    /** A council sits this often (ticks): a tenth of a day. */
    public static final long SESSION = PoliticsTables.DAY / 4;
    /** It raises at most one army a session, and waits this long between two. */
    public static final long LAUNCH_GAP = PoliticsTables.DAY / 2;
    /** It covets a village this long before it goes to war for it. */
    public static final long COVET_TICKS = PoliticsTables.DAY; // 1.10: a day (was two)
    /** It looks this far from its villages for a village to covet (blocks). */
    public static final double NEIGHBOUR = 1500;
    /** 1.7: its front: enemies farther than this from all its villages are not its goals (unless their army marches on it). */
    public static final double FRONT = 2000;
    /** 1.7: the goals it pursues at once (besides defending its own), and the villages it courts at once. */
    public static final int MAX_GOALS = 3, MAX_COURTED = 2;
    /** 1.7: it covets nothing until it has been at peace this long. */
    public static final long PEACE_FIRST = PoliticsTables.DAY; // 1.10: a day (was two)

    public static boolean enabled() {
        return RealmService.enabled() && HywMillConfig.REALM_COUNCIL.get();
    }

    /** {@code v}'s wars are run by its realm's council: it belongs to a realm with a national army. */
    public static boolean governs(GarrisonLedger ledger, UUID v, long now) {
        return enabled() && RealmService.national(ledger, RealmService.sovereignOf(ledger, v, now), now);
    }

    /** Each village AI interval: every council whose session is due sits; the plans of realms no more are dropped. */
    public static void sit(ServerLevel ow, GarrisonLedger ledger, SiegeService sieges, long now) {
        if (!enabled()) {
            return;
        }
        ledger.plans().keySet().removeIf(h -> !RealmService.national(ledger, h, now));
        ledger.coalitions().keySet().removeIf(h -> !ledger.plans().containsKey(h));
        for (VillageRecord v : new ArrayList<>(ledger.all())) {
            if (!v.loneBuilding && RealmService.national(ledger, v.villageId, now)) {
                RealmPlan p = ledger.plans().computeIfAbsent(v.villageId, k -> new RealmPlan());
                if (p.lastSession < 0 || now - p.lastSession >= SESSION) {
                    session(ow, ledger, sieges, v, p, now);
                }
                // 1.7: an enemy host on the road to one of its villages: meet it there if the army is a match (§13)
                double commit = 1 - Math.max(0, Math.min(1, Strategy.reserveShare(p.stance == Strategy.Stance.AT_PEACE ? Strategy.Stance.BALANCED : p.stance)
                        + dev.hywmill.politics.realm.Officials.reserveDelta(ServiceRoles.officials(ledger, v))));
                for (String d : SiegeService.considerIntercepts(ow, ledger, v, RealmService.members(ledger, v.villageId, now), commit, now)) {
                    note(ow, ledger, Services.settlements(), v, p, now, d);
                }
                if (p.lastLeague < 0 || now - p.lastLeague >= PoliticsTables.DAY) {
                    p.lastLeague = now;
                    SettlementSource source = Services.settlements();
                    if (source != null) {
                        RealmDiplomacy.leagues(ow, ledger, source, v, p, now); // 1.7: its neighbours fear a great realm (§12)
                        RealmDiplomacy.appease(ow, ledger, source, v, p, now); // 1.8: and each seeks peace with it (§15)
                    }
                }
            }
        }
    }

    static long day(long now) {
        return now / PoliticsTables.DAY + 1;
    }

    /** One session: the council sees, decides and acts. */
    public static void session(ServerLevel ow, GarrisonLedger ledger, SiegeService sieges, VillageRecord capital, RealmPlan p, long now) {
        SettlementSource source = Services.settlements();
        if (source == null) {
            return;
        }
        p.lastSession = now;
        ledger.setDirty();
        UUID head = capital.villageId;
        List<UUID> realm = RealmService.members(ledger, head, now);
        // 1.7: its scouts see which of its villages an enemy council is ready to march on (§12); its officials' tempers (§11)
        java.util.Map<UUID, UUID> eyed = RealmDiplomacy.eyed(ledger, head, realm, now);
        p.eyed.clear();
        p.eyed.putAll(eyed);
        List<dev.hywmill.politics.realm.Officials.Official> officials = ServiceRoles.officials(ledger, capital);
        int engines = SiegeService.marchingEngines(capital);
        List<Strategy.Member> members = new ArrayList<>();
        for (UUID m : realm) {
            VillageRecord v = ledger.get(m);
            if (v == null || v.hywRoster == null) {
                continue;
            }
            int free = ReliefService.freeAtHome(v);
            int men = NationalArmy.pledge(free, RealmService.pledgeShare(ledger, m, now));
            double power = free <= 0 ? 0 : ReliefService.freeStrength(v) * men / free;
            boolean threatened = false, besieged = false;
            for (Siege s : ledger.sieges()) {
                if (s.target.equals(m) && s.outcome == Siege.Outcome.NONE) {
                    besieged |= s.fighting();
                    threatened |= s.phase == Siege.Phase.PREPARE || s.phase == Siege.Phase.MUSTER || s.phase == Siege.Phase.MARCH || s.phase == Siege.Phase.WAIT;
                }
            }
            threatened |= eyed.containsKey(m);
            boolean seatFree = SiegeService.byAttacker(ledger, m) == null && !SiegeService.besieged(ledger, m);
            members.add(new Strategy.Member(m, v.center.getX(), v.center.getZ(), men, power, m.equals(head), seatFree, threatened, besieged));
        }
        // its enemies: every village at war with one of its members (lone buildings take no part)
        List<Strategy.Target> targets = new ArrayList<>();
        p.farEnemies = 0;
        Set<UUID> enemyHeads = new LinkedHashSet<>();
        for (VillageRecord e : ledger.all()) {
            if (e.loneBuilding || realm.contains(e.villageId)) {
                continue;
            }
            boolean atWar = false;
            for (UUID m : realm) {
                atWar |= RelationProjector.atWar(ledger, m, e.villageId);
            }
            if (!atWar) {
                continue;
            }
            // 1.7: only its front: an enemy near one of its villages, or one whose army marches on it
            double near = Double.MAX_VALUE;
            for (UUID m : realm) {
                VillageRecord mv = ledger.get(m);
                if (mv != null) {
                    near = Math.min(near, Math.sqrt(mv.center.distSqr(e.center)));
                }
            }
            Siege theirs = SiegeService.byAttacker(ledger, e.villageId);
            if (near > FRONT && (theirs == null || !realm.contains(theirs.target))) {
                p.farEnemies++;
                continue;
            }
            targets.add(target(ow, ledger, source, capital, e, engines, now));
            enemyHeads.add(RealmService.sovereignOf(ledger, e.villageId, now));
        }
        double enemyPower = 0;
        for (UUID h : enemyHeads) {
            boolean nat = RealmService.national(ledger, h, now);
            for (UUID m : RealmService.members(ledger, h, now)) {
                VillageRecord v = ledger.get(m);
                if (v != null && v.hywRoster != null) {
                    enemyPower += ReliefService.freeStrength(v) * (nat ? RealmService.pledgeShare(ledger, m, now) : 0.5);
                }
            }
        }
        // 1.7: the strength its military allies would send with an army against each target, and the targets its knights urged
        java.util.Map<UUID, Double> allied = new java.util.HashMap<>();
        for (dev.hywmill.politics.realm.Treaty tr : ledger.treaties().values()) {
            if (!tr.military() || !tr.involves(head)) {
                continue;
            }
            VillageRecord c = ledger.get(tr.other(head));
            if (c == null || c.hywRoster == null || realm.contains(c.villageId) || SiegeService.besieged(ledger, c.villageId)) {
                continue;
            }
            double share = ReliefService.freeStrength(c) * 0.275; // allies send 20-35% of their free garrison
            for (Strategy.Target t : targets) {
                VillageRecord tv = ledger.get(t.id());
                if (tv != null && !tv.villageId.equals(c.villageId) && !RelationProjector.atWar(ledger, c.villageId, head)
                        && c.center.distSqr(tv.center) <= (double) dev.hywmill.politics.realm.Treaty.REACH * dev.hywmill.politics.realm.Treaty.REACH) {
                    allied.merge(t.id(), share, Double::sum);
                }
            }
        }
        p.urged.values().removeIf(until -> until < now);
        Strategy.Counsel counsel = new Strategy.Counsel(dev.hywmill.politics.realm.Officials.marginFactor(officials),
                dev.hywmill.politics.realm.Officials.reserveDelta(officials), allied, java.util.Set.copyOf(p.urged.keySet()));
        Strategy.Plan plan = Strategy.plan(members, targets, enemyPower, !targets.isEmpty(), counsel);
        if (plan.stance() != p.stance) {
            note(ow, ledger, source, capital, p, now, "the council turns " + plan.stance().label
                    + (plan.stance() == Strategy.Stance.AT_PEACE ? "" : " (our army against theirs: " + String.format("%.1f", Math.min(9.9, plan.ratio())) + " to 1)"));
        }
        p.stance = plan.stance();
        p.ratio = plan.ratio();
        p.men = plan.men();
        p.reserve = plan.reserve();
        p.goals.clear();
        // 1.7: it pursues a few goals at a time, the worthiest (its own villages' defence always)
        int pursued = 0;
        for (Strategy.Goal g : plan.goals()) {
            if (g.kind() == Strategy.Kind.DEFEND || pursued++ < MAX_GOALS) {
                p.goals.add(g);
            }
        }
        p.farEnemies += Math.max(0, pursued - MAX_GOALS);
        if (plan.stance() == Strategy.Stance.AT_PEACE) {
            if (p.peaceSince < 0) {
                p.peaceSince = now;
            }
        } else {
            p.peaceSince = -1;
        }
        // on the defensive with a village threatened: armies not yet at the walls are called home to defend it, and none sets out
        boolean pressed = plan.stance() == Strategy.Stance.DEFENSIVE && members.stream().anyMatch(m -> m.threatened() || m.besieged());
        if (pressed) {
            recall(ow, ledger, source, capital, p, realm, members, now);
        }
        // raise an army: one a session at most, the worthiest that is ready
        if (!pressed && (now - p.lastLaunch >= LAUNCH_GAP || p.lastLaunch < 0)) {
            for (Strategy.Army a : plan.armies()) {
                if (launch(ow, ledger, sieges, source, capital, p, a, plan.stance(), counsel.reserveDelta(), now)) {
                    break;
                }
            }
        }
        court(ow, ledger, source, capital, p, realm, targets, now);
        RealmDiplomacy.sue(ow, ledger, source, capital, p, realm, targets, now); // 1.7: losing, it offers tribute for peace (§12)
        RealmDiplomacy.focus(ow, ledger, source, capital, p, realm, now); // 1.8: a few wars at a time (§15)
        if (plan.stance() == Strategy.Stance.AT_PEACE && now - p.peaceSince >= PEACE_FIRST) {
            ambition(ow, ledger, source, capital, p, members, realm, engines, dev.hywmill.politics.realm.Officials.covetFactor(officials), now);
        } else if (p.covets != null) {
            p.covets = null;
            p.covetSince = -1;
        }
    }

    /** Friendship gained each day with a village that fights one of the realm's enemies, up to {@link #COURT_MAX}. */
    public static final int COURT_STEP = 5, COURT_MAX = 80, COURT_MIN = -10;

    /**
     * The council calls home the armies it sent that are not yet at the walls (gathering, mustering, on the road) to defend
     * its threatened villages: their men come home, and march with the realm's army to break a siege of their own.
     */
    static void recall(ServerLevel ow, GarrisonLedger ledger, SettlementSource source, VillageRecord capital, RealmPlan p, List<UUID> realm,
                       List<Strategy.Member> members, long now) {
        List<String> threatened = new ArrayList<>();
        for (Strategy.Member m : members) {
            VillageRecord v = ledger.get(m.id());
            if (v != null && (m.threatened() || m.besieged())) {
                threatened.add(v.name);
            }
        }
        for (Siege s : new ArrayList<>(ledger.sieges())) {
            boolean early = s.phase == Siege.Phase.PREPARE || s.phase == Siege.Phase.MUSTER || s.phase == Siege.Phase.MARCH || s.phase == Siege.Phase.WAIT;
            if (!early || s.outcome != Siege.Outcome.NONE || !realm.contains(s.attacker) || realm.contains(s.target)) {
                continue;
            }
            VillageRecord a = ledger.get(s.attacker), t = ledger.get(s.target);
            if (a == null || t == null) {
                continue;
            }
            String name = s.armyName.isEmpty() ? "the host of " + a.name : s.armyName;
            if (SiegeService.recall(ow, ledger, a.villageId, t.villageId, now) > 0) {
                note(ow, ledger, source, capital, p, now, "the council calls " + name + " home from the road to " + t.name + " to defend "
                        + String.join(", ", threatened));
            }
        }
    }

    /**
     * The enemy of its enemy: the council courts each village (not its own, not at war with it) that fights one of the realm's
     * enemies: their friendship rises each session, so a military alliance follows by the treaty rule (75).
     */
    static void court(ServerLevel ow, GarrisonLedger ledger, SettlementSource source, VillageRecord capital, RealmPlan p, List<UUID> realm,
                      List<Strategy.Target> enemies, long now) {
        p.courting.removeIf(c -> ledger.get(c) == null);
        if (p.lastCourt >= 0 && now - p.lastCourt < PoliticsTables.DAY) {
            return; // 1.7: once a day
        }
        p.lastCourt = now;
        // 1.7: at most two at a time, the nearest within reach of its capital
        List<VillageRecord> near = new ArrayList<>();
        for (VillageRecord c : ledger.all()) {
            if (!c.loneBuilding && !realm.contains(c.villageId)
                    && c.center.distSqr(capital.center) <= (double) dev.hywmill.politics.realm.Treaty.REACH * dev.hywmill.politics.realm.Treaty.REACH) {
                near.add(c);
            }
        }
        near.sort(java.util.Comparator.comparingDouble(c -> c.center.distSqr(capital.center)));
        p.courting.removeIf(c -> near.stream().noneMatch(v -> v.villageId.equals(c)));
        int courted = 0;
        for (VillageRecord c : near) {
            if (RealmService.tieOf(ledger, c.villageId, now) != null) {
                continue;
            }
            if (courted >= MAX_COURTED && !p.courting.contains(c.villageId)) {
                continue;
            }
            boolean ours = false;
            for (UUID m : realm) {
                ours |= RelationProjector.atWar(ledger, m, c.villageId);
            }
            UUID common = null;
            for (Strategy.Target e : enemies) {
                if (!e.id().equals(c.villageId) && RelationProjector.atWar(ledger, c.villageId, e.id())) {
                    common = e.id();
                    break;
                }
            }
            Integer rel = RealmService.relation(ow, source, capital.villageId, c.villageId);
            if (ours || common == null || rel == null || rel < COURT_MIN || rel >= COURT_MAX) {
                p.courting.remove(c.villageId);
                continue;
            }
            if (courted >= MAX_COURTED) {
                p.courting.remove(c.villageId);
                continue;
            }
            courted++;
            source.setVillageRelation(ow, capital.villageId, c.villageId, Math.min(COURT_MAX, rel + COURT_STEP));
            if (!p.courting.contains(c.villageId)) {
                p.courting.add(c.villageId);
                VillageRecord foe = ledger.get(common);
                note(ow, ledger, source, capital, p, now, "the council seeks the friendship of " + c.name + " (a common enemy: " + (foe != null ? foe.name : "?") + ")");
            }
        }
    }

    /** An enemy, or a neighbour, as the council sees it. */
    static Strategy.Target target(ServerLevel ow, GarrisonLedger ledger, SettlementSource source, VillageRecord capital, VillageRecord e, int engines, long now) {
        Siege away = SiegeService.byAttacker(ledger, e.villageId);
        boolean hostAway = away != null && away.outcome == Siege.Outcome.NONE && away.phase != Siege.Phase.PREPARE && away.phase != Siege.Phase.RETURN;
        Integer rel = RealmService.relation(ow, source, capital.villageId, e.villageId);
        boolean kin = e.culture.equals(capital.culture) && RealmService.tieOf(ledger, e.villageId, now) != null;
        return new Strategy.Target(e.villageId, e.center.getX(), e.center.getZ(), SiegeService.defense(ow, e, engines),
                capital.villageId.equals(ledger.rebels().get(e.villageId)), hostAway, rel != null && rel <= RealmCall.HATE, kin,
                SiegeService.against(ledger, e.villageId) != null, e.tier.ordinal());
    }

    /** 1.10: a target this near the capital (blocks) is taken as a province, not made a vassal, while the realm has room. */
    public static final double ANNEX_REACH = 1200;

    /** What the council's army fights for (1.10: land before oaths: it annexes what it can hold, near enough to hold it). */
    static SiegeAims.Aim aim(GarrisonLedger ledger, VillageRecord capital, VillageRecord t, Strategy.Kind kind, long now) {
        boolean room = RealmService.provinceRoom(ledger, capital, now);
        boolean near = Math.sqrt(capital.center.distSqr(t.center)) <= ANNEX_REACH;
        return switch (kind) {
            case RECLAIM, CONQUER -> room ? SiegeAims.Aim.ANNEX : SiegeAims.Aim.SUBJUGATE;
            case PUNISH -> SiegeAims.Aim.PUNISH;
            default -> room && near && t.tier.ordinal() <= capital.tier.ordinal() ? SiegeAims.Aim.ANNEX : SiegeAims.Aim.SUBJUGATE;
        };
    }

    /** Raises {@code a}: its seat leads it, its members march in it, each with its pledge less the reserve. */
    static boolean launch(ServerLevel ow, GarrisonLedger ledger, SiegeService sieges, SettlementSource source, VillageRecord capital, RealmPlan p,
                          Strategy.Army a, Strategy.Stance stance, double reserveDelta, long now) {
        VillageRecord seat = ledger.get(a.seat()), t = ledger.get(a.target());
        if (seat == null || t == null || SiegeService.against(ledger, t.villageId) != null || SiegeService.byAttacker(ledger, seat.villageId) != null) {
            return false;
        }
        double commit = 1 - Math.max(0, Math.min(1, Strategy.reserveShare(stance) + reserveDelta));
        List<UUID> others = new ArrayList<>(a.members());
        others.remove(seat.villageId);
        String name = seat == capital ? "the army of the realm of " + capital.name : "the army of " + seat.name;
        SiegeAims.Aim aim = aim(ledger, capital, t, a.kind(), now);
        var l = sieges.launch(ow, seat.villageId, t.villageId, null, now, true, false, aim,
                new SiegeService.CouncilOrder(name, others, RealmService.pledgeShare(ledger, seat.villageId, now) * commit, commit, "the council of " + capital.name));
        if (!l.ok()) {
            HmLog.info("Realm council of {}: {} on {} not raised: {}", capital.name, name, t.name, l.detail());
            return false;
        }
        p.lastLaunch = now;
        List<String> from = new ArrayList<>();
        for (UUID m : a.members()) {
            VillageRecord v = ledger.get(m);
            if (v != null) {
                from.add(v.name);
            }
        }
        note(ow, ledger, source, capital, p, now, "the council sends " + name + " (about " + Math.round(a.men() * commit) + " men from "
                + String.join(", ", from) + ") to " + aim.name().toLowerCase() + " " + t.name + " (" + a.kind().label.toLowerCase() + ")");
        return true;
    }

    /**
     * At peace: the council covets a neighbour it has a claim on and could take, or (1.10) with room for a province and no claim,
     * a weak free neighbour at its border ({@link Strategy#expand}); a day later, if it still can, it demands it and, refused,
     * declares war on it (and the realms' unity takes it from there).
     */
    static void ambition(ServerLevel ow, GarrisonLedger ledger, SettlementSource source, VillageRecord capital, RealmPlan p, List<Strategy.Member> members,
                         List<UUID> realm, int engines, double covetFactor, long now) {
        if (!HywMillConfig.REALM_AMBITION.get()) {
            p.covets = null;
            return;
        }
        List<Strategy.Target> neighbours = new ArrayList<>(), free = new ArrayList<>();
        for (VillageRecord e : ledger.all()) {
            if (e.loneBuilding || realm.contains(e.villageId) || RealmService.treaty(ledger, capital.villageId, e.villageId) != null
                    || capital.politics.truceWith(e.villageId, now) || e.politics.truceWith(capital.villageId, now)) {
                continue;
            }
            double d = Double.MAX_VALUE;
            for (UUID m : realm) {
                VillageRecord v = ledger.get(m);
                if (v != null) {
                    d = Math.min(d, Math.sqrt(v.center.distSqr(e.center)));
                }
            }
            if (d <= NEIGHBOUR) {
                Strategy.Target n = target(ow, ledger, source, capital, e, engines, now);
                neighbours.add(n);
                if (RealmService.tieOf(ledger, e.villageId, now) == null && RealmService.subjectsOf(ledger, e.villageId, now).isEmpty()) {
                    free.add(n);
                }
            }
        }
        Strategy.Target want = Strategy.covet(members, neighbours, covetFactor);
        if (want == null && RealmService.provinceRoom(ledger, capital, now)) {
            want = Strategy.expand(members, free, covetFactor); // 1.10: land for the realm
        }
        if (want == null) {
            if (p.covets != null) {
                VillageRecord was = ledger.get(p.covets);
                note(ow, ledger, source, capital, p, now, "the council gives up its claim" + (was != null ? " on " + was.name : ""));
            }
            p.covets = null;
            p.covetSince = -1;
            return;
        }
        VillageRecord t = ledger.get(want.id());
        if (t == null) {
            return;
        }
        String claim = want.formerMember() ? "it was ours" : want.kin() ? "its people are ours, under a foreign lord"
                : want.hated() ? "the realm hates it" : "it is weak and lies at our border";
        if (!want.id().equals(p.covets)) {
            p.covets = want.id();
            p.covetSince = now;
            p.claim = claim;
            note(ow, ledger, source, capital, p, now, "the council covets " + t.name + " (" + claim + ")");
            return;
        }
        if (now - p.covetSince >= COVET_TICKS) {
            p.covets = null;
            p.covetSince = -1;
            // 1.7: first a demand: a much weaker village may swear fealty rather than fight (§12)
            double ours = members.stream().mapToDouble(Strategy.Member::power).sum();
            if (RealmDiplomacy.demand(ow, ledger, capital, p, t, ours, now)) {
                note(ow, ledger, source, capital, p, now, RealmService.isProvince(ledger, t.villageId, now)
                        ? "the council demands that " + t.name + " submit (" + claim + "): it opens its gates, and is a province of the realm"
                        : "the council demands that " + t.name + " swear fealty (" + claim + "): it bows, and is the realm's vassal");
                return;
            }
            note(ow, ledger, source, capital, p, now, "the council claims " + t.name + " (" + claim + ")" + (RealmDiplomacy.enabled()
                    ? ", demands its submission, is refused" : "") + " and declares war");
            WarCounselService.declareWar(ow, ledger, capital, t, now, "the council of " + capital.name + " claims it: " + claim);
        }
    }

    static void note(ServerLevel ow, GarrisonLedger ledger, @Nullable SettlementSource source, VillageRecord capital, RealmPlan p, long now, String what) {
        p.note("Day " + day(now) + ": " + what);
        ledger.setDirty();
        PoliticsService.chronicle(ow, source, capital, now, "The council of " + capital.name + ": " + what);
        HmLog.info("Realm council of {}: {}", capital.name, what);
    }

    /** The council's section of the Realm tab: stance, armies (where they are), goals, the men held back, its claim, its log. */
    public static List<String> lines(ServerLevel ow, GarrisonLedger ledger, UUID head, long now) {
        List<String> out = new ArrayList<>();
        VillageRecord capital = ledger.get(head);
        if (capital == null || !RealmService.national(ledger, head, now)) {
            return out;
        }
        RealmPlan p = ledger.plans().get(head);
        out.add("");
        if (!enabled() || p == null) {
            out.add("The army of the realm of " + capital.name + ":");
        } else {
            out.add("The council of " + capital.name + ": " + p.stance.label + (p.stance == Strategy.Stance.AT_PEACE ? ""
                    : " (our army against our enemies': " + String.format("%.1f", Math.min(9.9, p.ratio)) + " to 1)") + ".");
        }
        List<String> armies = SiegeService.armyLines(ow, ledger, head, now);
        out.add(" Armies:");
        if (armies.isEmpty()) {
            out.add("  All at home.");
        } else {
            armies.forEach(l -> out.add(" " + l));
        }
        if (!enabled() || p == null) {
            return out;
        }
        out.add(" Goals:");
        if (p.goals.isEmpty()) {
            out.add("  None: the realm is at peace" + (p.covets != null ? "" : " and covets no one") + ".");
        }
        for (Strategy.Goal g : p.goals) {
            VillageRecord t = ledger.get(g.target());
            if (t == null) {
                continue;
            }
            Siege on = SiegeService.against(ledger, g.target());
            String state;
            if (g.kind() == Strategy.Kind.DEFEND) {
                state = "its men stay home: " + (SiegeService.besieged(ledger, g.target()) || (on != null && on.fighting()) ? "it is besieged" : "a host marches on it");
            } else if (on != null && RealmService.realmOf(ledger, head, now).contains(on.attacker)) {
                state = "our army is on it";
            } else if (g.ready()) {
                state = "an army is ready to march";
            } else {
                state = "waiting for men (strength " + Math.round(g.power()) + " of " + Math.round(g.needed()) + " needed)";
            }
            out.add("  " + g.kind().label + " " + t.name + ": " + state + ".");
        }
        if (p.farEnemies > 0) {
            out.add("  (" + p.farEnemies + " other enem" + (p.farEnemies == 1 ? "y" : "ies") + " too far or not worth an army now.)");
        }
        if (p.stance != Strategy.Stance.AT_PEACE) {
            out.add(" Held back to defend: " + p.reserve + " of " + p.men + " men pledged.");
        }
        p.eyed.forEach((m, by) -> {
            VillageRecord mv = ledger.get(m), bv = ledger.get(by);
            if (mv != null && bv != null) {
                out.add(" Our scouts: the council of " + bv.name + " is ready to march on " + mv.name + "; its men stay home.");
            }
        });
        p.urged.forEach((t, until) -> {
            VillageRecord tv = ledger.get(t);
            if (tv != null && until > now) {
                out.add(" Its knights urge it on against " + tv.name + " (for about " + Math.max(1, Math.round((until - now) / 1200.0)) + " min more).");
            }
        });
        out.addAll(RealmDiplomacy.lines(ledger, head, now));
        for (UUID c : p.courting) {
            VillageRecord v = ledger.get(c);
            Integer rel = v == null || Services.settlements() == null ? null : RealmService.relation(ow, Services.settlements(), head, c);
            if (v != null) {
                out.add(" Courts " + v.name + " against a common enemy (relation " + (rel == null ? "?" : rel) + "; an alliance at 75).");
            }
        }
        if (p.covets != null) {
            VillageRecord c = ledger.get(p.covets);
            if (c != null) {
                long left = Math.max(0, COVET_TICKS - (now - p.covetSince));
                out.add(" Covets " + c.name + " (" + p.claim + "): war in about " + Math.max(1, Math.round(left / 1200.0)) + " min unless it changes its mind.");
            }
        }
        if (!p.log.isEmpty()) {
            out.add(" Lately:");
            p.log.forEach(l -> out.add("  " + l));
        }
        return out;
    }
}

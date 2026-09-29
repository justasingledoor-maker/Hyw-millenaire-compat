package dev.hywmill.garrison.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.service.PoliticsService;
import dev.hywmill.politics.service.RelationProjector;
import dev.hywmill.politics.war.Relief;
import dev.hywmill.politics.war.Siege;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Post-M5 relief forces ({@link Relief}). When a siege is launched, villages on great terms with the besieged village may
 * promise relief; each sends a small force when the attackers march, which arrives by forced march in 1 to 2 minutes (usually
 * before the attackers), unless ambushed or lost on the way. The force stands spread round the village, fights the
 * attackers (their factions are projected HOSTILE while it is out) and counts among the defenders; it goes home when the
 * siege ends. Driven by {@link SiegeService} on the siege's own step.
 */
public final class ReliefService {
    private ReliefService() {}

    static PoliticsTables.ReliefRule rule(VillageRecord rec) {
        return PoliticsService.tables(rec).relief();
    }

    /** Soldiers of {@code v} at home and free to go (garrisoned on a standing duty, not war engines). */
    static List<RosterEntry> available(VillageRecord v) {
        List<RosterEntry> out = new ArrayList<>();
        GarrisonRoster r = v.hywRoster;
        if (r == null) {
            return out;
        }
        for (RosterEntry e : r.entries()) {
            if (e.state() == UnitState.GARRISONED && e.duty.standing()) {
                out.add(e);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ planning (at launch)

    /** At launch: the besieged village's friends decide whether they will send relief (seeded; at most maxHelpers). */
    static void plan(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick) {
        SettlementSource source = Services.settlements();
        PoliticsTables.ReliefRule r = rule(t);
        if (source == null || !r.enabled() || r.maxHelpers() <= 0) {
            return;
        }
        List<VillageRecord> friends = new ArrayList<>();
        for (VillageRecord v : ledger.all()) {
            int rel = source.villageRelation(overworld, v.villageId, t.villageId).orElse(Integer.MIN_VALUE);
            boolean party = v.villageId.equals(a.villageId) || v.villageId.equals(t.villageId);
            if (Relief.eligible(rel, RelationProjector.atWar(ledger, v.villageId, t.villageId), party, v.loneBuilding, available(v).size(), r)) {
                friends.add(v);
            }
        }
        friends.sort(Comparator.comparing(v -> v.villageId));
        SplittableRandom rnd = new SplittableRandom(s.seed(0x7e11efL));
        for (VillageRecord v : friends) {
            if (s.reliefs.size() >= r.maxHelpers()) {
                break;
            }
            if (rnd.nextDouble() < r.chance()) {
                s.reliefs.add(new Relief(v.villageId));
                HmLog.info("Siege {}: {} will send relief to {}", s.id.toString().substring(0, 8), v.name, t.name);
            }
        }
    }

    /** Admin/dev: {@code helper} promises relief to the siege of {@code s} ({@code fate}: NONE rolls the journey, else forced). */
    public static String promise(GarrisonLedger ledger, Siege s, VillageRecord helper, Relief.Fate fate) {
        if (helper.villageId.equals(s.attacker) || helper.villageId.equals(s.target)) {
            return "a party to the siege cannot relieve it";
        }
        for (Relief r : s.reliefs) {
            if (r.helper.equals(helper.villageId)) {
                return helper.name + " already sends relief";
            }
        }
        Relief r = new Relief(helper.villageId);
        r.fate = fate;
        s.reliefs.add(r);
        ledger.setDirty();
        return helper.name + " will send relief" + (fate == Relief.Fate.NONE ? "" : " (fate " + fate + ")");
    }

    // ------------------------------------------------------------------ step

    /** One step of every relief force of {@code s}. */
    static void step(ServerLevel overworld, GarrisonLedger ledger, Siege s, @Nullable VillageRecord a, @Nullable VillageRecord t, long tick) {
        boolean over = s.outcome != Siege.Outcome.NONE || s.phase == Siege.Phase.RETURN || t == null;
        for (Relief rl : s.reliefs) {
            VillageRecord h = ledger.get(rl.helper);
            if (h == null || h.hywRoster == null) {
                rl.phase = Relief.Phase.DONE;
                continue;
            }
            switch (rl.phase) {
                case PENDING -> {
                    if (over) {
                        rl.phase = Relief.Phase.DONE; // the siege ended before the attackers marched: nobody set out
                    } else if (s.phase != Siege.Phase.MUSTER) {
                        dispatch(overworld, ledger, s, rl, h, a, t, tick);
                    }
                }
                case MARCH -> {
                    if (over) {
                        turnBack(overworld, ledger, s, rl, h, t, tick, "the siege is over before they arrive");
                    } else if (tick >= rl.phaseEnd) {
                        arrive(overworld, ledger, s, rl, h, a, t, tick);
                    }
                }
                case PRESENT -> {
                    if (over) {
                        turnBack(overworld, ledger, s, rl, h, t, tick, "the siege is over");
                    } else {
                        materialize(overworld, ledger, s, rl, h, t, tick);
                    }
                }
                case RETURN -> {
                    if (tick >= rl.phaseEnd && bringHome(overworld, h, rl.units, tick)) {
                        rl.phase = rl.strays.isEmpty() ? Relief.Phase.DONE : rl.phase;
                    }
                }
                case DONE -> { }
            }
            if (!rl.strays.isEmpty() && rl.phase != Relief.Phase.MARCH && rl.phase != Relief.Phase.PENDING && bringHome(overworld, h, rl.strays, tick)) {
                rl.strays.clear();
                if (rl.phase == Relief.Phase.RETURN && rl.units.isEmpty()) {
                    rl.phase = Relief.Phase.DONE;
                }
            }
            if (rl.phase == Relief.Phase.RETURN && rl.units.isEmpty() && rl.strays.isEmpty()) {
                rl.phase = Relief.Phase.DONE;
            }
        }
    }

    private static void dispatch(ServerLevel overworld, GarrisonLedger ledger, Siege s, Relief rl, VillageRecord h, @Nullable VillageRecord a,
                                 VillageRecord t, long tick) {
        PoliticsTables.ReliefRule r = rule(h);
        List<RosterEntry> pool = available(h);
        SplittableRandom rnd = new SplittableRandom(s.seed(h.villageId.getLeastSignificantBits()));
        int n = Relief.size(pool.size(), rnd.nextDouble(), r);
        if (n <= 0) {
            rl.phase = Relief.Phase.DONE;
            return;
        }
        pool.sort(Comparator.comparing(e -> e.rosterId));
        Collections.shuffle(pool, new Random(rnd.nextLong()));
        for (RosterEntry e : pool.subList(0, n)) {
            e.transition(UnitState.DEPLOYED, tick);
            e.duty = Duty.SIEGE;
            GarrisonService.stow(overworld, e);
            rl.units.add(e.rosterId);
        }
        rl.sent = n;
        long travel = Relief.travel(rnd.nextDouble(), r);
        rl.phase = Relief.Phase.MARCH;
        rl.phaseEnd = tick + travel;
        ledger.setDirty();
        String text = h.name + " sends " + n + " soldier" + (n == 1 ? "" : "s") + " by forced march to relieve " + t.name
                + (a != null ? " against " + a.name : "") + "; they arrive in about " + Math.max(1, Math.round(travel / 1200.0)) + " min";
        SiegeService.chronicle(overworld, h, t, tick, text);
        SiegeService.announceNear(overworld, ledger, s, text);
        HmLog.info("Siege {}: relief: {}", s.id.toString().substring(0, 8), text);
    }

    private static void arrive(ServerLevel overworld, GarrisonLedger ledger, Siege s, Relief rl, VillageRecord h, @Nullable VillageRecord a,
                               VillageRecord t, long tick) {
        PoliticsTables.ReliefRule r = rule(h);
        List<UUID> order = new ArrayList<>(rl.units);
        Collections.sort(order);
        Collections.shuffle(order, new Random(s.seed(h.villageId.getMostSignificantBits())));
        Relief.Journey j = rl.fate == Relief.Fate.NONE ? Relief.journey(order.size(), s.seed(h.villageId.getMostSignificantBits() ^ 0x5eedL), r)
                : forced(rl.fate, order.size());
        for (UUID id : order.subList(0, j.killed())) {
            SiegeService.kill(overworld, h, id, tick);
        }
        List<UUID> strays = order.subList(j.killed(), j.killed() + j.strays());
        rl.strays.addAll(strays);
        rl.units.clear();
        rl.units.addAll(order.subList(j.killed() + j.strays(), order.size()));
        rl.killed = j.killed();
        rl.fate = j.fate();
        int arrived = rl.units.size();
        String text = switch (j.fate()) {
            case CLEAN, NONE -> h.name + "'s relief (" + arrived + ") reaches " + t.name + " and takes position round the village";
            case AMBUSHED -> h.name + "'s relief was ambushed on the road: " + j.killed() + " fell, " + arrived + " reach " + t.name;
            case ROUTED -> h.name + "'s relief was ambushed and routed: " + j.killed() + " fell, the rest fled home; none reach " + t.name;
            case STRAGGLED -> h.name + "'s relief lost its way: only " + arrived + " of " + rl.sent + " reach " + t.name;
            case LOST -> h.name + "'s relief lost its way and never reaches " + t.name;
        };
        rl.phase = arrived > 0 ? Relief.Phase.PRESENT : Relief.Phase.RETURN;
        rl.phaseEnd = tick;
        ledger.setDirty();
        SiegeService.chronicle(overworld, h, t, tick, text);
        SiegeService.announceNear(overworld, ledger, s, text);
        HmLog.info("Siege {}: relief {}: {}", s.id.toString().substring(0, 8), j.fate(), text);
        if (arrived > 0) {
            materialize(overworld, ledger, s, rl, h, t, tick);
        }
    }

    /** A forced fate (admin/dev): fixed shares. */
    static Relief.Journey forced(Relief.Fate fate, int n) {
        return switch (fate) {
            case AMBUSHED -> new Relief.Journey(fate, Math.min(n, Math.max(1, Math.round(n * 0.4f))), 0);
            case ROUTED -> {
                int k = Math.min(n, Math.max(1, Math.round(n * 0.3f)));
                yield new Relief.Journey(fate, k, n - k);
            }
            case STRAGGLED -> new Relief.Journey(n <= 1 ? Relief.Fate.LOST : fate, 0, Math.max(1, n / 2));
            case LOST -> new Relief.Journey(fate, 0, n);
            default -> new Relief.Journey(Relief.Fate.CLEAN, 0, 0);
        };
    }

    /** Units of a present force that are not in the world appear at their posts round the target, once it is loaded. */
    private static void materialize(ServerLevel overworld, GarrisonLedger ledger, Siege s, Relief rl, VillageRecord h, VillageRecord t, long tick) {
        if (!overworld.isPositionEntityTicking(t.center)) {
            return;
        }
        int placed = 0;
        for (int i = 0; i < rl.units.size(); i++) {
            RosterEntry e = h.hywRoster.entry(rl.units.get(i));
            if (e == null || e.state().terminal() || e.entityUuid != null) {
                continue;
            }
            int[] o = Relief.post(i, rl.units.size(), t.villageRadius > 0 ? t.villageRadius : SiegeService.DEFAULT_RADIUS, s.seed(7));
            BlockPos p = SiegeService.surface(overworld, t.center.offset(o[0], 0, o[1]));
            Vec3 spot = GarrisonService.spotNear(overworld, p, e.rosterId);
            if (spot != null && GarrisonService.materialize(overworld, h, e, spot, BlockPos.containing(spot), tick)) {
                placed++;
            }
        }
        if (placed > 0) {
            if (s.phase == Siege.Phase.BATTLE) {
                s.defendersStart += placed; // they joined a battle under way
            }
            ledger.setDirty();
        }
    }

    private static void turnBack(ServerLevel overworld, GarrisonLedger ledger, Siege s, Relief rl, VillageRecord h, @Nullable VillageRecord t,
                                 long tick, String why) {
        for (UUID id : rl.units) {
            RosterEntry e = h.hywRoster.entry(id);
            if (e != null && !e.state().terminal()) {
                GarrisonService.stow(overworld, e);
            }
        }
        long travel = rule(h).minTicks();
        rl.phase = Relief.Phase.RETURN;
        rl.phaseEnd = tick + travel;
        ledger.setDirty();
        HmLog.info("Siege {}: {}'s relief goes home ({})", s.id.toString().substring(0, 8), h.name, why);
    }

    /** Stowed relief soldiers appear at home (spaced ranks) and take the return path. True when all of them are home. */
    static boolean bringHome(ServerLevel overworld, VillageRecord h, List<UUID> ids, long tick) {
        BlockPos anchor = GarrisonService.anchorOf(h);
        if (!overworld.isPositionEntityTicking(anchor)) {
            return false; // home not loaded: they arrive when it is
        }
        int slot = 0;
        for (UUID id : ids) {
            RosterEntry e = h.hywRoster.entry(id);
            if (e == null || e.state().terminal()) {
                continue;
            }
            if (e.entityUuid == null) {
                Vec3 spot = GarrisonService.spotNear(overworld, SiegeService.formation(anchor, h.center, slot++, 8, 3), e.rosterId);
                if (spot == null || !GarrisonService.materialize(overworld, h, e, spot, BlockPos.containing(spot), tick)) {
                    return false;
                }
            }
            if (e.state() == UnitState.DEPLOYED) {
                e.transition(UnitState.RETURNING, tick);
                e.duty = Duty.RETURNING;
            } else if (e.duty == Duty.SIEGE) {
                e.duty = e.assignedDuty;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ strength and entities

    /** Relief soldiers standing at the target (in the world), for the battle's foes and defenders. */
    static List<LivingEntity> entities(ServerLevel overworld, GarrisonLedger ledger, Siege s) {
        List<LivingEntity> out = new ArrayList<>();
        for (Relief rl : s.reliefs) {
            VillageRecord h = ledger.get(rl.helper);
            if (rl.phase != Relief.Phase.PRESENT || h == null || h.hywRoster == null) {
                continue;
            }
            for (UUID id : rl.units) {
                RosterEntry e = h.hywRoster.entry(id);
                Entity ent = e != null && e.entityUuid != null ? GarrisonService.find(overworld.getServer(), e.entityUuid) : null;
                if (ent instanceof LivingEntity le && le.isAlive()) {
                    out.add(le);
                }
            }
        }
        return out;
    }

    /** Living relief soldiers at the target (stowed or not): strength for off-screen battles. */
    static List<RosterEntry> present(GarrisonLedger ledger, Siege s, UUID helper) {
        List<RosterEntry> out = new ArrayList<>();
        for (Relief rl : s.reliefs) {
            VillageRecord h = ledger.get(rl.helper);
            if (rl.phase != Relief.Phase.PRESENT || h == null || h.hywRoster == null || !rl.helper.equals(helper)) {
                continue;
            }
            for (UUID id : rl.units) {
                RosterEntry e = h.hywRoster.entry(id);
                if (e != null && !e.state().terminal()) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** Status lines for {@code /hywmill war sieges}. */
    static String describe(GarrisonLedger ledger, Siege s) {
        List<String> parts = new ArrayList<>();
        for (Relief rl : s.reliefs) {
            VillageRecord h = ledger.get(rl.helper);
            parts.add((h != null ? h.name : "?") + " " + rl.phase + (rl.fate != Relief.Fate.NONE ? "/" + rl.fate : "") + " " + rl.units.size() + "/" + rl.sent
                    + (rl.killed > 0 ? " -" + rl.killed : "") + (rl.strays.isEmpty() ? "" : " strays " + rl.strays.size()));
        }
        return parts.isEmpty() ? "" : " relief " + parts;
    }

    static long seedOf(String s) {
        UUID u = UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
        return u.getMostSignificantBits() ^ u.getLeastSignificantBits();
    }
}

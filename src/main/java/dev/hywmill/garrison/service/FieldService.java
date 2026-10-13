package dev.hywmill.garrison.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.realm.Officials;
import dev.hywmill.politics.service.PoliticsService;
import dev.hywmill.politics.service.RealmService;
import dev.hywmill.politics.service.ServiceRoles;
import dev.hywmill.politics.war.FieldTactics;
import dev.hywmill.politics.war.FieldTactics.Doctrine;
import dev.hywmill.politics.war.FieldTactics.Move;
import dev.hywmill.politics.war.FieldTactics.Order;
import dev.hywmill.politics.war.FieldTactics.Role;
import dev.hywmill.politics.war.Siege;
import dev.hywmill.politics.war.SiegeMath;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * 1.8: field battles fought in the world (docs/realm-design.md §16). When a realm's army meets an enemy host on the road
 * (§13), the host halts and both armies form their lines {@link FieldTactics#GAP} blocks apart under a ceasefire (20 min):
 * they come into the world whenever the field is loaded, dress their ranks, drill and ride along the lines, while those in
 * either realm's service are ordered to their side's line. Then they fight, each side by the doctrine its marshal chose
 * against what his scouts saw of the other ({@link FieldTactics#choose}), group by group ({@link FieldTactics#order}), with
 * their war engines, until one routs. Far from any witness the battle is drawn by strength and doctrine. The host that wins
 * marches on; the one that loses goes home; the realm's army goes home either way.
 */
public final class FieldService {
    private FieldService() {}

    /** Re-dress the ranks this often while the armies wait (ticks), and the drill's cycle. */
    static final long DRILL = 300, DRILL_CYCLE = 1200;
    /** At most this many men of a side come into the world each step. */
    static final int PLACE_PER_STEP = 12;
    /** Players within this of the field are at the battle (blocks). */
    public static final int AT_BATTLE = FieldTactics.GAP / 2 + 48;

    /** Each step of a siege with a field battle: returns true while the host is held at the field. */
    static boolean step(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick) {
        if (s.interceptBy == null) {
            return false;
        }
        switch (s.fieldPhase) {
            case NONE -> {
                if (s.interceptDone || tick < s.interceptAt) {
                    return false;
                }
                if (s.outcome != Siege.Outcome.NONE || s.phase == Siege.Phase.RETURN || s.fighting()) {
                    s.interceptDone = true; // too late, or over
                    ledger.setDirty();
                    return false;
                }
                if (s.phase != Siege.Phase.MARCH) {
                    return false; // the host has not set out yet: it is met on the road
                }
                form(ow, ledger, s, a, t, tick);
                return true;
            }
            case FORMING -> {
                hold(ow, s, tick);
                VillageRecord by = ledger.get(s.interceptBy);
                if (by == null) {
                    end(ow, ledger, s, a, t, false, "the realm's army is gone", tick);
                    return false;
                }
                if (s.outcome != Siege.Outcome.NONE || s.phase == Siege.Phase.RETURN) {
                    part(ow, ledger, s, a, t, tick); // peace, or the host recalled: no battle
                    return false;
                }
                boolean loaded = loaded(ow, s, a, t);
                if (loaded) {
                    place(ow, ledger, s, a, t, tick);
                    drill(ow, ledger, s, a, t, tick);
                }
                String breaker = loaded ? breach(ow, ledger, s, a) : null;
                if (breaker != null || tick >= s.fieldBattleAt) {
                    begin(ow, ledger, s, a, t, by, loaded, breaker, tick);
                }
                return true;
            }
            case FIGHT -> {
                hold(ow, s, tick);
                VillageRecord by = ledger.get(s.interceptBy);
                if (by == null) {
                    end(ow, ledger, s, a, t, false, "the realm's army is gone", tick);
                    return false;
                }
                if (s.outcome != Siege.Outcome.NONE || s.phase == Siege.Phase.RETURN) {
                    part(ow, ledger, s, a, t, tick);
                    return false;
                }
                if (!loaded(ow, s, a, t)) {
                    paper(ow, ledger, s, a, t, by, tick, true); // the witnesses left: the rest is decided far from them
                    return false;
                }
                place(ow, ledger, s, a, t, tick);
                fight(ow, ledger, s, a, t, by, tick);
                return s.fieldPhase == Siege.Field.FIGHT;
            }
            case DONE -> {
                if (!s.fieldArmy.isEmpty() && tick >= s.fieldHomeAt) {
                    s.fieldArmy.clear(); // the realm's army is home: its men take their places again (returnOrphans)
                    ledger.setDirty();
                }
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the armies meet

    /** The host halts: its arrival at the walls moves on with the clock while it stands at the field. */
    static void hold(ServerLevel ow, Siege s, long tick) {
        s.arriveAt = s.elapsed(tick, ow.getDayTime()) + Math.max(1200, s.fieldLeft);
    }

    /** The realm's army is raised from its members, the host halts, both sides choose how they will fight, and the orders go out. */
    static void form(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick) {
        VillageRecord by = ledger.get(s.interceptBy);
        if (by == null) {
            s.interceptDone = true;
            return;
        }
        // the realm's army: each member sends its share of the men the council counted on, and a war engine with its crew
        List<VillageRecord> from = new ArrayList<>();
        int total = 0;
        for (UUID m : s.interceptFrom) {
            VillageRecord v = ledger.get(m);
            if (v != null && v.hywRoster != null && !SiegeService.besieged(ledger, m)) {
                from.add(v);
                total += ReliefService.available(v).size();
            }
        }
        double share = total <= 0 ? 0 : Math.min(1, s.interceptMen / (double) total);
        s.fieldArmy.clear();
        int men = 0;
        for (VillageRecord v : from) {
            List<RosterEntry> pool = new ArrayList<>(ReliefService.available(v));
            pool.sort(Comparator.comparing(e -> e.rosterId));
            int k = Math.max(pool.isEmpty() ? 0 : 1, (int) Math.round(pool.size() * share));
            List<UUID> ids = new ArrayList<>();
            for (RosterEntry e : pool.subList(0, Math.min(k, pool.size()))) {
                send(ow, e, tick);
                ids.add(e.rosterId);
                men++;
            }
            RosterEntry engine = null, crew = null;
            for (RosterEntry e : v.hywRoster.arsenal()) {
                if (e.state() != UnitState.GARRISONED || e.duty == Duty.SIEGE) {
                    continue;
                }
                if (dev.hywmill.politics.war.ArsenalPlan.isEngine(e.unitKey)) {
                    if (engine == null && !"trebuchets".equals(e.unitKey)) {
                        engine = e;
                    }
                } else if (crew == null) {
                    crew = e;
                }
            }
            if (engine != null && crew != null) {
                send(ow, engine, tick);
                send(ow, crew, tick);
                ids.add(engine.rosterId);
                ids.add(crew.rosterId);
            }
            if (!ids.isEmpty()) {
                s.fieldArmy.put(v.villageId, ids);
            }
        }
        if (men == 0) {
            s.interceptDone = true;
            s.fieldPhase = Siege.Field.DONE;
            SiegeService.announce(ow, ledger, s, a, t, "The army of the realm of " + by.name + " could not be raised in time: the host of " + a.name + " marches on");
            ledger.setDirty();
            return;
        }
        long el = s.elapsed(tick, ow.getDayTime());
        s.fieldLeft = Math.max(1200, s.arriveAt - el);
        s.fieldSince = tick;
        s.fieldBattleAt = tick + ceasefire();
        s.fieldHostStart = SiegeService.soldiers(a, SiegeService.entries(a, s.host)).size();
        s.fieldRealmStart = men;
        s.fieldPhase = Siege.Field.FORMING;
        // each marshal chooses how to fight: knowing his own men, and what his scouts saw of the other side's
        FieldTactics.Mix host = mix(side(ow, ledger, s, a, t, true)), realm = mix(side(ow, ledger, s, a, t, false));
        double hostPower = SiegeService.strength(SiegeService.soldiers(a, SiegeService.entries(a, s.host)));
        double realmPower = SiegeService.strength(realmSoldiers(ledger, s));
        VillageRecord aHead = ledger.get(RealmService.sovereignOf(ledger, a.villageId, tick));
        int hostBold = aHead == null ? 0 : Officials.boldness(ServiceRoles.officials(ledger, aHead));
        int realmBold = Officials.boldness(ServiceRoles.officials(ledger, by));
        s.hostDoctrine = FieldTactics.choose(host, realm, hostPower / Math.max(1, realmPower), hostBold, s.seed(0x484453L)).name();
        s.realmDoctrine = FieldTactics.choose(realm, host, realmPower / Math.max(1, hostPower), realmBold, s.seed(0x524c4dL)).name();
        hold(ow, s, tick);
        ledger.setDirty();
        BlockPos[] lines = lines(s, a, t);
        String text = "The army of the realm of " + by.name + " (" + men + " men" + engines(realm) + ") bars the road to " + t.name + " near ("
                + s.interceptX + ", " + s.interceptZ + "): the host of " + a.name + " (" + s.fieldHostStart + " men" + engines(host) + ") halts "
                + FieldTactics.GAP + " blocks from it. Both sides form their lines under a ceasefire; battle in " + minutes(s.fieldBattleAt - tick);
        SiegeService.announce(ow, ledger, s, a, t, text);
        tell(ow, s, text);
        SiegeService.chronicle(ow, a, by, tick, text);
        note(ledger, by, tick, "our army faces the host of " + a.name + " near (" + s.interceptX + ", " + s.interceptZ + "); we will fight by "
                + doctrine(s.realmDoctrine).label);
        SiegeService.horn(ow, BlockPos.containing(s.interceptX, ow.getSeaLevel(), s.interceptZ));
        HmLog.info("Siege {}: field battle forms near ({}, {}): realm {} men ({}), host {} men ({}); battle at {}", s.id.toString().substring(0, 8),
                s.interceptX, s.interceptZ, men, s.realmDoctrine, s.fieldHostStart, s.hostDoctrine, s.fieldBattleAt);
        ServiceRoles.onFieldFormed(ow, ledger, s, a, t, lines[0], lines[1], doctrine(s.hostDoctrine), doctrine(s.realmDoctrine), tick);
    }

    /** The ceasefire's length (ticks): the config's, at least 30 s. */
    static long ceasefire() {
        return Math.max(600, HywMillConfig.FIELD_CEASEFIRE.get());
    }

    private static void send(ServerLevel ow, RosterEntry e, long tick) {
        e.transition(UnitState.DEPLOYED, tick);
        e.duty = Duty.SIEGE;
        GarrisonService.stow(ow, e);
    }

    static String engines(FieldTactics.Mix m) {
        return m.engines() <= 0 ? "" : " and " + m.engines() + " war engine" + (m.engines() == 1 ? "" : "s");
    }

    static String minutes(long ticks) {
        long sec = Math.max(0, ticks) / 20;
        return sec >= 120 ? Math.round(sec / 60.0) + " min" : sec + " s";
    }

    public static String doctrineLabel(String name) {
        return doctrine(name).label;
    }

    static Doctrine doctrine(String name) {
        try {
            return Doctrine.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Doctrine.SHIELD_WALL;
        }
    }

    static void note(GarrisonLedger ledger, VillageRecord by, long tick, String what) {
        var plan = ledger.plans().get(by.villageId);
        if (plan != null) {
            plan.note("Day " + (tick / PoliticsTables.DAY + 1) + ": " + what);
            ledger.setDirty();
        }
    }

    /** Tells the players at the field (the news range of the villages may not reach it). */
    static void tell(ServerLevel ow, Siege s, String text) {
        for (net.minecraft.server.level.ServerPlayer p : ow.players()) {
            if (Math.hypot(p.getX() - s.interceptX, p.getZ() - s.interceptZ) <= AT_BATTLE + 96) {
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Battle] " + text));
            }
        }
    }

    // ------------------------------------------------------------------ the field

    /** The field is loaded: the meeting point and both lines. */
    static boolean loaded(ServerLevel ow, Siege s, VillageRecord a, VillageRecord t) {
        BlockPos[] l = lines(s, a, t);
        return !s.forceUnwatched && ow.isPositionEntityTicking(BlockPos.containing(s.interceptX, 64, s.interceptZ))
                && ow.isPositionEntityTicking(l[0]) && ow.isPositionEntityTicking(l[1]);
    }

    /** The road's direction (from the attacker towards the target), as a unit vector in x and z. */
    static double[] axis(VillageRecord a, VillageRecord t) {
        double dx = t.center.getX() - a.center.getX(), dz = t.center.getZ() - a.center.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        return len < 1 ? new double[]{1, 0} : new double[]{dx / len, dz / len};
    }

    /** The centres of the host's line and the realm army's line, {@link FieldTactics#GAP} apart across the meeting point. */
    public static BlockPos[] lines(Siege s, VillageRecord a, VillageRecord t) {
        double[] u = axis(a, t);
        int h = FieldTactics.GAP / 2;
        return new BlockPos[]{BlockPos.containing(s.interceptX - u[0] * h, 64, s.interceptZ - u[1] * h),
                BlockPos.containing(s.interceptX + u[0] * h, 64, s.interceptZ + u[1] * h)};
    }

    /** One side at the field: its men, their roles and places in the line, where its line stands and which way it faces. */
    static final class Side {
        final boolean host;
        final Vec3 base, fwd, lat;
        final Doctrine doctrine;
        final List<Man> men = new ArrayList<>();
        final Map<Role, List<Man>> byRole = new EnumMap<>(Role.class);

        Side(boolean host, Vec3 base, Vec3 fwd, Doctrine doctrine) {
            this.host = host;
            this.base = base;
            this.fwd = fwd;
            this.lat = new Vec3(-fwd.z, 0, fwd.x);
            this.doctrine = doctrine;
        }

        Vec3 at(double lateral, double depth) {
            return base.add(lat.scale(lateral)).add(fwd.scale(depth));
        }

        /** The standing men in the world, as entities. */
        List<LivingEntity> bodies() {
            List<LivingEntity> out = new ArrayList<>();
            men.forEach(m -> {
                if (m.body != null) {
                    out.add(m.body);
                }
            });
            return out;
        }

        Vec3 centroid(Role role, Vec3 otherwise) {
            List<Man> g = byRole.getOrDefault(role, List.of());
            return centroid(g, otherwise);
        }

        Vec3 centroid(List<Man> g, Vec3 otherwise) {
            double x = 0, z = 0;
            int n = 0;
            for (Man m : g) {
                if (m.body != null) {
                    x += m.body.getX();
                    z += m.body.getZ();
                    n++;
                }
            }
            return n == 0 ? otherwise : new Vec3(x / n, base.y, z / n);
        }

        /** The centroid of its fighting men in the world (not the engines). */
        Vec3 front() {
            List<Man> g = new ArrayList<>();
            men.forEach(m -> {
                if (m.role != Role.ENGINE) {
                    g.add(m);
                }
            });
            return centroid(g, base);
        }
    }

    /** One man (or engine) at the field: its roster slot, village, role, place in the line, and body when in the world. */
    static final class Man {
        final VillageRecord village;
        final RosterEntry entry;
        final Role role;
        double lateral, depth;
        LivingEntity body;

        Man(VillageRecord village, RosterEntry entry, Role role) {
            this.village = village;
            this.entry = entry;
            this.role = role;
        }
    }

    static List<RosterEntry> hostEntries(VillageRecord a, Siege s) {
        return SiegeService.entries(a, s.host);
    }

    static List<RosterEntry> realmEntries(GarrisonLedger ledger, Siege s) {
        List<RosterEntry> out = new ArrayList<>();
        s.fieldArmy.forEach((v, ids) -> {
            VillageRecord rec = ledger.get(v);
            if (rec != null && rec.hywRoster != null) {
                out.addAll(SiegeService.entries(rec, ids));
            }
        });
        return out;
    }

    static List<RosterEntry> realmSoldiers(GarrisonLedger ledger, Siege s) {
        List<RosterEntry> out = new ArrayList<>();
        s.fieldArmy.forEach((v, ids) -> {
            VillageRecord rec = ledger.get(v);
            if (rec != null && rec.hywRoster != null) {
                for (RosterEntry e : SiegeService.entries(rec, ids)) {
                    if (!rec.hywRoster.isArsenal(e)) {
                        out.add(e);
                    }
                }
            }
        });
        return out;
    }

    /** An army's make-up from its side at the field (an engine's crew counts with its engine). */
    static FieldTactics.Mix mix(Side side) {
        int[] n = new int[Role.values().length];
        for (Man m : side.men) {
            if (m.role == Role.ENGINE && !dev.hywmill.politics.war.ArsenalPlan.isEngine(m.entry.unitKey)) {
                continue;
            }
            n[m.role.ordinal()]++;
        }
        return new FieldTactics.Mix(n[Role.SHIELD.ordinal()], n[Role.MELEE.ordinal()], n[Role.RANGED.ordinal()], n[Role.CAVALRY.ordinal()],
                n[Role.ENGINE.ordinal()]);
    }

    /**
     * One side at the field: its men still fit to fight (not wounded), each with its role and its place in the line by its
     * doctrine (the front rank the shields, or the warriors in the hammer; the archers behind, riders on the wings, engines at
     * the back), and its body when it stands in the world.
     */
    static Side side(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, boolean host) {
        BlockPos[] l = lines(s, a, t);
        double[] u = axis(a, t);
        BlockPos b = host ? l[0] : l[1];
        Vec3 fwd = host ? new Vec3(u[0], 0, u[1]) : new Vec3(-u[0], 0, -u[1]);
        Doctrine d = doctrine(host ? s.hostDoctrine : s.realmDoctrine);
        Side side = new Side(host, new Vec3(b.getX() + 0.5, b.getY(), b.getZ() + 0.5), fwd, d);
        List<Man> all = new ArrayList<>();
        if (host) {
            for (RosterEntry e : hostEntries(a, s)) {
                all.add(new Man(a, e, FieldTactics.role(e.entityType, a.hywRoster.isArsenal(e))));
            }
        } else {
            s.fieldArmy.forEach((v, ids) -> {
                VillageRecord rec = ledger.get(v);
                if (rec != null && rec.hywRoster != null) {
                    for (RosterEntry e : SiegeService.entries(rec, ids)) {
                        all.add(new Man(rec, e, FieldTactics.role(e.entityType, rec.hywRoster.isArsenal(e))));
                    }
                }
            });
        }
        all.sort(Comparator.comparing(m -> m.entry.rosterId));
        UnitProvider units = Services.units();
        for (Man m : all) {
            if (m.entry.state().terminal() || m.entry.wounded) {
                continue;
            }
            side.men.add(m);
            side.byRole.computeIfAbsent(m.role, k -> new ArrayList<>()).add(m);
            if (m.entry.entityUuid != null && GarrisonService.find(ow.getServer(), m.entry.entityUuid) instanceof LivingEntity le && le.isAlive()
                    && (units == null || units.isUnit(le))) {
                m.body = le;
            }
        }
        // places in the line
        boolean hammer = d == Doctrine.HAMMER;
        double front = 0, second = -4;
        place(side.byRole.get(Role.SHIELD), hammer ? second : front, 2, 16);
        place(side.byRole.get(Role.MELEE), hammer ? front : second, 2, 16);
        place(side.byRole.get(Role.RANGED), -10, 2, 16);
        place(side.byRole.get(Role.ENGINE), -24, 6, 8);
        List<Man> riders = side.byRole.getOrDefault(Role.CAVALRY, List.of());
        double half = Math.min(16, Math.max(side.byRole.getOrDefault(Role.SHIELD, List.of()).size(), side.byRole.getOrDefault(Role.MELEE, List.of()).size())) + 6;
        for (int i = 0; i < riders.size(); i++) {
            riders.get(i).lateral = (i % 2 == 0 ? 1 : -1) * (half + 3 * (i / 2));
            riders.get(i).depth = -2;
        }
        return side;
    }

    /** Ranks of {@code perRow} across the line at {@code depth}, {@code spacing} apart, each further rank 2 blocks back. */
    private static void place(@javax.annotation.Nullable List<Man> g, double depth, double spacing, int perRow) {
        if (g == null) {
            return;
        }
        for (int i = 0; i < g.size(); i++) {
            int inRow = Math.min(perRow, g.size() - (i / perRow) * perRow);
            g.get(i).lateral = (i % perRow - (inRow - 1) / 2.0) * spacing;
            g.get(i).depth = depth - (i / perRow) * 2;
        }
    }

    /** {@code v} on the ground: its block on the surface (when loaded). */
    static BlockPos ground(ServerLevel ow, Vec3 v) {
        return SiegeService.surface(ow, BlockPos.containing(v.x, 64, v.z));
    }

    /** Men of both sides not yet in the world come to their places in the line, a few each step. */
    static void place(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick) {
        UnitProvider units = Services.units();
        if (units == null) {
            return;
        }
        for (boolean host : new boolean[]{true, false}) {
            Side side = side(ow, ledger, s, a, t, host);
            int placed = 0;
            for (Man m : side.men) {
                if (placed >= PLACE_PER_STEP) {
                    break;
                }
                if (m.body != null || m.entry.entityUuid != null) {
                    continue;
                }
                BlockPos at = ground(ow, side.at(m.lateral, m.depth));
                Vec3 spot = GarrisonService.spotNear(ow, at, m.entry.rosterId);
                if (spot != null && GarrisonService.materialize(ow, m.village, m.entry, spot, at, tick)) {
                    placed++;
                    Entity ent = GarrisonService.find(ow.getServer(), m.entry.entityUuid);
                    if (ent != null) {
                        units.disengage(ent);
                        units.setAutonomous(ent, s.fieldPhase == Siege.Field.FIGHT);
                        units.setHome(ent, at);
                    }
                }
            }
            if (placed > 0) {
                ledger.setDirty();
                HmLog.info("Siege {}: field: {} of the {} come to the line ({} in all)", s.id.toString().substring(0, 8), placed,
                        host ? "host" : "realm's army", side.men.size());
            }
        }
    }

    /**
     * While they wait: every man keeps (or goes back to) his place, his ranks re-dressed every few seconds; in turn the shields,
     * the warriors and the archers step out a few paces and back; the riders pace along the front; nobody strikes a blow.
     */
    static void drill(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick) {
        UnitProvider units = Services.units();
        if (units == null) {
            return;
        }
        long cycle = tick / DRILL_CYCLE, in = tick % DRILL_CYCLE;
        Role[] turns = {Role.SHIELD, Role.MELEE, Role.RANGED};
        for (boolean host : new boolean[]{true, false}) {
            Side side = side(ow, ledger, s, a, t, host);
            Role drilling = in < DRILL ? turns[(int) ((cycle + (host ? 0 : 1)) % turns.length)] : null;
            for (Man m : side.men) {
                if (m.body == null) {
                    continue;
                }
                LivingEntity cur = units.target(m.body);
                if (cur != null) {
                    units.disengage(m.body);
                }
                units.setAutonomous(m.body, false);
                double lateral = m.lateral, depth = m.depth;
                if (m.role == Role.CAVALRY) {
                    // the riders pace along the front of their line
                    double half = Math.abs(m.lateral) + 4;
                    double phase = 2 * Math.PI * ((in + (m.entry.rosterId.getLeastSignificantBits() & 0xFF) * 4) % DRILL_CYCLE) / DRILL_CYCLE;
                    lateral = half * Math.sin(phase);
                    depth = 6;
                } else if (m.role != Role.ENGINE) {
                    SplittableRandom r = new SplittableRandom(m.entry.rosterId.getMostSignificantBits() ^ (tick / DRILL));
                    lateral += r.nextDouble() * 3 - 1.5;
                    depth += r.nextDouble() * 3 - 1.5 + (m.role == drilling ? 6 : 0);
                }
                BlockPos home = ground(ow, side.at(lateral, depth));
                BlockPos cur2 = units.home(m.body);
                if (cur2 == null || cur2.distSqr(home) > 2) {
                    units.setHome(m.body, home);
                }
            }
        }
    }

    /** Who broke the ceasefire, if anyone did: a blow struck at the other side by a man of one side, or by a player. */
    @javax.annotation.Nullable
    static String breach(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a) {
        VillageRecord t = ledger.get(s.target);
        if (t == null) {
            return null;
        }
        Side h = side(ow, ledger, s, a, t, true), r = side(ow, ledger, s, a, t, false);
        Set<LivingEntity> hb = new HashSet<>(h.bodies()), rb = new HashSet<>(r.bodies());
        for (Side x : new Side[]{h, r}) {
            for (LivingEntity le : x.bodies()) {
                LivingEntity by = le.getLastHurtByMob();
                if (by == null || le.tickCount - le.getLastHurtByMobTimestamp() > 40) {
                    continue;
                }
                if (by instanceof Player p) {
                    return p.getGameProfile().getName();
                }
                if ((x.host && rb.contains(by)) || (!x.host && hb.contains(by))) {
                    return x.host ? "the realm's army" : "the host of " + a.name;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the battle

    /** The ceasefire is over (or broken): the battle begins in the world if the field is loaded and both stand on it, else on paper. */
    static void begin(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, VillageRecord by, boolean loaded,
                      @javax.annotation.Nullable String breaker, long tick) {
        Side h = loaded ? side(ow, ledger, s, a, t, true) : null, r = loaded ? side(ow, ledger, s, a, t, false) : null;
        if (!loaded || h.bodies().isEmpty() || r.bodies().isEmpty()) {
            paper(ow, ledger, s, a, t, by, tick, false);
            return;
        }
        s.fieldPhase = Siege.Field.FIGHT;
        s.fieldWorld = true;
        s.fieldSince = tick;
        ledger.setDirty();
        SiegeService.horn(ow, ground(ow, new Vec3(s.interceptX, 64, s.interceptZ)));
        Doctrine hd = doctrine(s.hostDoctrine), rd = doctrine(s.realmDoctrine);
        String text = (breaker != null ? capital(breaker) + " breaks the ceasefire! " : "The ceasefire is over! ") + "Battle near (" + s.interceptX + ", "
                + s.interceptZ + "): the host of " + a.name + " comes on by " + hd.label + " (" + hd.plan + "); the army of the realm of " + by.name
                + " answers by " + rd.label + " (" + rd.plan + ")";
        SiegeService.announce(ow, ledger, s, a, t, text);
        tell(ow, s, text);
        HmLog.info("Siege {}: field battle begins in the world: host {} ({}), realm {} ({})", s.id.toString().substring(0, 8), h.men.size(), hd, r.men.size(), rd);
    }

    static String capital(String x) {
        return x.isEmpty() ? x : Character.toUpperCase(x.charAt(0)) + x.substring(1);
    }

    /**
     * One step of the battle in the world: each side rallies or routs, and its marshal gives each group its order. 1.9 morale
     * ({@link dev.hywmill.politics.war.Morale}): a side routs when it has lost its resolve (a tenth for levies to three tenths for
     * veterans) once the clash has lasted its time (longer for big armies), and the pursuit cuts down more of it as it flees.
     */
    static void fight(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, VillageRecord by, long tick) {
        Side h = side(ow, ledger, s, a, t, true), r = side(ow, ledger, s, a, t, false);
        int hUp = standing(h), rUp = standing(r);
        int hFall = Math.max(0, s.fieldHostStart - hUp), rFall = Math.max(0, s.fieldRealmStart - rUp);
        double[] res = resolves(ledger, s, a);
        double hs = dev.hywmill.politics.war.Morale.spirit(hFall, s.fieldHostStart, res[0]), rs = dev.hywmill.politics.war.Morale.spirit(rFall, s.fieldRealmStart, res[1]);
        if (FieldTactics.rallies(h.doctrine, hs, s.hostRallied)) {
            s.hostRallied = true;
            s.hostDoctrine = Doctrine.SHIELD_WALL.name();
            rally(ow, ledger, s, a, t, "the host of " + a.name, hUp, s.fieldHostStart);
            h = side(ow, ledger, s, a, t, true);
        }
        if (FieldTactics.rallies(r.doctrine, rs, s.realmRallied)) {
            s.realmRallied = true;
            s.realmDoctrine = Doctrine.SHIELD_WALL.name();
            rally(ow, ledger, s, a, t, "the army of the realm of " + by.name, rUp, s.fieldRealmStart);
            r = side(ow, ledger, s, a, t, false);
        }
        long el = tick - s.fieldSince;
        boolean ripe = el >= dev.hywmill.politics.war.Morale.clash(s.fieldHostStart + s.fieldRealmStart);
        boolean hRout = dev.hywmill.politics.war.Morale.broken(hFall, s.fieldHostStart, res[0], ripe);
        boolean rRout = dev.hywmill.politics.war.Morale.broken(rFall, s.fieldRealmStart, res[1], ripe);
        if (hRout || rRout || el >= FieldTactics.FIGHT_MAX) {
            boolean realmWon = hRout && rRout ? rs >= hs : hRout || (!rRout && rs >= hs);
            String how;
            if (hRout || rRout) {
                int fallen = realmWon ? hFall : rFall, start = realmWon ? s.fieldHostStart : s.fieldRealmStart;
                int run = pursue(ow, ledger, s, a, realmWon, realmWon ? r : h, tick);
                how = (realmWon ? "the host broke and fled" : "the realm's army broke and fled") + " when " + fallen + " of its " + start
                        + " had fallen" + (run > 0 ? "; the pursuit cut down " + run + " more" : "");
            } else {
                how = "neither broke by nightfall of the fight; " + (realmWon ? "the realm's army" : "the host") + " holds the field (" + rUp + " of "
                        + s.fieldRealmStart + " against " + hUp + " of " + s.fieldHostStart + ")";
            }
            end(ow, ledger, s, a, t, realmWon, how, tick);
            return;
        }
        command(ow, s, h, r, hs, rs, el, tick);
        command(ow, s, r, h, rs, hs, el, tick);
    }

    /** 1.9: each side's resolve in the open (by the grades of its men and the balance of strength): {host, realm's army}. */
    static double[] resolves(GarrisonLedger ledger, Siege s, VillageRecord a) {
        List<RosterEntry> host = SiegeService.soldiers(a, hostEntries(a, s)), realm = realmSoldiers(ledger, s);
        double hp = SiegeService.strength(host), rp = SiegeService.strength(realm);
        double h = dev.hywmill.politics.war.Morale.resolve(SiegeService.ranks(host),
                new dev.hywmill.politics.war.Morale.Circumstances(false, 0, rp > 0 ? hp / rp : 2, false, false));
        double r = dev.hywmill.politics.war.Morale.resolve(SiegeService.ranks(realm),
                new dev.hywmill.politics.war.Morale.Circumstances(false, 0, hp > 0 ? rp / hp : 2, false, false));
        return new double[]{h, r};
    }

    /**
     * 1.9: the beaten side flees and is run down: of its men still standing, {@link dev.hywmill.politics.war.Morale#pursuit}
     * fall (more when the victors have horse), each dead or wounded at the field's chance. {@code realmWon}: the host is the
     * beaten side; {@code victors}: the winning side (its horse). Returns how many fell to the pursuit.
     */
    static int pursue(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, boolean realmWon, Side victors, long tick) {
        FieldTactics.Mix mix = mix(victors);
        double cav = mix.men() <= 0 ? 0 : mix.cavalry() / (double) mix.men();
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(s.seed(0x50555253L ^ tick));
        double share = dev.hywmill.politics.war.Morale.pursuit(cav, rnd.nextDouble());
        List<Object[]> fleeing = new ArrayList<>();
        if (realmWon) {
            for (RosterEntry e : SiegeService.soldiers(a, hostEntries(a, s))) {
                if (!e.wounded) {
                    fleeing.add(new Object[]{a, e});
                }
            }
        } else {
            s.fieldArmy.forEach((v, ids) -> {
                VillageRecord rec = ledger.get(v);
                if (rec != null && rec.hywRoster != null) {
                    for (RosterEntry e : SiegeService.entries(rec, ids)) {
                        if (!rec.hywRoster.isArsenal(e) && !e.wounded) {
                            fleeing.add(new Object[]{rec, e});
                        }
                    }
                }
            });
        }
        fleeing.sort(Comparator.comparing(x -> ((RosterEntry) x[1]).rosterId));
        int n = (int) Math.round(fleeing.size() * share);
        for (int i = 0; i < n && i < fleeing.size(); i++) {
            VillageRecord v = (VillageRecord) fleeing.get(i)[0];
            RosterEntry e = (RosterEntry) fleeing.get(i)[1];
            if (rnd.nextDouble() < dev.hywmill.politics.war.SiegeWaves.ATTACKER_WOUND) {
                e.wounded = true;
                GarrisonService.stow(ow, e);
            } else {
                SiegeService.kill(ow, v, e.rosterId, tick);
            }
        }
        ledger.setDirty();
        return Math.min(n, fleeing.size());
    }

    static void rally(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, String who, int up, int start) {
        String text = capital(who) + " wavers (" + up + " of " + start + " standing): its marshal pulls it back into a shield wall";
        SiegeService.announce(ow, ledger, s, a, t, text);
        tell(ow, s, text);
        HmLog.info("Siege {}: field: {}", s.id.toString().substring(0, 8), text);
        ledger.setDirty();
    }

    /** The men of a side still fit to fight (not its engines). */
    static int standing(Side side) {
        int n = 0;
        for (Man m : side.men) {
            n += m.role == Role.ENGINE ? 0 : 1;
        }
        return n;
    }

    static double share(int n, int start) {
        return start <= 0 ? 0 : n / (double) start;
    }

    /** The marshal of {@code us} gives each of his men the order of his group, from where the battle stands. */
    static void command(ServerLevel ow, Siege s, Side us, Side them, double ours, double theirs, long el, long tick) {
        UnitProvider units = Services.units();
        if (units == null) {
            return;
        }
        List<LivingEntity> foes = them.bodies();
        if (foes.isEmpty()) {
            return;
        }
        double contact = Double.MAX_VALUE, toRanged = Double.MAX_VALUE, toShield = Double.MAX_VALUE;
        for (Man m : us.men) {
            if (m.body == null || m.role == Role.ENGINE) {
                continue;
            }
            double d = nearest(m.body, foes, null);
            contact = Math.min(contact, d);
            if (m.role == Role.RANGED) {
                toRanged = Math.min(toRanged, d);
            } else if (m.role == Role.SHIELD) {
                toShield = Math.min(toShield, d);
            }
        }
        FieldTactics.Situation sit = new FieldTactics.Situation(el, contact <= 10, ours, theirs, toRanged, toShield);
        Vec3 enemy = them.front();
        Vec3 leader = us.centroid(us.doctrine == Doctrine.HAMMER ? Role.MELEE : Role.SHIELD, us.at(0, 0));
        Vec3 shields = us.centroid(Role.SHIELD, us.at(0, 0)), archers = us.centroid(Role.RANGED, us.at(0, -10));
        double enemyDepth = enemy.subtract(us.base).dot(us.fwd);
        for (Man m : us.men) {
            if (m.body == null) {
                continue;
            }
            Order o = FieldTactics.order(us.doctrine, m.role, sit);
            LivingEntity cur = units.target(m.body);
            boolean busy = cur != null && cur.isAlive();
            if (o.move() == Move.BOMBARD) {
                if (dev.hywmill.politics.war.ArsenalPlan.isEngine(m.entry.unitKey) && el % SiegeService.ADVANCE_EVERY < SiegeService.INTERVAL && !busy) {
                    double reach = dev.hywmill.politics.war.ArsenalPlan.reach(m.entry.unitKey) * SiegeService.ENGINE_REACH_USE;
                    if (nearest(m.body, foes, null) > reach) {
                        Vec3 to = enemy.subtract(m.body.position());
                        double d = Math.sqrt(to.x * to.x + to.z * to.z);
                        if (d > 1) {
                            units.setHome(m.body, ground(ow, m.body.position().add(to.x / d * SiegeService.ADVANCE_STEP, 0, to.z / d * SiegeService.ADVANCE_STEP)));
                        }
                    }
                }
                continue;
            }
            if (o.move() == Move.FALL_BACK) {
                if (busy) {
                    units.disengage(m.body);
                }
                units.setAutonomous(m.body, false);
                Vec3 back = (us.byRole.containsKey(Role.SHIELD) ? shields : us.base).subtract(us.fwd.scale(o.depth())).add(us.lat.scale(m.lateral * 0.5));
                home(units, m.body, ground(ow, back));
                continue;
            }
            if (busy) {
                continue; // in the thick of it: he fights on
            }
            LivingEntity[] best = new LivingEntity[1];
            double d = nearest(m.body, foes, best);
            if (o.move() == Move.CHARGE) {
                units.setAutonomous(m.body, true);
                if (best[0] != null && d <= o.engage()) {
                    units.engage(m.body, best[0]);
                } else {
                    home(units, m.body, ground(ow, enemy));
                }
                continue;
            }
            Vec3 goal = switch (o.move()) {
                case HOLD -> us.at(m.lateral, m.depth + o.depth());
                case ADVANCE -> us.at(m.lateral, o.depth() >= 0 ? m.depth + o.depth() : Math.max(m.depth, Math.min(FieldTactics.GAP, enemyDepth + o.depth())));
                case FOLLOW -> leader.subtract(us.fwd.scale(o.depth())).add(us.lat.scale(m.lateral * 0.6));
                case SCREEN -> archers.add(us.fwd.scale(o.depth())).add(us.lat.scale(m.lateral));
                case WHEEL -> enemy.add(us.lat.scale(((m.entry.rosterId.hashCode() & 1) == 0 ? 1 : -1) * o.depth())).add(us.fwd.scale(o.depth() / 3));
                default -> us.at(m.lateral, m.depth);
            };
            boolean arrived = o.move() == Move.WHEEL && Math.hypot(m.body.getX() - goal.x, m.body.getZ() - goal.z) < 10;
            if (best[0] != null && (d <= o.engage() || arrived)) {
                units.setAutonomous(m.body, true);
                units.engage(m.body, best[0]);
            } else {
                units.setAutonomous(m.body, false);
                home(units, m.body, ground(ow, goal));
            }
        }
    }

    static void home(UnitProvider units, LivingEntity body, BlockPos to) {
        BlockPos cur = units.home(body);
        if (cur == null || cur.distSqr(to) > 4) {
            units.setHome(body, to);
        }
    }

    /** Distance (in x and z) from {@code from} to the nearest of {@code foes}; the nearest into {@code out[0]}. */
    static double nearest(Entity from, List<LivingEntity> foes, @javax.annotation.Nullable LivingEntity[] out) {
        double best = Double.MAX_VALUE;
        for (LivingEntity f : foes) {
            double d = Math.hypot(f.getX() - from.getX(), f.getZ() - from.getZ());
            if (d < best) {
                best = d;
                if (out != null) {
                    out[0] = f;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the end

    /** The armies part without a battle (peace was made, or the host was called home): each goes its way. */
    static void part(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick) {
        s.fieldPhase = Siege.Field.DONE;
        s.interceptDone = true;
        hostEntries(a, s).forEach(e -> GarrisonService.stow(ow, e));
        long home = 0;
        for (Map.Entry<UUID, List<UUID>> en : s.fieldArmy.entrySet()) {
            VillageRecord rec = ledger.get(en.getKey());
            if (rec != null && rec.hywRoster != null) {
                SiegeService.entries(rec, en.getValue()).forEach(e -> GarrisonService.stow(ow, e));
                home = Math.max(home, SiegeMath.marchTicks(Math.hypot(rec.center.getX() - s.interceptX, rec.center.getZ() - s.interceptZ), SiegeService.rule(rec)));
            }
        }
        s.fieldHomeAt = tick + Math.max(600, home);
        ledger.setDirty();
        String text = "The armies near (" + s.interceptX + ", " + s.interceptZ + ") part without a battle: each marches home";
        SiegeService.announce(ow, ledger, s, a, t, text);
        tell(ow, s, text);
        HmLog.info("Siege {}: {}", s.id.toString().substring(0, 8), text);
    }

    /**
     * Far from any witness (or when they leave): the battle drawn by strength and doctrine: the loser loses its share of its
     * men; the winner a share of the smaller army's. Each sworn sword of the realm at the field adds his strength.
     */
    static void paper(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, VillageRecord by, long tick, boolean midway) {
        PoliticsTables.SiegeRule r = SiegeService.rule(a);
        List<RosterEntry> hs = new ArrayList<>();
        for (RosterEntry e : SiegeService.soldiers(a, hostEntries(a, s))) {
            if (!e.wounded) {
                hs.add(e);
            }
        }
        List<RosterEntry> rs = new ArrayList<>();
        Map<RosterEntry, VillageRecord> of = new HashMap<>();
        s.fieldArmy.forEach((v, ids) -> {
            VillageRecord rec = ledger.get(v);
            if (rec != null && rec.hywRoster != null) {
                for (RosterEntry e : SiegeService.entries(rec, ids)) {
                    if (!rec.hywRoster.isArsenal(e) && !e.wounded) {
                        rs.add(e);
                        of.put(e, rec);
                    }
                }
            }
        });
        int present = 0;
        for (net.minecraft.server.level.ServerPlayer p : ow.players()) {
            var sv = ledger.services().get(p.getUUID());
            if (sv != null && sv.realm.equals(by.villageId) && Math.hypot(p.getX() - s.interceptX, p.getZ() - s.interceptZ) <= AT_BATTLE) {
                present++;
            }
        }
        double host = SiegeService.strength(hs), ours = SiegeService.strength(rs) + present * dev.hywmill.politics.war.FieldBattle.PLAYER;
        double p = FieldTactics.winChance(ours, host, doctrine(s.realmDoctrine), doctrine(s.hostDoctrine));
        // 1.9 morale: the beaten side loses its resolve's worth and then the pursuit; the victor about half what it inflicted
        double[] res = resolves(ledger, s, a);
        dev.hywmill.politics.war.Morale.Field f = dev.hywmill.politics.war.Morale.paperField(rs.size(), hs.size(), p, res[1], res[0],
                cavalry(rs), cavalry(hs), s.seed(0x4649454cL ^ tick));
        boolean realmWon = f.oursWon();
        hs.sort(Comparator.comparing(e -> e.rosterId));
        rs.sort(Comparator.comparing(e -> e.rosterId));
        SplittableRandom wr = new SplittableRandom(s.seed(0x46574FL ^ tick));
        int hostDead = 0, ourDead = 0;
        for (int i = 0; i < f.theirFallen() && i < hs.size(); i++) {
            hostDead += fallInField(ow, a, hs.get(i), wr, tick) ? 1 : 0;
        }
        for (int i = 0; i < f.ourFallen() && i < rs.size(); i++) {
            ourDead += fallInField(ow, of.get(rs.get(i)), rs.get(i), wr, tick) ? 1 : 0;
        }
        String how = (midway ? "the rest of the battle was fought far from any witness: " : "")
                + (realmWon ? "the host broke and fled (" + f.theirFallen() + " of " + hs.size() + " fell, " + hostDead + " of them dead)"
                : "the realm's army broke and fled (" + f.ourFallen() + " of " + rs.size() + " fell, " + ourDead + " of them dead)")
                + (present > 0 ? "; " + present + " of the realm's sworn swords stood with its army" : "");
        end(ow, ledger, s, a, t, realmWon, how, tick);
    }

    /** The share of horsemen among a side's men. */
    static double cavalry(List<RosterEntry> men) {
        if (men.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (RosterEntry e : men) {
            n += FieldTactics.role(e.entityType, false) == Role.CAVALRY ? 1 : 0;
        }
        return n / (double) men.size();
    }

    /** One who fell in a battle drawn on paper: wounded at the field's chance (carried off), else dead. True if dead. */
    static boolean fallInField(ServerLevel ow, VillageRecord v, RosterEntry e, SplittableRandom r, long tick) {
        if (r.nextDouble() < dev.hywmill.politics.war.SiegeWaves.ATTACKER_WOUND) {
            e.wounded = true;
            GarrisonService.stow(ow, e);
            return false;
        }
        SiegeService.kill(ow, v, e.rosterId, tick);
        return true;
    }

    /**
     * The battle is over: the wounded of both sides are tended (some die of their wounds), the realm's army marches home, and
     * the host either goes home beaten or marches on.
     */
    static void end(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, boolean realmWon, String how, long tick) {
        VillageRecord by = s.interceptBy == null ? null : ledger.get(s.interceptBy);
        s.fieldPhase = Siege.Field.DONE;
        s.interceptDone = true;
        ledger.setDirty();
        // the wounded: the host's camp surgeons, field dressing for the realm's army
        double hostSuccumb = dev.hywmill.politics.war.SiegeWaves.succumbChance(dev.hywmill.politics.war.SiegeWaves.surgeons(false, false, SiegeService.town(a)));
        int[] hw = SiegeService.tend(ow, a, hostEntries(a, s), hostSuccumb, s.seed(0x464857L), tick);
        int realmDied = 0;
        long home = 0;
        for (Map.Entry<UUID, List<UUID>> en : s.fieldArmy.entrySet()) {
            VillageRecord rec = ledger.get(en.getKey());
            if (rec == null || rec.hywRoster == null) {
                continue;
            }
            List<RosterEntry> es = SiegeService.entries(rec, en.getValue());
            realmDied += SiegeService.tend(ow, rec, es, dev.hywmill.politics.war.SiegeWaves.SUCCUMB, s.seed(0x464852L ^ rec.villageId.getLeastSignificantBits()), tick)[0];
            es.forEach(e -> GarrisonService.stow(ow, e));
            home = Math.max(home, SiegeMath.marchTicks(Math.hypot(rec.center.getX() - s.interceptX, rec.center.getZ() - s.interceptZ), SiegeService.rule(rec)));
        }
        s.fieldHomeAt = tick + Math.max(600, home);
        int hostLeft = SiegeService.soldiers(a, hostEntries(a, s)).size(), realmLeft = realmSoldiers(ledger, s).size();
        String text = "Battle near (" + s.interceptX + ", " + s.interceptZ + ") on the road to " + t.name + ": the army of the realm of "
                + (by == null ? "?" : by.name) + " (" + s.fieldRealmStart + " men, " + doctrine(s.realmDoctrine).label + ") against the host of " + a.name
                + " (" + s.fieldHostStart + " men, " + doctrine(s.hostDoctrine).label + "): " + how + ". "
                + (realmWon ? "The host turns for home with " + hostLeft + " men; the realm's army marches home with " + realmLeft
                : "The host marches on to " + t.name + " with " + hostLeft + " men; the realm's army falls back home with " + realmLeft)
                + (hw[0] + realmDied > 0 ? " (" + (hw[0] + realmDied) + " died of their wounds)" : "");
        SiegeService.announce(ow, ledger, s, a, t, text);
        tell(ow, s, text);
        if (by != null) {
            SiegeService.chronicle(ow, a, by, tick, text);
            note(ledger, by, tick, realmWon ? "our army breaks the host of " + a.name + " on the road to " + t.name
                    : "our army is beaten on the road by the host of " + a.name);
        }
        s.notes.add("Field: " + text);
        HmLog.info("Siege {}: {}", s.id.toString().substring(0, 8), text);
        List<RosterEntry> host = hostEntries(a, s);
        if (realmWon) {
            s.summary = text;
            SiegeService.goHome(ow, ledger, s, a, host, tick, SiegeService.rule(a), false);
        } else {
            host.forEach(e -> GarrisonService.stow(ow, e)); // back on the road (out of the world), the march goes on
        }
    }
}

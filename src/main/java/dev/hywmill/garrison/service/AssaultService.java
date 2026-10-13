package dev.hywmill.garrison.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.garrison.tag.GarrisonAttachments;
import dev.hywmill.garrison.tag.GarrisonTag;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.war.Assault;
import dev.hywmill.politics.war.Conscription;
import dev.hywmill.politics.war.Morale;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.PoliticsNbt;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A player's own assaults ({@link Assault}; docs/siege-design.md "A player's assault"): he leads his own men (the soldiers he
 * hired) against a village he is at war with, from a side and at an hour of his choosing, with men his side lends him if he
 * asks. The village sees them gather and presses levies into service to even the odds; the fight is decided by morale:
 * thrown back, his band regroups at the rally point and goes in again; the village yields when its defenders have lost their
 * resolve (it pays him a ransom); his band gives up when it has lost its campaign's worth. Every head taken earns its price
 * ({@link WarSpoils}).
 */
public final class AssaultService {
    private AssaultService() {}

    /** The village notices a band gathering within this far of its edge, or when half the time has passed (blocks). */
    static final int NOTICE = 64;
    /** A player farther than this from the village when his men should go in, or for a minute during the fight, calls it off. */
    static final int LEAD = 160;
    /** At most this many men lent by his side. */
    static final int LEND_MAX = 8;

    /** The player's assault being prepared or fought, or null. */
    @Nullable
    public static Assault active(GarrisonLedger ledger, UUID player) {
        for (Assault a : ledger.assaults()) {
            if (a.player.equals(player) && a.active()) {
                return a;
            }
        }
        return null;
    }

    /** The village a player's active assault is against, when it is {@code village}: who pays his head prices (the plunder). */
    @Nullable
    static UUID target(GarrisonLedger ledger, UUID player, UUID village) {
        Assault a = active(ledger, player);
        return a != null && a.target.equals(village) && a.phase != Assault.Phase.GATHER ? village : null;
    }

    /** A head taken from {@code village} by {@code player} (or his men): his merit in his assault on it. */
    static void merit(GarrisonLedger ledger, UUID player, UUID village, int price) {
        Assault a = active(ledger, player);
        if (a != null && a.target.equals(village)) {
            a.merit += price;
        }
    }

    /** The side a player fights for against {@code village}: the village he campaigns with, or the realm he serves; null if none. */
    @Nullable
    static UUID sideAgainst(GarrisonLedger ledger, UUID player, UUID village, long now) {
        dev.hywmill.politics.war.Campaign c = dev.hywmill.politics.service.RelationProjector.campaignOf(ledger, player);
        if (c != null && c.active(now) && (c.enemy().equals(village) || dev.hywmill.politics.service.RealmService.sameRealm(ledger, c.enemy(), village, now))) {
            return c.ally();
        }
        dev.hywmill.politics.realm.Service sv = ledger.services().get(player);
        if (sv != null && sv.realm != null && dev.hywmill.politics.service.RelationProjector.atWar(ledger, sv.realm, village)) {
            return sv.realm;
        }
        return null;
    }

    /** A player's own men (HYW soldiers he owns) alive within {@code radius} of a point. */
    static List<LivingEntity> ownMen(ServerLevel ow, UUID player, Vec3 at, double radius) {
        UnitProvider units = Services.units();
        if (units == null) {
            return List.of();
        }
        return ow.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(radius, 32, radius),
                e -> e.isAlive() && units.isUnit(e) && player.equals(units.ownerOf(e)));
    }

    /**
     * The player leads an assault on {@code targetId}: his men gather at the rally point on {@code side} and go in after
     * {@code minutes}; with {@code help} his side lends him men. Returns what happened (a refusal says why).
     */
    public static String launch(ServerLevel ow, ServerPlayer player, UUID targetId, int side, int minutes, boolean help) {
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        VillageRecord t = ledger.get(targetId);
        long now = ow.getGameTime();
        if (t == null || t.hywRoster == null) {
            return "Unknown village.";
        }
        if (t.loneBuilding) {
            return t.name + " is a lone building: there is nothing there to take.";
        }
        if (active(ledger, player.getUUID()) != null) {
            return "You are already leading an assault: call it off first.";
        }
        UUID ally = sideAgainst(ledger, player.getUUID(), targetId, now);
        if (ally == null) {
            return "You are at no war with " + t.name + ": go on campaign against it with an ally, or serve a realm at war with it.";
        }
        dev.hywmill.politics.war.Siege besieged = SiegeService.against(ledger, targetId);
        if (besieged != null && besieged.fighting()) {
            return t.name + " is besieged already: join that fight.";
        }
        List<LivingEntity> men = ownMen(ow, player.getUUID(), player.position(), 96);
        if (men.size() < Assault.MIN_MEN) {
            return "You need at least " + Assault.MIN_MEN + " men of your own within 96 blocks (hire them at a Muster Roll); you have " + men.size() + ".";
        }
        int radius = t.villageRadius > 0 ? t.villageRadius : 64;
        double[] d = Assault.direction(side);
        int rx = t.center.getX() + (int) Math.round(d[0] * (radius + Assault.RALLY_OUT)), rz = t.center.getZ() + (int) Math.round(d[1] * (radius + Assault.RALLY_OUT));
        int ry = ow.isLoaded(new BlockPos(rx, 64, rz)) ? ow.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rx, rz) : t.center.getY();
        Assault a = new Assault(UUID.randomUUID(), player.getUUID(), targetId, side, help, now, now + Math.max(1, minutes) * 1200L);
        a.rallyX = rx;
        a.rallyY = ry;
        a.rallyZ = rz;
        ledger.assaults().add(a);
        String helped = help ? lend(ow, ledger, a, ally, t, men.size(), now) : "You go without help.";
        ledger.setDirty();
        String text = "Your assault on " + t.name + ": gather your men at the " + a.sideName() + " rally point (" + rx + ", " + rz + "); they go in in "
                + minutes + " min. " + helped + " " + t.name + " will press levies into service if it sees you gather.";
        HmLog.info("Assault {}: {} on {} from the {} ({}, {}) in {} min, {} men nearby, help {}", a.id.toString().substring(0, 8),
                player.getGameProfile().getName(), t.name, a.sideName(), rx, rz, minutes, men.size(), help);
        a.notes.add(text);
        return text;
    }

    /** The player's side lends him men (from the village he campaigns with, or the realm's member nearest the target). */
    static String lend(ServerLevel ow, GarrisonLedger ledger, Assault a, UUID ally, VillageRecord t, int own, long now) {
        VillageRecord best = null;
        List<UUID> spare = List.of();
        List<UUID> members = new ArrayList<>(dev.hywmill.politics.service.RealmService.members(ledger, ally, now));
        if (!members.contains(ally)) {
            members.add(ally);
        }
        for (UUID m : members) {
            VillageRecord v = ledger.get(m);
            if (v == null || v.hywRoster == null || SiegeService.besieged(ledger, m)) {
                continue;
            }
            List<UUID> s = ErrandService.spare(ow, v.hywRoster, ErrandService.DutyTableRaid.of(v));
            if (!s.isEmpty() && (best == null || v.center.distSqr(t.center) < best.center.distSqr(t.center))) {
                best = v;
                spare = s;
            }
        }
        if (best == null) {
            return "Your side has no men to spare.";
        }
        int n = Math.min(Math.min(LEND_MAX, spare.size()), Math.max(2, own));
        long until = a.assaultAt + Assault.FIGHT_MAX;
        BlockPos rally = new BlockPos(a.rallyX, a.rallyY, a.rallyZ);
        for (UUID id : spare.subList(0, n)) {
            RosterEntry e = best.hywRoster.entry(id);
            e.transition(UnitState.DEPLOYED, now);
            e.duty = Duty.DETACHED;
            e.dutySince = now;
            e.errandPlayer = a.player;
            e.errandUntil = until;
            e.errandPoint = rally.asLong();
            a.lent.add(id);
        }
        a.helper = best.villageId;
        return best.name + " lends you " + n + " men: they march to the rally point.";
    }

    /** Calls off the player's assault (his men stand down; men lent go home; the village's levies go home). */
    public static String cancel(ServerLevel ow, ServerPlayer player) {
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        Assault a = active(ledger, player.getUUID());
        if (a == null) {
            return "You are leading no assault.";
        }
        VillageRecord t = ledger.get(a.target);
        end(ow, ledger, a, t, Assault.Outcome.CALLED_OFF, "you called it off", ow.getGameTime());
        return "Your assault on " + (t == null ? "?" : t.name) + " is called off.";
    }

    /** One line on the player's assault (status, HUD). */
    @Nullable
    public static String status(ServerLevel ow, UUID player) {
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        Assault a = active(ledger, player);
        if (a == null) {
            return null;
        }
        VillageRecord t = ledger.get(a.target);
        String name = t == null ? "?" : t.name;
        long now = ow.getGameTime();
        return switch (a.phase) {
            case GATHER -> "Assault on " + name + ": gather at the " + a.sideName() + " rally point (" + a.rallyX + ", " + a.rallyZ + "); in "
                    + clock(a.assaultAt - now) + "; " + ownMen(ow, player, rally(a), Assault.GATHER_REACH).size() + " of your men there"
                    + (a.lent.isEmpty() ? "" : ", " + a.lent.size() + " lent");
            case FIGHT -> "Assault " + a.assault + " on " + name + ": defenders fallen " + a.defFallen + " (they yield at "
                    + Morale.breakAt(a.defStart, a.defResolve) + " of " + a.defStart + "); your men fallen " + a.assaultFallen + " this assault (you fall back at "
                    + Morale.breakAt(a.assaultEngaged, a.hostResolve) + ")";
            case REGROUP -> "Your band regroups at the " + a.sideName() + " rally point; it goes in again in " + clock(a.phaseSince + Assault.REGROUP - now);
            case DONE -> null;
        };
    }

    static String clock(long ticks) {
        long s = Math.max(0, ticks) / 20;
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    static Vec3 rally(Assault a) {
        return new Vec3(a.rallyX + 0.5, a.rallyY, a.rallyZ + 0.5);
    }

    // ------------------------------------------------------------------ tick

    /** Every second, on the sieges' cadence ({@link SiegeService#tick}). */
    public static void tick(ServerLevel ow, long now) {
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        if (ledger.assaults().isEmpty()) {
            return;
        }
        for (Assault a : new ArrayList<>(ledger.assaults())) {
            try {
                step(ow, ledger, a, now);
            } catch (RuntimeException ex) {
                HmLog.warn("Assault {} step failed: {}", a.id, ex.toString());
            }
        }
        ledger.assaults().removeIf(a -> !a.active() && now - a.phaseSince > 24000L);
    }

    static void step(ServerLevel ow, GarrisonLedger ledger, Assault a, long now) {
        if (!a.active()) {
            return;
        }
        VillageRecord t = ledger.get(a.target);
        ServerPlayer p = online(ow, a.player);
        if (t == null || t.hywRoster == null) {
            end(ow, ledger, a, t, Assault.Outcome.CALLED_OFF, "the village is gone", now);
            return;
        }
        boolean near = p != null && p.level() == ow && Math.hypot(p.getX() - t.center.getX(), p.getZ() - t.center.getZ()) <= LEAD + (t.villageRadius);
        if (near) {
            a.lastSeen = now;
        }
        switch (a.phase) {
            case GATHER -> {
                if (!a.noticed && (now - a.launched >= (a.assaultAt - a.launched) / 2 || gatheringSeen(ow, a, t))) {
                    notice(ow, ledger, a, t, now);
                }
                if (now >= a.assaultAt) {
                    if (!near) {
                        end(ow, ledger, a, t, Assault.Outcome.CALLED_OFF, "you were not there to lead your men", now);
                        return;
                    }
                    begin(ow, ledger, a, t, now);
                }
            }
            case FIGHT -> fight(ow, ledger, a, t, p, near, now);
            case REGROUP -> {
                if (now - a.lastSeen > 1200) {
                    end(ow, ledger, a, t, Assault.Outcome.CALLED_OFF, "you left your men", now);
                } else if (now - a.phaseSince >= Assault.REGROUP) {
                    goIn(ow, ledger, a, t, now, "Your band forms up again and goes in: assault " + (a.assault + 1));
                }
            }
            default -> { }
        }
    }

    /** The village's sentries see a band gathering: his men within {@link #NOTICE} of its edge. */
    static boolean gatheringSeen(ServerLevel ow, Assault a, VillageRecord t) {
        int r = (t.villageRadius > 0 ? t.villageRadius : 64) + NOTICE;
        return !ownMen(ow, a.player, new Vec3(t.center.getX(), t.center.getY(), t.center.getZ()), r).isEmpty();
    }

    /** The village sees the band and presses levies into service to even the odds (poorly armed, like a siege's). */
    static void notice(ServerLevel ow, GarrisonLedger ledger, Assault a, VillageRecord t, long now) {
        a.noticed = true;
        int band = ownMen(ow, a.player, rally(a), 96).size() + a.lent.size();
        int defenders = SiegeService.homeDefenders(t).size();
        int cap = Conscription.cap(t.population, 0);
        int want = Conscription.levies(Math.max(band, 1), defenders, cap);
        GarrisonTables tables = GarrisonTables.current();
        int level = Math.max(0, ColumnService.levyLevel(t) - 1);
        List<String> pool = dev.hywmill.politics.war.DefenderAid.draw(SiegeService.LEVY_POOL, want, a.id.getLeastSignificantBits());
        for (String key : pool) {
            UnitSpec u = tables.units().get(key);
            if (u == null || !u.enabled()) {
                continue;
            }
            RosterEntry e = t.hywRoster.recruit(t.villageId, key, u.entityType(), level, now, false);
            e.mobilized = true;
            e.extra = "levy";
            e.transition(UnitState.SPAWNED, now);
            e.transition(UnitState.GARRISONED, now);
            a.levies.add(e.rosterId);
        }
        musterLevies(ow, a, t, now);
        String text = t.name + " sees " + (band > 0 ? "a band of " + band : "a band") + " gathering to the " + a.sideName()
                + (a.levies.isEmpty() ? ": its garrison stands to" : ": it presses " + a.levies.size() + " levies into service, poorly armed");
        tell(ow, a, text);
        a.notes.add(text);
        ledger.setDirty();
        HmLog.info("Assault {}: {}", a.id.toString().substring(0, 8), text);
    }

    static void musterLevies(ServerLevel ow, Assault a, VillageRecord t, long now) {
        BlockPos anchor = GarrisonService.anchorOf(t);
        if (!ow.isPositionEntityTicking(anchor)) {
            return;
        }
        int i = 0;
        for (UUID id : a.levies) {
            RosterEntry e = t.hywRoster.entry(id);
            if (e == null || e.state().terminal() || e.entityUuid != null) {
                continue;
            }
            Vec3 spot = GarrisonService.spotNear(ow, SiegeService.formation(anchor, t.center.offset(1, 0, 0), i++, 6, 3), e.rosterId);
            if (spot != null) {
                GarrisonService.materialize(ow, t, e, spot, anchor, now);
            }
        }
    }

    /** The hour: the men at the rally point are the band; each side's resolve is reckoned; the first assault goes in. */
    static void begin(ServerLevel ow, GarrisonLedger ledger, Assault a, VillageRecord t, long now) {
        if (!a.noticed) {
            notice(ow, ledger, a, t, now);
        }
        a.host.clear();
        UnitProvider units = Services.units();
        Morale.Ranks ranks = Morale.Ranks.NONE;
        for (LivingEntity e : ownMen(ow, a.player, rally(a), Assault.GATHER_REACH)) {
            a.host.add(e.getUUID());
            ranks = ranks.plus(units != null && units.equipmentLevel(e) >= Morale.VETERAN_GEAR ? Morale.Grade.VETERAN : Morale.Grade.REGULAR, 1);
        }
        VillageRecord helper = a.helper == null ? null : ledger.get(a.helper);
        List<RosterEntry> lent = lentAlive(helper, a);
        ranks = plus(ranks, SiegeService.ranks(lent));
        if (a.host.isEmpty() && lent.isEmpty()) {
            end(ow, ledger, a, t, Assault.Outcome.CALLED_OFF, "none of your men were at the rally point", now);
            return;
        }
        List<RosterEntry> defs = SiegeService.homeDefenders(t);
        a.hostStart = a.host.size() + lent.size();
        a.defStart = Math.max(1, defs.size());
        // his hired men count as the village's average soldier, a quarter better (picked men, kept in pay)
        double perMan = defs.isEmpty() ? 12 : SiegeService.strength(defs) / defs.size();
        double hp = a.host.size() * perMan * 1.25 + SiegeService.strength(lent), dp = SiegeService.defense(ow, t, 0);
        a.hostResolve = Morale.resolve(ranks, new Morale.Circumstances(false, 0, dp > 0 ? hp / dp : 2, false, false));
        a.defResolve = Morale.resolve(SiegeService.ranks(defs), new Morale.Circumstances(true, t.fortification, hp > 0 ? dp / hp : 2, false, false));
        SiegeService.horn(ow, t.center);
        goIn(ow, ledger, a, t, now, "Your band (" + a.hostStart + ") goes in against " + t.name + " from the " + a.sideName() + "; " + a.defStart
                + " defenders hold it");
    }

    static Morale.Ranks plus(Morale.Ranks a, Morale.Ranks b) {
        return new Morale.Ranks(a.levy() + b.levy(), a.mercenary() + b.mercenary(), a.regular() + b.regular(), a.veteran() + b.veteran());
    }

    static List<RosterEntry> lentAlive(@Nullable VillageRecord helper, Assault a) {
        List<RosterEntry> out = new ArrayList<>();
        if (helper != null && helper.hywRoster != null) {
            for (UUID id : a.lent) {
                RosterEntry e = helper.hywRoster.entry(id);
                if (e != null && !e.state().terminal()) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** An assault goes in: its men are told to take the village; the lent men march on it. */
    static void goIn(ServerLevel ow, GarrisonLedger ledger, Assault a, VillageRecord t, long now, String text) {
        a.phase = Assault.Phase.FIGHT;
        a.phaseSince = now;
        a.assault++;
        a.assaultSince = now;
        a.assaultFallen = 0;
        VillageRecord helper = a.helper == null ? null : ledger.get(a.helper);
        a.assaultEngaged = Math.max(1, alive(ow, a).size() + lentAlive(helper, a).size());
        UnitProvider units = Services.units();
        for (Entity e : alive(ow, a)) {
            if (units != null) {
                units.setHome(e, t.center);
            }
        }
        for (RosterEntry e : lentAlive(helper, a)) {
            e.errandPoint = t.center.asLong();
        }
        tell(ow, a, text);
        a.notes.add(text);
        ledger.setDirty();
        HmLog.info("Assault {}: {}", a.id.toString().substring(0, 8), text);
    }

    static List<Entity> alive(ServerLevel ow, Assault a) {
        List<Entity> out = new ArrayList<>();
        for (UUID id : a.host) {
            Entity e = ow.getEntity(id);
            if (e instanceof LivingEntity le && le.isAlive()) {
                out.add(e);
            }
        }
        return out;
    }

    /** The fight: each of his men without a foe takes the nearest defender; morale decides. */
    static void fight(ServerLevel ow, GarrisonLedger ledger, Assault a, VillageRecord t, @Nullable ServerPlayer p, boolean near, long now) {
        if (now - a.lastSeen > 1200) {
            end(ow, ledger, a, t, Assault.Outcome.CALLED_OFF, "you left your men", now);
            return;
        }
        UnitProvider units = Services.units();
        List<LivingEntity> foes = SiegeService.defenders(ow, t);
        if (units != null && !foes.isEmpty()) {
            for (Entity e : alive(ow, a)) {
                if (units.target(e) == null) {
                    LivingEntity best = null;
                    double bd = Double.MAX_VALUE;
                    for (LivingEntity f : foes) {
                        double d = f.distanceToSqr(e);
                        if (d < bd) {
                            bd = d;
                            best = f;
                        }
                    }
                    if (best != null && bd < 96 * 96) {
                        units.engage(e, best);
                    }
                }
            }
        }
        SiegeService.steel(ow, t);
        boolean ripe = now - a.assaultSince >= Morale.clash(a.assaultEngaged + a.defStart);
        if (SiegeService.homeDefenders(t).stream().allMatch(e -> e.wounded)) {
            end(ow, ledger, a, t, Assault.Outcome.WON, "none of its defenders is left standing", now);
        } else if (Morale.broken(a.defFallen, a.defStart, a.defResolve, ripe)) {
            end(ow, ledger, a, t, Assault.Outcome.WON, a.defFallen + " of its " + a.defStart + " defenders fell and the rest would not stand", now);
        } else if (a.assaultEngaged > 0 && alive(ow, a).isEmpty() && lentAlive(a.helper == null ? null : ledger.get(a.helper), a).isEmpty()) {
            end(ow, ledger, a, t, Assault.Outcome.LOST, "none of your band is left standing", now);
        } else if (Morale.broken(a.assaultFallen, a.assaultEngaged, a.hostResolve, ripe)
                && Morale.broken(a.hostFallen, a.hostStart, Morale.campaign(a.hostResolve), true)) {
            // thrown back with its campaign's worth lost: the band gives up
            end(ow, ledger, a, t, Assault.Outcome.LOST, "your band lost " + a.hostFallen + " of its " + a.hostStart + " men and gave up", now);
        } else if (Morale.broken(a.assaultFallen, a.assaultEngaged, a.hostResolve, ripe)) {
            a.phase = Assault.Phase.REGROUP;
            a.phaseSince = now;
            for (Entity e : alive(ow, a)) {
                if (units != null) {
                    units.disengage(e);
                    units.setHome(e, BlockPos.containing(rally(a)));
                }
            }
            String text = "Assault " + a.assault + " on " + t.name + " is thrown back (" + a.assaultFallen + " of " + a.assaultEngaged
                    + " fell): your band falls back to the rally point to regroup";
            tell(ow, a, text);
            a.notes.add(text);
            ledger.setDirty();
        } else if (now - a.assaultSince > Assault.FIGHT_MAX) {
            end(ow, ledger, a, t, Assault.Outcome.LOST, "the defenders held until your band could fight no more", now);
        }
    }

    // ------------------------------------------------------------------ the fallen

    /**
     * One of the player's men in an assault falls: by chance ({@link Assault#WOUND}) only wounded, carried back to the rally
     * point, weakened and out of the fight; else dead. Either way he counts against the band's morale. True: wounded (the
     * caller cancels the death).
     */
    public static boolean onOwnFall(LivingEntity entity, ServerLevel level) {
        ServerLevel ow = level.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        for (Assault a : ledger.assaults()) {
            if (a.phase != Assault.Phase.FIGHT || !a.host.contains(entity.getUUID())) {
                continue;
            }
            a.hostFallen++;
            a.assaultFallen++;
            a.host.remove(entity.getUUID());
            ledger.setDirty();
            boolean wounded = new java.util.SplittableRandom(a.id.getLeastSignificantBits() ^ entity.getUUID().getMostSignificantBits() ^ ow.getGameTime())
                    .nextDouble() < Assault.WOUND;
            if (!wounded) {
                return false;
            }
            entity.setHealth(4f);
            entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 2400, 2, false, true));
            entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 2400, 1, false, true));
            UnitProvider units = Services.units();
            if (units != null) {
                units.disengage(entity);
                units.setHome(entity, BlockPos.containing(rally(a)));
            }
            entity.teleportTo(a.rallyX + 0.5, a.rallyY, a.rallyZ + 0.5);
            return true;
        }
        return false;
    }

    /**
     * A village's soldier falls: a defender (or levy) in an assault on it is wounded at the defenders' chance (carried inside),
     * else dead; a man lent to a player's band counts against its morale.
     */
    public static boolean onDefenderFall(LivingEntity entity, ServerLevel level) {
        GarrisonTag tag = GarrisonAttachments.get(entity);
        if (tag == null) {
            return false;
        }
        ServerLevel ow = level.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        for (Assault a : ledger.assaults()) {
            if (a.phase == Assault.Phase.FIGHT && tag.villageId().equals(a.helper) && a.lent.contains(tag.rosterId())) {
                a.hostFallen++; // one of the men his side lent him
                a.assaultFallen++;
                ledger.setDirty();
                return false;
            }
            if (a.phase != Assault.Phase.FIGHT && a.phase != Assault.Phase.REGROUP || !a.target.equals(tag.villageId())) {
                continue;
            }
            VillageRecord t = ledger.get(a.target);
            RosterEntry e = t == null || t.hywRoster == null ? null : t.hywRoster.entry(tag.rosterId());
            if (e == null || e.state().terminal() || !entity.getUUID().equals(e.entityUuid)) {
                return false;
            }
            a.defFallen++;
            ledger.setDirty();
            double draw = new java.util.SplittableRandom(a.id.getMostSignificantBits() ^ e.rosterId.getLeastSignificantBits()).nextDouble();
            if (draw >= dev.hywmill.politics.war.SiegeWaves.defenderWoundChance(t.fortification)) {
                return false;
            }
            entity.setHealth(1f);
            e.wounded = true;
            GarrisonService.stow(ow, e);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the end

    static void end(ServerLevel ow, GarrisonLedger ledger, Assault a, @Nullable VillageRecord t, Assault.Outcome o, String how, long now) {
        a.outcome = o;
        a.phase = Assault.Phase.DONE;
        a.phaseSince = now;
        String name = t == null ? "the village" : t.name;
        String text = switch (o) {
            case WON -> name + " yields to your band: " + how;
            case LOST -> "Your assault on " + name + " has failed: " + how;
            default -> "Your assault on " + name + " is called off: " + how;
        };
        if (o == Assault.Outcome.WON && t != null) {
            int ransom = ransom(t);
            SettlementSource source = Services.settlements();
            ServerPlayer p = online(ow, a.player);
            text += "; it pays you a ransom of " + dev.hywmill.recruit.RecruitOffers.money(ransom) + (a.merit > 0 ? " (your men took "
                    + dev.hywmill.recruit.RecruitOffers.money(a.merit) + " in head prices)" : "");
            if (p != null) {
                if (source != null) {
                    source.giveMoney(p, ransom);
                }
            } else {
                ledger.pendingPay().add(new PoliticsNbt.PendingPay(a.player, source != null ? ransom : 0, text));
            }
            VillageRecord helper = a.helper == null ? null : ledger.get(a.helper);
            if (helper != null && helper.hywRoster != null) {
                helper.hywRoster.levyPoints += dev.hywmill.politics.war.SiegeMath.levy(ransom, SiegeService.rule(helper)); // the men it lent come home with their share
            }
            SiegeService.chronicle(ow, t, t, now, t.name + " yielded to the band of " + playerName(ow, a.player) + " after " + a.assault + " assault"
                    + (a.assault == 1 ? "" : "s"));
        }
        // the band and the lent men stand down; the levies go home; the defenders' wounded stand again
        UnitProvider units = Services.units();
        for (Entity e : alive(ow, a)) {
            if (units != null) {
                units.disengage(e);
                units.setHome(e, BlockPos.containing(rally(a)));
            }
        }
        VillageRecord helper = a.helper == null ? null : ledger.get(a.helper);
        for (RosterEntry e : lentAlive(helper, a)) {
            e.errandUntil = now; // the errand ends: they walk home
        }
        if (t != null && t.hywRoster != null) {
            for (UUID id : a.levies) {
                RosterEntry e = t.hywRoster.entry(id);
                if (e != null && !e.state().terminal()) {
                    MobilizationService.discharge(ow, e, now);
                }
            }
            for (RosterEntry e : t.hywRoster.entries()) {
                e.wounded = false;
            }
        }
        a.levies.clear();
        tell(ow, a, text);
        a.notes.add(text);
        ledger.setDirty();
        HmLog.info("Assault {} ended {}: {}", a.id.toString().substring(0, 8), o, text);
    }

    /** A village that yields to a band pays a ransom: twice its tier's siege tribute. */
    static int ransom(VillageRecord t) {
        PoliticsTables.SiegeRule r = SiegeService.rule(t);
        return 2 * r.tribute(PoliticsTables.MilitaryTierKey.valueOf(t.tier.name()));
    }

    static void tell(ServerLevel ow, Assault a, String text) {
        ServerPlayer p = online(ow, a.player);
        if (p != null) {
            p.sendSystemMessage(Component.literal("[Assault] " + text).withStyle(ChatFormatting.GOLD));
        }
    }

    /** The player if he is on the server (a dev stand-in in the overworld counts), else null. */
    @Nullable
    static ServerPlayer online(ServerLevel ow, UUID player) {
        ServerPlayer p = ow.getServer().getPlayerList().getPlayer(player);
        return p != null ? p : ow.getPlayerByUUID(player) instanceof ServerPlayer sp ? sp : null;
    }

    static String playerName(ServerLevel ow, UUID player) {
        ServerPlayer p = online(ow, player);
        return p != null ? p.getGameProfile().getName() : player.toString().substring(0, 8);
    }

    /** The roster of a village (for tests: whether an id is one of its levies). */
    static boolean levy(GarrisonRoster r, UUID id) {
        RosterEntry e = r.entry(id);
        return e != null && "levy".equals(e.extra);
    }
}

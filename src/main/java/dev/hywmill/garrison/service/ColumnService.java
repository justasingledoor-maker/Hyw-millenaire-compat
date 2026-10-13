package dev.hywmill.garrison.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.duty.Duty;
import dev.hywmill.garrison.equip.EquipmentProfiles;
import dev.hywmill.garrison.spi.EquipmentProvider;
import dev.hywmill.garrison.spi.SpawnRequest;
import dev.hywmill.garrison.spi.SpawnResult;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.garrison.tables.GarrisonTable;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.service.PoliticsService;
import dev.hywmill.politics.service.RelationProjector;
import dev.hywmill.politics.war.Campaign;
import dev.hywmill.politics.war.Column;
import dev.hywmill.politics.war.Mercenaries;
import dev.hywmill.politics.war.Relief;
import dev.hywmill.politics.war.ScoutRide;
import dev.hywmill.politics.war.Siege;
import dev.hywmill.politics.war.Vassalage;
import dev.hywmill.politics.war.WarRecord;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Columns on the road and scouts (post-M5). Off-screen a column is numbers moving along its road by time; near a player it
 * becomes real soldiers (untagged HYW units of its owner's faction, so a campaigning player and his men fight the enemy's),
 * and its march is held while they stand. Killed to the last, it never arrives. Scouts of villages at war ride out, and
 * sometimes find columns (or an enemy supply convoy camped for the night), sometimes nothing, sometimes die. A found column
 * can be taken by a player (War tab), mercenaries bribed from there, or the council acts on its own after a while.
 * All state is in the ledger ({@link GarrisonLedger#columns()}, {@link GarrisonLedger#scoutRides()}).
 */
public final class ColumnService {
    private ColumnService() {}

    /** Blocks per tick: a column at a walk; riders (messengers, alarm). */
    public static final double SPEED = 0.12, RIDER_SPEED = 0.3;
    /** A column comes into the world within SHOW blocks of a player and leaves when none is within HIDE. */
    public static final int SHOW = 96, HIDE = 144;
    /** A found column the council has not seen to after this long (ticks) is decided by it. */
    public static final long COUNCIL = 3600;
    public static final double COUNCIL_ACTS = 0.5, COUNCIL_WINS = 0.6;
    /** Finished columns are kept this long for the War tab. */
    public static final long KEEP = 3 * 24000L;
    /** A convoy camps for a day. */
    public static final long CAMP = 24000L;
    /** A bribe: deniers per man. */
    public static final int BRIBE_PER_MAN = 48;
    public static final double SCOUT_CHANCE = 0.4, ALARM_CHANCE = 0.5, CONVOY_INSTEAD = 0.5;
    public static final Set<String> LIGHT = Set.of("light_lancer_rider", "archer_rider");
    public static final String TAG = "hywmill_column";
    public static final String C_CART = "astikorcartsredux:supply_cart";

    // ------------------------------------------------------------------ the build-up of a siege

    /**
     * A prepared siege is announced: mercenaries hired by the attackers take the road from afar (arriving during the build-up;
     * 1.9: the besieged hire none, they press their own countryside into service instead), the besieged send messengers to
     * their allies (a relief comes only if its messenger arrives) and both sides to their vassals.
     */
    static void announced(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick) {
        long prep = Math.max(2400, s.arriveAt - s.march - SiegeService.rule(a).musterTicks());
        SplittableRandom r = new SplittableRandom(s.seed(0x636F6CL));
        Mercenaries.Hire ha = Mercenaries.roll(s.seed(0x4D455243L), Mercenaries.CHANCE);
        if (ha != null) {
            mercs(overworld, ledger, s, a, ha, tick, prep, r);
        }
        s.mercRolled = true;
        s.vassalRolled = true;
        supplies(overworld, ledger, s, a, t, tick); // 1.10: supply trains on the roads
        for (Relief rl : s.reliefs) {
            VillageRecord h = ledger.get(rl.helper);
            if (h != null) {
                messenger(overworld, ledger, s, t, h, tick + r.nextLong(1200), r);
            }
        }
        for (Vassalage v : ledger.vassalages()) {
            if (v.over(tick)) {
                continue;
            }
            if (v.overlord.equals(a.villageId) && !v.vassal.equals(t.villageId) && ledger.get(v.vassal) != null) {
                messenger(overworld, ledger, s, a, ledger.get(v.vassal), tick + r.nextLong(1200), r);
            } else if (v.overlord.equals(t.villageId) && !v.vassal.equals(a.villageId) && ledger.get(v.vassal) != null) {
                messenger(overworld, ledger, s, t, ledger.get(v.vassal), tick + r.nextLong(1200), r);
            }
        }
        ledger.setDirty();
    }

    /** The host marches: by chance the besieged's scouts saw it muster and ride home with the alarm. */
    static void marching(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick) {
        SplittableRandom r = new SplittableRandom(s.seed(0x616C61L));
        if (r.nextDouble() >= ALARM_CHANCE || t.hywRoster == null) {
            return;
        }
        double fx = a.center.getX() + (t.center.getX() - a.center.getX()) * 0.3, fz = a.center.getZ() + (t.center.getZ() - a.center.getZ()) * 0.3;
        double d = Math.hypot(t.center.getX() - fx, t.center.getZ() - fz);
        Column c = new Column(id(s.id + ">alarm>" + tick), Column.Kind.ALARM, s.id, t.villageId, t.villageId, (int) fx, (int) fz, t.center.getX(),
                t.center.getZ(), tick, tick + Math.max(600, (long) (d / RIDER_SPEED)));
        c.arrive = c.depart + Math.max(600, travel(overworld, c, RIDER_SPEED)); // 1.10: by the roads when they serve
        String rider = lightRider(t);
        for (int i = 0, n = 2 + r.nextInt(2); i < n; i++) {
            c.units.add(rider);
            c.regular.add(true);
        }
        c.survivors = c.units.size();
        // the host's pickets saw them ride off: known to the attacker at once
        c.revealedBy = a.villageId;
        c.revealedAt = tick;
        ledger.columns().add(c);
        ledger.setDirty();
        intel(overworld, ledger, a, c, tick, "pickets of " + a.name + " saw");
    }

    private static void mercs(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord to, Mercenaries.Hire h, long tick, long prep,
                              SplittableRandom r) {
        double ang = r.nextDouble() * Math.PI * 2;
        // 1.9: from as far as the build-up lets them come in time (it is a day now): 200 to 900 blocks
        int far = (int) Math.max(200, Math.min(900, prep * 0.7 * SPEED));
        int dist = Math.max(150, far - r.nextInt(Math.max(1, far / 3)));
        int fx = (int) (to.center.getX() + Math.cos(ang) * dist), fz = (int) (to.center.getZ() + Math.sin(ang) * dist);
        double when = r.nextDouble();
        Column c = new Column(id(s.id + ">mercs>" + to.villageId), Column.Kind.MERCS, s.id, to.villageId, to.villageId, fx, fz, to.center.getX(),
                to.center.getZ(), tick, tick + 1);
        long travel = travel(overworld, c, SPEED); // 1.10: they ride in by the roads when they serve
        c.depart = tick + (long) (when * Math.max(0, prep * 0.8 - travel));
        c.arrive = c.depart + travel;
        c.units.addAll(h.units());
        h.units().forEach(u -> c.regular.add(false));
        c.look = h.company().look();
        c.name = h.company().name();
        c.survivors = c.units.size();
        ledger.columns().add(c);
    }

    private static void messenger(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord from, VillageRecord helper, long depart,
                                  SplittableRandom r) {
        double d = Math.sqrt(from.center.distSqr(helper.center));
        Column c = new Column(id(s.id + ">msg>" + helper.villageId), Column.Kind.MESSENGER, s.id, from.villageId, helper.villageId, from.center.getX(),
                from.center.getZ(), helper.center.getX(), helper.center.getZ(), depart, depart + Math.max(600, (long) (d / RIDER_SPEED)));
        c.helper = helper.villageId;
        c.arrive = c.depart + Math.max(600, travel(overworld, c, RIDER_SPEED)); // 1.10: by the roads when they serve
        String rider = lightRider(from);
        for (int i = 0, n = 1 + r.nextInt(2); i < n; i++) {
            c.units.add(rider);
            c.regular.add(true);
        }
        c.survivors = c.units.size();
        ledger.columns().add(c);
    }

    // ------------------------------------------------------------------ tick

    /** Every second: columns move, arrive, come into the world near players and leave it; councils decide; scouts ride. */
    static void tick(ServerLevel overworld, GarrisonLedger ledger, long tick) {
        for (Column c : new ArrayList<>(ledger.columns())) {
            try {
                step(overworld, ledger, c, tick);
            } catch (RuntimeException ex) {
                HmLog.warn("Column {} step failed: {}", c.id.toString().substring(0, 8), ex.toString());
            }
        }
        if (ledger.columns().removeIf(c -> !c.onRoad() && c.materialized.isEmpty() && tick - c.arrive > KEEP)) {
            ledger.setDirty();
        }
        if (tick % SiegeService.AI_INTERVAL == SiegeService.AI_OFFSET) {
            scouts(overworld, ledger, tick);
        }
        rides(overworld, ledger, tick);
    }

    private static void step(ServerLevel overworld, GarrisonLedger ledger, Column c, long tick) {
        if (!c.onRoad()) {
            return;
        }
        VillageRecord owner = ledger.get(c.owner);
        if (owner == null) {
            end(overworld, ledger, c, Column.State.FAILED, "its village is gone", tick);
            return;
        }
        if (!c.materialized.isEmpty()) {
            watch(overworld, ledger, c, tick);
            return;
        }
        if (c.kind == Column.Kind.CONVOY ? tick >= c.arrive : tick >= c.arrive) {
            if (c.kind == Column.Kind.CONVOY) {
                end(overworld, ledger, c, Column.State.EXPIRED, "the convoy broke camp and moved on", tick);
            } else {
                arrive(overworld, ledger, c, tick);
            }
            return;
        }
        if (tick < c.depart && c.kind != Column.Kind.CONVOY) {
            return; // not on the road yet
        }
        if (c.revealedBy != null && c.takenBy == null && !c.councilDecided && tick - c.revealedAt >= councilDelay(c, tick)) {
            council(overworld, ledger, c, tick);
            if (!c.onRoad()) {
                return;
            }
        }
        double[] p = c.position(tick);
        BlockPos at = BlockPos.containing(p[0], 64, p[1]);
        if (!overworld.isPositionEntityTicking(at)) {
            return;
        }
        for (ServerPlayer pl : overworld.players()) {
            if (!pl.isSpectator() && Math.hypot(pl.getX() - p[0], pl.getZ() - p[1]) < SHOW) {
                materialize(overworld, ledger, c, owner, p, tick);
                return;
            }
        }
    }

    private static long councilDelay(Column c, long tick) {
        return Math.max(200, Math.min(COUNCIL, (c.arrive - c.revealedAt) / 2));
    }

    /** The column is in the world: count the living, hold its march, and stow it when no player is near. */
    private static void watch(ServerLevel overworld, GarrisonLedger ledger, Column c, long tick) {
        double[] p = c.position(tick);
        BlockPos at = BlockPos.containing(p[0], 64, p[1]);
        boolean loaded = overworld.isPositionEntityTicking(at);
        int alive = 0;
        List<Entity> living = new ArrayList<>();
        for (UUID id : c.materialized) {
            Entity e = overworld.getEntity(id);
            if (e != null && e.isAlive()) {
                alive++;
                living.add(e);
            }
        }
        if (loaded) {
            if (alive < c.survivors) {
                c.survivors = alive;
                ledger.setDirty();
            }
            if (alive == 0) {
                c.materialized.clear();
                end(overworld, ledger, c, Column.State.DESTROYED, "cut down to the last man", tick);
                return;
            }
        }
        boolean near = false;
        for (ServerPlayer pl : overworld.players()) {
            if (!pl.isSpectator() && Math.hypot(pl.getX() - p[0], pl.getZ() - p[1]) < HIDE) {
                near = true;
                break;
            }
        }
        if (near && loaded) {
            return;
        }
        // nobody near: the column goes back to the road (its men leave the world)
        living.forEach(Entity::discard);
        c.materialized.clear();
        if (c.kind == Column.Kind.CONVOY) {
            discardCarts(overworld, c);
            end(overworld, ledger, c, Column.State.EXPIRED, "the convoy broke camp and moved on", tick);
            return;
        }
        if (c.kind == Column.Kind.SUPPLY && c.cartsSpawned) {
            c.cargo = cargo(overworld, c); // 1.10: it goes on with what is left in its carts
            discardCarts(overworld, c);
            c.cartsSpawned = false;
        }
        c.resume(tick);
        ledger.setDirty();
    }

    // ------------------------------------------------------------------ in the world

    private static void materialize(ServerLevel overworld, GarrisonLedger ledger, Column c, VillageRecord owner, double[] p, long tick) {
        UnitProvider units = Services.units();
        if (units == null || c.survivors <= 0) {
            return;
        }
        BlockPos base = SiegeService.surface(overworld, BlockPos.containing(p[0], 64, p[1]));
        boolean convoy = c.kind == Column.Kind.CONVOY;
        // the road's heading, for a column in file (1.10: the stretch of road it is on)
        double[] heading = c.route().heading(c.progress(tick));
        double hx = heading[0], hz = heading[1];
        SplittableRandom r = new SplittableRandom(c.id.getLeastSignificantBits() ^ tick);
        int n = 0;
        for (int i = 0; i < c.units.size() && n < c.survivors; i++) {
            BlockPos spot;
            if (convoy) {
                double ang = (Math.PI * 2 * n) / Math.max(1, c.survivors) + r.nextDouble() * 0.5;
                double rad = 4 + r.nextInt(4);
                spot = base.offset((int) Math.round(Math.cos(ang) * rad), 0, (int) Math.round(Math.sin(ang) * rad));
            } else {
                int row = n / 2, side = n % 2 == 0 ? -1 : 1;
                spot = base.offset((int) Math.round(-hx * row * 2.5 + hz * side * 1.5), 0, (int) Math.round(-hz * row * 2.5 - hx * side * 1.5));
            }
            spot = SiegeService.surface(overworld, spot);
            // a vassal's men march under their overlord's banner (they join its host): its own soldiers never take them for enemies
            VillageRecord dest = c.kind == Column.Kind.VASSAL && c.destination != null ? ledger.get(c.destination) : null;
            UUID banner = dest != null ? dest.factionId : owner.factionId;
            Entity e = spawnMan(overworld, owner, banner, c.units.get(i), i < c.regular.size() && c.regular.get(i), c.look,
                    Vec3.atBottomCenterOf(spot), convoy ? base : spot, c.id);
            if (e == null) {
                continue;
            }
            n++;
            c.materialized.add(e.getUUID());
            // marching columns are on their guard and fight; a camped convoy's guards rest, not expecting much
            units.setAutonomous(e, !convoy);
        }
        if (n == 0) {
            return;
        }
        c.survivors = n;
        c.heldSince = tick;
        if (convoy && !c.cartsSpawned) {
            spawnCarts(overworld, c, owner, base, r, 1 + r.nextInt(3));
        } else if (c.kind == Column.Kind.SUPPLY && !c.cartsSpawned) {
            // 1.10: the train's carts follow its guards on the road
            spawnCarts(overworld, c, owner, base.offset((int) Math.round(-hx * 8), 0, (int) Math.round(-hz * 8)), r, c.carts());
        }
        ledger.setDirty();
        for (ServerPlayer pl : overworld.players()) {
            if (Math.hypot(pl.getX() - p[0], pl.getZ() - p[1]) < HIDE) {
                pl.sendSystemMessage(Component.literal("[Road] You come upon " + c.label() + " of " + owner.name + ", "
                        + Column.where(pl.getX(), pl.getZ(), p[0], p[1])).withStyle(ChatFormatting.YELLOW));
            }
        }
        HmLog.info("Column {} ({}) of {} in the world at {}: {} men", c.id.toString().substring(0, 8), c.kind, owner.name, base.toShortString(), n);
    }

    /** One soldier of a column: an untagged HYW unit of the owner's faction, equipped as the village's (or its company's look). */
    @Nullable
    static Entity spawnMan(ServerLevel overworld, VillageRecord owner, String unitKey, boolean regular, String look, Vec3 pos, BlockPos home, UUID column) {
        return spawnMan(overworld, owner, owner.factionId, unitKey, regular, look, pos, home, column);
    }

    /** {@code faction}: the HYW owner the soldier fights for (his village's own, or the overlord a vassal's men serve). */
    @Nullable
    static Entity spawnMan(ServerLevel overworld, VillageRecord owner, UUID faction, String unitKey, boolean regular, String look, Vec3 pos,
                           BlockPos home, UUID column) {
        UnitProvider units = Services.units();
        GarrisonTables tables = GarrisonTables.current();
        UnitSpec spec = tables.units().get(unitKey);
        if (units == null || spec == null || !units.isValidUnitType(spec.entityType())) {
            return null;
        }
        GarrisonTable table = tables.forCulture(owner.culture);
        EquipmentProvider eq = Services.equipment(table.equipmentProvider());
        if (eq == null) {
            eq = Services.equipment("hyw");
        }
        if (eq == null) {
            return null;
        }
        PoliticsTables.MobilizationRule mob = MobilizationService.rule(owner);
        int reg = dev.hywmill.garrison.Recruitment.equipmentLevel(owner.tier, table);
        int level = regular ? reg : dev.hywmill.garrison.Mobilization.equipmentLevel(reg, mob.equipmentFloor(), mob.equipmentDrop());
        boolean merc = !look.isEmpty();
        int[] livery = merc ? null : LiveryService.of(overworld, owner);
        String role = merc ? EquipmentProfiles.LOOK_PREFIX + look : regular ? "" : EquipmentProfiles.LEVY;
        UUID id = UUID.randomUUID();
        SpawnResult res = units.spawn(overworld, new SpawnRequest(spec, faction, id, pos, home, level, false, null, eq,
                new EquipmentProvider.Context(owner.culture, EquipmentProfiles.gearTier(!regular, level, owner.tier), role,
                        EquipmentProfiles.classRole(spec.unitClass()), id).withLivery(livery)));
        if (!res.ok()) {
            return null;
        }
        res.entity().getPersistentData().putUUID(TAG, column);
        Entity mount = units.mount(res.entity());
        if (mount != null) {
            mount.getPersistentData().putUUID(TAG, column);
        }
        return res.entity();
    }

    /** A column's soldier (or its horse) loaded from the world after its column left it, or a column that is gone: dropped. */
    public static boolean onJoin(Entity entity, ServerLevel level) {
        UUID cid = entity.getPersistentData().getUUID(TAG);
        GarrisonLedger ledger = GarrisonLedger.get(level.getServer().overworld());
        for (Column c : ledger.columns()) {
            if (c.id.equals(cid)) {
                if (c.materialized.contains(entity.getUUID()) || c.carts.contains(entity.getUUID())) {
                    return true;
                }
                Entity rider = entity.getFirstPassenger();
                return rider != null && c.materialized.contains(rider.getUUID());
            }
        }
        return false;
    }

    private static void spawnCarts(ServerLevel overworld, Column c, VillageRecord owner, BlockPos base, SplittableRandom r, int carts) {
        int keep = c.cargo; // 1.10: a train met before carries what was left in its carts then
        for (int i = 0; i < carts; i++) {
            BlockPos at = SiegeService.surface(overworld, base.offset(i * 4 - (carts - 1) * 2, 0, 0));
            Entity cart = dev.hywmill.integration.astikor.AstikorCarts.supplyCart(overworld, Vec3.atBottomCenterOf(at));
            if (cart == null) {
                cart = EntityType.CHEST_MINECART.create(overworld);
                if (cart == null) {
                    continue;
                }
                cart.moveTo(Vec3.atBottomCenterOf(at));
                overworld.addFreshEntity(cart);
            }
            c.carts.add(cart.getUUID());
            if (cart instanceof Container box) {
                ConvoyLoot.fill(box, owner.culture, c.theme, r);
                if (keep >= 0) {
                    int share = keep / carts + (i < keep % carts ? 1 : 0);
                    trim(box, share);
                }
            }
            var horse = EntityType.HORSE.create(overworld);
            if (horse != null) {
                horse.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 2.5, 0, 0);
                horse.finalizeSpawn(overworld, overworld.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
                horse.setTamed(true);
                overworld.addFreshEntity(horse);
                c.carts.add(horse.getUUID());
                dev.hywmill.integration.astikor.AstikorCarts.hitch(cart, horse);
            }
        }
        c.cartsSpawned = true;
    }

    /** Keeps the first {@code keep} full slots of a cart (what was left in it when its train was last met). */
    static void trim(Container box, int keep) {
        int seen = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (!box.getItem(i).isEmpty() && ++seen > keep) {
                box.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
            }
        }
    }

    /** The full slots left in a train's carts. */
    static int cargo(ServerLevel overworld, Column c) {
        int n = 0;
        for (UUID id : c.carts) {
            if (overworld.getEntity(id) instanceof Container box) {
                for (int i = 0; i < box.getContainerSize(); i++) {
                    n += box.getItem(i).isEmpty() ? 0 : 1;
                }
            }
        }
        return n;
    }

    /**
     * 1.10: a supply train cut on the road: each player near it who is at war with its village earns the bounty from his side
     * ({@link dev.hywmill.politics.war.Supply#bounty}), and the merit in the siege.
     */
    private static void bounty(ServerLevel overworld, GarrisonLedger ledger, Column c, Siege s, long tick) {
        double[] p = c.position(tick);
        int pay = dev.hywmill.politics.war.Supply.bounty(c.carts());
        dev.hywmill.settlement.SettlementSource source = Services.settlements();
        for (ServerPlayer pl : overworld.players()) {
            if (pl.isSpectator() || Math.hypot(pl.getX() - p[0], pl.getZ() - p[1]) > HIDE
                    || WarSpoils.payer(ledger, pl.getUUID(), c.owner, tick) == null) {
                continue;
            }
            if (source != null) {
                source.giveMoney(pl, pay);
            }
            s.merit.merge(pl.getUUID(), pay, Integer::sum);
            pl.sendSystemMessage(Component.literal("[Road] +" + dev.hywmill.recruit.RecruitOffers.money(pay) + ": the bounty for cutting " + c.label()
                    + ". Its carts are yours.").withStyle(ChatFormatting.GOLD));
            HmLog.info("Column {}: {} earns the bounty of {} deniers for cutting it", c.id.toString().substring(0, 8), pl.getGameProfile().getName(), pay);
        }
    }

    /** Supply trains go this far at most (blocks from the village they serve). */
    static final int SUPPLY_REACH = 1600;

    /**
     * 1.10 (docs/siege-design.md "War on the roads"): a siege announced, each side's realm sends its supply trains by the
     * roads: the besieger's to its host's camp through the first day before the walls, the besieged's friends' before the host
     * is there. Returns how many trains set out.
     */
    public static int supplies(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long tick) {
        for (Column c : ledger.columns()) {
            if (c.kind == Column.Kind.SUPPLY && s.id.equals(c.siege)) {
                return 0; // sent already
            }
        }
        long hostAt = tick + Math.max(1200, s.arriveAt - s.elapsed(tick, overworld.getDayTime()));
        SplittableRandom r = new SplittableRandom(s.seed(0x737570L));
        int n = 0;
        // the host's: from the attacker and the nearest of its realm, to the camp before the walls
        List<VillageRecord> ours = senders(ledger, a, t, true, tick);
        double ux = a.center.getX() - t.center.getX(), uz = a.center.getZ() - t.center.getZ(), ul = Math.max(1, Math.hypot(ux, uz));
        int reach = (t.villageRadius > 0 ? t.villageRadius : 64) + 48;
        int campX = (int) Math.round(t.center.getX() + ux / ul * reach), campZ = (int) Math.round(t.center.getZ() + uz / ul * reach);
        int trains = dev.hywmill.politics.war.Supply.trains(ours.size() - 1);
        for (int k = 0; k < trains && !ours.isEmpty(); k++) {
            VillageRecord from = ours.get(k % ours.size());
            n += train(overworld, ledger, s, from, a, campX, campZ, true, hostAt + 1200 + k * 4000L, k, tick, r) ? 1 : 0;
        }
        // the besieged's: from their realm, before the host is at the walls
        List<VillageRecord> theirs = senders(ledger, t, a, false, tick);
        for (int k = 0; k < Math.min(dev.hywmill.politics.war.Supply.MAX_TRAINS, theirs.size()); k++) {
            n += train(overworld, ledger, s, theirs.get(k), t, t.center.getX(), t.center.getZ(), false, hostAt - 2400 - k * 2400L, k, tick, r) ? 1 : 0;
        }
        ledger.setDirty();
        if (n > 0) {
            HmLog.info("Siege {}: {} supply train(s) take the road ({} for the host, {} for the besieged)", s.id.toString().substring(0, 8), n,
                    Math.min(trains, ours.isEmpty() ? 0 : trains), Math.min(dev.hywmill.politics.war.Supply.MAX_TRAINS, theirs.size()));
        }
        return n;
    }

    /** The villages that can send a side's trains: its realm's (the village itself first for the host), not besieged, within reach. */
    private static List<VillageRecord> senders(GarrisonLedger ledger, VillageRecord v, VillageRecord foe, boolean self, long tick) {
        List<VillageRecord> out = new ArrayList<>();
        UUID sov = dev.hywmill.politics.service.RealmService.sovereignOf(ledger, v.villageId, tick);
        for (UUID m : dev.hywmill.politics.service.RealmService.members(ledger, sov, tick)) {
            VillageRecord mv = ledger.get(m);
            if (mv == null || mv == foe || mv.hywRoster == null || mv.loneBuilding || SiegeService.besieged(ledger, m) || !self && mv == v
                    || Math.sqrt(mv.center.distSqr(foe.center)) > SUPPLY_REACH + Math.sqrt(v.center.distSqr(foe.center))) {
                continue;
            }
            out.add(mv);
        }
        if (self && !out.contains(v)) {
            out.add(v);
        }
        out.sort(java.util.Comparator.comparingDouble((VillageRecord x) -> x == v && self ? -1 : x.center.distSqr(v.center)));
        return out;
    }

    /** One supply train from {@code from} to (x, z), serving {@code dest}, to arrive about {@code arriveBy}. */
    private static boolean train(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord from, VillageRecord dest, int x, int z, boolean host,
                                 long arriveBy, int k, long tick, SplittableRandom r) {
        Column c = new Column(id(s.id + ">supply>" + (host ? "h" : "d") + k), Column.Kind.SUPPLY, s.id, from.villageId, dest.villageId,
                from.center.getX(), from.center.getZ(), x, z, tick, tick + 1);
        long travel = Math.max(600, travel(overworld, c, dev.hywmill.politics.war.Supply.SPEED));
        c.depart = Math.max(tick + 200, arriveBy - travel);
        c.arrive = c.depart + travel;
        c.forHost = host;
        c.theme = host ? (r.nextBoolean() ? "military" : "siege") : (r.nextBoolean() ? "provisions" : "mixed");
        List<String> pool = new ArrayList<>();
        GarrisonTables tables = GarrisonTables.current();
        dev.hywmill.garrison.Recruitment.eligibleUnits(from.tier, tables.forCulture(from.culture), tables.units()).forEach(u -> {
            if (u.unitClass() != UnitClass.CAVALRY && !LIGHT.contains(u.key())) {
                pool.add(u.key());
            }
        });
        MobilizationService.rule(from).levyUnits().keySet().forEach(pool::add);
        if (pool.isEmpty()) {
            pool.add("spear_man");
        }
        int guards = dev.hywmill.politics.war.Supply.guards(r.nextDouble());
        for (int i = 0; i < guards; i++) {
            c.units.add(pool.get(r.nextInt(pool.size())));
            c.regular.add(r.nextDouble() < 0.4);
        }
        c.survivors = guards;
        ledger.columns().add(c);
        HmLog.info("Column {} (SUPPLY): {} sends {} to {} ({}), {} min on the road{}", c.id.toString().substring(0, 8), from.name, c.label(),
                host ? "the host's camp" : dest.name, x + ", " + z, Math.max(1, travel / 1200), c.route().roads() ? " by the roads" : " across country");
        return true;
    }

    private static void discardCarts(ServerLevel overworld, Column c) {
        for (UUID id : c.carts) {
            Entity e = overworld.getEntity(id);
            if (e != null) {
                if (e instanceof Container box) {
                    box.clearContent(); // nothing spills: the convoy took it with it
                }
                e.discard();
            }
        }
        c.carts.clear();
    }

    // ------------------------------------------------------------------ arrival and endings

    private static void arrive(ServerLevel overworld, GarrisonLedger ledger, Column c, long tick) {
        Siege s = siegeOf(ledger, c);
        VillageRecord owner = ledger.get(c.owner);
        VillageRecord dest = c.destination != null ? ledger.get(c.destination) : null;
        if (s == null || s.phase == Siege.Phase.RETURN || s.outcome != Siege.Outcome.NONE || dest == null || owner == null) {
            end(overworld, ledger, c, Column.State.FAILED, "it came too late", tick);
            return;
        }
        if (c.kind == Column.Kind.SUPPLY && !c.forHost && s.fighting()) {
            end(overworld, ledger, c, Column.State.FAILED, "the host's lines bar the way into " + dest.name, tick);
            return;
        }
        if (s.wave > 0 && c.kind != Column.Kind.ALARM && c.kind != Column.Kind.SUPPLY) {
            // post-M5: help joins only before the first assault; once the siege is fought it comes too late
            end(overworld, ledger, c, Column.State.FAILED, "it came too late: the siege had begun", tick);
            return;
        }
        VillageRecord a = ledger.get(s.attacker), t = ledger.get(s.target);
        String text;
        switch (c.kind) {
            case MERCS, VASSAL -> {
                String kind = c.kind == Column.Kind.MERCS ? "merc" : "vassal";
                int n;
                if (dest.villageId.equals(s.attacker)) {
                    for (int i = 0; i < c.units.size() && i < c.survivors; i++) {
                        s.pendingUnits.add(c.units.get(i));
                        s.pendingRegular.add(i < c.regular.size() && c.regular.get(i));
                        s.pendingLook.add(c.look);
                        s.pendingKind.add(kind);
                    }
                    n = Math.min(c.units.size(), c.survivors);
                    if (s.phase != Siege.Phase.PREPARE) {
                        SiegeService.joinPending(ledger, s, dest, tick); // the host has mustered: they join it at once
                    }
                    text = c.label() + " reached " + dest.name + " and joins its host against " + (t == null ? "?" : t.name);
                } else {
                    n = SiegeService.raiseExtras(dest, s, c.units.subList(0, Math.min(c.units.size(), c.survivors)), kind, c.look,
                            c.kind == Column.Kind.MERCS, levyLevel(dest), tick);
                    if (overworld.isPositionEntityTicking(GarrisonService.anchorOf(dest))) {
                        SiegeService.musterExtras(overworld, s, dest, tick);
                    }
                    text = c.label() + " reached " + dest.name + " and mans its defences against " + (a == null ? "?" : a.name);
                }
                s.notes.add((c.kind == Column.Kind.MERCS ? c.name : "a vassal's men") + " reached " + dest.name + " (" + n + ")");
            }
            case MESSENGER -> {
                VillageRecord h = c.helper != null ? ledger.get(c.helper) : null;
                if (h == null) {
                    end(overworld, ledger, c, Column.State.FAILED, "nobody to call", tick);
                    return;
                }
                boolean any = false, besieged = SiegeService.besieged(ledger, h.villageId);
                // post-M5: it will not fight beside a village it is at war with or despises (a province minds no one)
                VillageRecord foe = besieged ? null : SiegeService.wontFightBeside(overworld, ledger, s, owner.villageId.equals(s.attacker), h.villageId, tick);
                for (Relief rl : s.reliefs) {
                    if (rl.helper.equals(h.villageId)) {
                        rl.called = !besieged && foe == null;
                        any |= rl.called;
                        if (foe != null && rl.sent == 0) {
                            rl.phase = Relief.Phase.DONE;
                        }
                    }
                }
                for (Vassalage v : ledger.vassalages()) {
                    if (!besieged && foe == null && v.vassal.equals(h.villageId) && v.overlord.equals(owner.villageId) && !v.over(tick)) {
                        any |= vassalColumn(overworld, ledger, s, owner, h, tick);
                    }
                }
                text = "A messenger of " + owner.name + " reached " + h.name + (any ? ": it sends help"
                        : besieged ? ": besieged itself, it sends no one"
                        : foe != null ? ": it will not fight beside " + foe.name + " and sends no one" : ": it sends no one");
                s.notes.add(text);
            }
            case ALARM -> {
                // 1.9: the scouts counted the host as it marched: the besieged know its strength and levy accordingly
                text = "Scouts of " + dest.name + " brought the alarm home" + (a != null ? ": " + SiegeService.hostCounted(overworld, ledger, s, a, dest, tick) : "");
                s.notes.add(dest.name + "'s scouts brought the alarm home");
            }
            case SUPPLY -> {
                // 1.10: the train reached the host's camp (or the besieged before the host): its side is better found
                String what = SiegeService.supplied(s, c.forHost, true);
                text = c.label() + " of " + owner.name + " reached " + (c.forHost ? "the host's camp before " + (t == null ? "?" : t.name) : dest.name)
                        + ": " + what;
                s.notes.add(text);
            }
            default -> text = "";
        }
        c.state = Column.State.ARRIVED;
        c.outcome = "arrived";
        ledger.setDirty();
        if (a != null) {
            SiegeService.chronicle(overworld, a, t, tick, text);
            SiegeService.announce(overworld, ledger, s, a, t, text);
        }
        HmLog.info("Column {} ({}): {}", c.id.toString().substring(0, 8), c.kind, text);
    }

    /** A vassal answers its overlord's messenger (three times in four): its men take the road. */
    private static boolean vassalColumn(ServerLevel overworld, GarrisonLedger ledger, Siege s, VillageRecord lord, VillageRecord vassal, long tick) {
        List<Boolean> men = Vassalage.levy(s.seed(vassal.villageId.getMostSignificantBits() ^ 0x766173L));
        if (men.isEmpty()) {
            return false;
        }
        GarrisonTables tables = GarrisonTables.current();
        var table = tables.forCulture(vassal.culture);
        List<String> pool = new ArrayList<>();
        dev.hywmill.garrison.Recruitment.eligibleUnits(vassal.tier, table, tables.units()).forEach(u -> {
            if (!LIGHT.contains(u.key())) {
                pool.add(u.key());
            }
        });
        MobilizationService.rule(vassal).levyUnits().keySet().forEach(pool::add);
        if (pool.isEmpty()) {
            return false;
        }
        double d = Math.sqrt(vassal.center.distSqr(lord.center));
        Column c = new Column(id(s.id + ">vassal>" + vassal.villageId), Column.Kind.VASSAL, s.id, vassal.villageId, lord.villageId,
                vassal.center.getX(), vassal.center.getZ(), lord.center.getX(), lord.center.getZ(), tick, tick + Math.max(1200, (long) (d / SPEED)));
        c.arrive = c.depart + Math.max(1200, travel(overworld, c, SPEED)); // 1.10: by the roads when they serve
        c.units.addAll(dev.hywmill.politics.war.DefenderAid.draw(pool, men.size(), s.seed(vassal.villageId.getLeastSignificantBits())));
        c.regular.addAll(men.subList(0, c.units.size()));
        c.survivors = c.units.size();
        ledger.columns().add(c);
        return true;
    }

    /** Post-M5: a village about to be besieged calls back its vassal levies still on the road. Returns how many men. */
    static int recall(ServerLevel overworld, GarrisonLedger ledger, UUID village, String why, long tick) {
        int n = 0;
        for (Column c : ledger.columns()) {
            if (c.kind != Column.Kind.VASSAL || !c.onRoad() || !c.owner.equals(village)) {
                continue;
            }
            for (UUID id : c.materialized) {
                Entity e = overworld.getEntity(id);
                if (e != null) {
                    e.discard();
                }
            }
            c.materialized.clear();
            n += c.survivors;
            end(overworld, ledger, c, Column.State.FAILED, "called home: " + why, tick);
        }
        return n;
    }

    private static void end(ServerLevel overworld, GarrisonLedger ledger, Column c, Column.State st, String why, long tick) {
        c.state = st;
        c.outcome = why;
        c.heldSince = -1;
        c.arrive = Math.max(c.arrive, tick);
        ledger.setDirty();
        VillageRecord owner = ledger.get(c.owner);
        String text = c.label() + (owner != null ? " of " + owner.name : "") + ": " + why;
        if (st == Column.State.DESTROYED) {
            text = c.label() + (owner != null ? " of " + owner.name : "") + " was destroyed on the road (" + why + ")" + consequence(ledger, c);
            if (c.kind == Column.Kind.CONVOY || c.kind == Column.Kind.SUPPLY) {
                c.carts.clear(); // its carts stay where they stand, for the taking
                text += "; its carts are there for the taking";
            }
        }
        Siege s = siegeOf(ledger, c);
        if (s != null && st == Column.State.DESTROYED && c.kind == Column.Kind.SUPPLY) {
            text += "; " + SiegeService.supplied(s, c.forHost, false); // 1.10: its side goes short
            bounty(overworld, ledger, c, s, tick);
        }
        if (s != null && st == Column.State.DESTROYED) {
            s.notes.add(c.label() + " destroyed on the road");
            VillageRecord a = ledger.get(s.attacker);
            if (a != null) {
                SiegeService.announce(overworld, ledger, s, a, ledger.get(s.target), text);
            }
        } else if (st == Column.State.DESTROYED) {
            tell(overworld, ledger, c, text);
        }
        HmLog.info("Column {} ({}) {}: {}", c.id.toString().substring(0, 8), c.kind, st, st == Column.State.DESTROYED ? text : why);
    }

    private static String consequence(GarrisonLedger ledger, Column c) {
        VillageRecord d = c.destination != null ? ledger.get(c.destination) : null;
        VillageRecord h = c.helper != null ? ledger.get(c.helper) : null;
        return switch (c.kind) {
            case MERCS, VASSAL -> d == null ? "" : ": " + d.name + " will not have them";
            case MESSENGER -> h == null ? "" : ": " + h.name + " will never hear the call";
            case ALARM -> d == null ? "" : ": " + d.name + " will not hear of the host in time";
            case CONVOY -> "";
            case SUPPLY -> "";
        };
    }

    // ------------------------------------------------------------------ the council, the War tab

    /** The council of the village whose scouts found a column sees to it if no player took the job. */
    private static void council(ServerLevel overworld, GarrisonLedger ledger, Column c, long tick) {
        c.councilDecided = true;
        ledger.setDirty();
        VillageRecord v = ledger.get(c.revealedBy);
        if (v == null || c.revealedBy.equals(c.owner)) {
            return;
        }
        SplittableRandom r = new SplittableRandom(c.id.getMostSignificantBits() ^ 0x636F756EL);
        if (!hunts(ledger, v.villageId, c)) {
            tell(overworld, ledger, c, v.name + "'s council lets " + c.label() + " pass: they ride for a friend");
            return;
        }
        if (r.nextDouble() >= COUNCIL_ACTS) {
            tell(overworld, ledger, c, v.name + "'s council lets " + c.label() + " pass");
            return;
        }
        if (r.nextDouble() < COUNCIL_WINS) {
            c.survivors = 0;
            end(overworld, ledger, c, Column.State.DESTROYED, "riders of " + v.name + " caught it", tick);
        } else {
            tell(overworld, ledger, c, "riders of " + v.name + " went after " + c.label() + " but it slipped through");
        }
    }

    /**
     * Post-M5: {@code v}'s scouts and council go after a column only if its men are {@code v}'s enemies and ride for an enemy
     * of {@code v}, and {@code v} has no men of its own on that side of the siege. Men riding to help a friend (or a village
     * {@code v} itself fights beside) are let pass, whoever they are.
     */
    static boolean hunts(GarrisonLedger ledger, UUID v, Column c) {
        UUID serves = c.kind == Column.Kind.MESSENGER || c.kind == Column.Kind.CONVOY || c.destination == null ? c.owner : c.destination;
        if (!RelationProjector.atWar(ledger, v, c.owner) || !RelationProjector.atWar(ledger, v, serves)) {
            return false;
        }
        Siege s = siegeOf(ledger, c);
        return s == null || !SiegeService.side(ledger, s, serves.equals(s.attacker)).contains(v);
    }

    /** A player takes the job of intercepting a found column (on campaign with the village that found it). */
    public static String take(ServerLevel overworld, GarrisonLedger ledger, ServerPlayer player, Column c) {
        if (!c.open(overworld.getGameTime())) {
            return "That column is no longer open.";
        }
        if (!mayAct(ledger, player.getUUID(), c)) {
            return "You must be on campaign against " + name(ledger, c.owner) + ".";
        }
        c.takenBy = player.getUUID();
        ledger.setDirty();
        double[] p = c.position(overworld.getGameTime());
        return "You take the job: " + c.label() + " is at " + (int) p[0] + ", " + (int) p[1] + " (" + Column.where(player.getX(), player.getZ(), p[0], p[1])
                + ")" + eta(c, overworld.getGameTime());
    }

    /** Price to buy a mercenary company on the road (deniers). */
    public static int price(Column c) {
        return Math.max(1, c.survivors) * BRIBE_PER_MAN;
    }

    /** Whether a player may bribe this column: mercenaries of the enemy, riding to a siege the player's ally is part of. */
    public static boolean bribable(GarrisonLedger ledger, UUID player, Column c, long now) {
        if (c.kind != Column.Kind.MERCS || c.bribed || !c.onRoad() || c.revealedBy == null) {
            return false;
        }
        Campaign camp = RelationProjector.campaignOf(ledger, player);
        Siege s = siegeOf(ledger, c);
        return camp != null && camp.active(now) && camp.enemy().equals(c.owner) && s != null && s.involves(camp.ally());
    }

    public static String bribe(ServerLevel overworld, GarrisonLedger ledger, ServerPlayer player, Column c) {
        long now = overworld.getGameTime();
        if (!bribable(ledger, player.getUUID(), c, now)) {
            return "You cannot bribe that company.";
        }
        int cost = price(c);
        var source = Services.settlements();
        if (source == null || !source.takeMoney(player, cost)) {
            return "You need " + cost + " deniers to buy " + c.name + ".";
        }
        Campaign camp = RelationProjector.campaignOf(ledger, player.getUUID());
        VillageRecord ally = ledger.get(camp.ally());
        VillageRecord was = ledger.get(c.owner);
        c.owner = ally.villageId;
        c.destination = ally.villageId;
        c.toX = ally.center.getX();
        c.toZ = ally.center.getZ();
        double[] p = c.position(now);
        long travel = (long) (Math.hypot(c.toX - p[0], c.toZ - p[1]) / SPEED);
        // the road from where it stands to its new master
        Column nc = c;
        nc.bribed = true;
        nc.depart = now - 1;
        nc.arrive = now + Math.max(600, travel);
        nc.councilDecided = true;
        ledger.columns().remove(c);
        Column moved = new Column(c.id, c.kind, c.siege, c.owner, c.destination, (int) p[0], (int) p[1], c.toX, c.toZ, nc.depart, nc.arrive);
        copy(c, moved);
        moved.arrive = moved.depart + Math.max(600, travel(overworld, moved, SPEED)); // 1.10: by the roads to its new master
        ledger.columns().add(moved);
        ledger.setDirty();
        String text = PoliticsService.playerName(overworld, player.getUUID()) + " bought " + c.name + " away from " + (was == null ? "?" : was.name)
                + ": it rides for " + ally.name;
        Siege s = siegeOf(ledger, moved);
        if (s != null) {
            s.notes.add(text);
            VillageRecord a = ledger.get(s.attacker);
            if (a != null) {
                SiegeService.announce(overworld, ledger, s, a, ledger.get(s.target), text);
            }
        }
        HmLog.info("Column {}: {}", c.id.toString().substring(0, 8), text);
        return "Paid " + cost + " deniers: " + c.name + " now rides for " + ally.name + ".";
    }

    private static void copy(Column from, Column to) {
        to.units.addAll(from.units);
        to.regular.addAll(from.regular);
        to.look = from.look;
        to.name = from.name;
        to.theme = from.theme;
        to.helper = from.helper;
        to.state = from.state;
        to.survivors = from.survivors;
        to.revealedBy = from.revealedBy;
        to.revealedAt = from.revealedAt;
        to.takenBy = from.takenBy;
        to.councilDecided = from.councilDecided;
        to.bribed = from.bribed;
        to.materialized.addAll(from.materialized);
        to.heldSince = from.heldSince;
        to.cartsSpawned = from.cartsSpawned;
        to.carts.addAll(from.carts);
        to.outcome = from.outcome;
        to.forHost = from.forHost;
    }

    /** The player may act on a column: on campaign against its owner (or the column belongs to his ally's enemy's side). */
    public static boolean mayAct(GarrisonLedger ledger, UUID player, Column c) {
        Campaign camp = RelationProjector.campaignOf(ledger, player);
        // 1.10: or it serves the enemy (a supply train of its realm), or comes from a village his ally is at war with
        return camp != null && (camp.enemy().equals(c.owner) || c.destination != null && camp.enemy().equals(c.destination)
                || RelationProjector.atWar(ledger, camp.ally(), c.owner));
    }

    /** Columns a player should see in the War tab: found by his ally's scouts (or concerning his ally's war). */
    public static List<Column> visible(GarrisonLedger ledger, UUID player) {
        Campaign camp = RelationProjector.campaignOf(ledger, player);
        List<Column> out = new ArrayList<>();
        for (Column c : ledger.columns()) {
            if (c.revealedBy == null) {
                continue;
            }
            if (camp == null || c.revealedBy.equals(camp.ally()) || c.owner.equals(camp.enemy()) || player.equals(c.takenBy)) {
                out.add(c);
            }
        }
        return out;
    }

    public static String describe(ServerLevel overworld, GarrisonLedger ledger, Column c, @Nullable ServerPlayer viewer) {
        long now = overworld.getGameTime();
        String who = name(ledger, c.owner);
        StringBuilder b = new StringBuilder(c.label()).append(" of ").append(who);
        if (c.onRoad()) {
            double[] p = c.position(now);
            b.append(" at ").append((int) p[0]).append(", ").append((int) p[1]);
            if (viewer != null) {
                b.append(" (").append(Column.where(viewer.getX(), viewer.getZ(), p[0], p[1])).append(")");
            }
            if (c.destination != null && c.kind != Column.Kind.CONVOY) {
                b.append(" -> ").append(name(ledger, c.destination));
            }
            if (c.route().roads()) {
                b.append(c.kind == Column.Kind.CONVOY ? ", camped by the road" : c.onTheRoad(now) ? ", on the road" : ", making for the road"); // 1.10
            }
            b.append(eta(c, now));
            if (c.takenBy != null) {
                b.append("; taken by ").append(PoliticsService.playerName(overworld, c.takenBy));
            } else if (c.councilDecided) {
                b.append("; the council decided");
            }
        } else {
            b.append(": ").append(c.state.name().toLowerCase()).append(c.outcome.isEmpty() ? "" : " (" + c.outcome + ")");
        }
        return b.toString();
    }

    private static String eta(Column c, long now) {
        long left = c.arrive - (c.heldSince >= 0 ? c.heldSince : now);
        if (c.kind == Column.Kind.CONVOY) {
            return "; camped for about " + Math.max(1, left / 1200) + " more min";
        }
        if (now < c.depart) {
            return "; sets out in about " + Math.max(1, (c.depart - now) / 1200) + " min";
        }
        return "; arrives in about " + Math.max(1, left / 1200) + " min";
    }

    @Nullable
    public static Column find(GarrisonLedger ledger, String prefix) {
        for (Column c : ledger.columns()) {
            if (c.id.toString().startsWith(prefix)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ scouts

    /** Every minute: each village at war with a light horseman at home may send him out (by chance). */
    private static void scouts(ServerLevel overworld, GarrisonLedger ledger, long tick) {
        Set<UUID> atWar = new HashSet<>();
        for (WarRecord w : ledger.wars().values()) {
            if (w.atWar()) {
                atWar.add(w.a);
                atWar.add(w.b);
            }
        }
        for (UUID vid : atWar) {
            VillageRecord v = ledger.get(vid);
            if (v == null || v.hywRoster == null || ledger.scoutRides().stream().anyMatch(r -> r.village().equals(vid))) {
                continue;
            }
            SplittableRandom r = new SplittableRandom(vid.getMostSignificantBits() ^ tick);
            if (r.nextDouble() >= SCOUT_CHANCE) {
                continue;
            }
            RosterEntry rider = idleRider(v);
            if (rider != null) {
                ride(overworld, ledger, v, rider, tick, r);
            }
        }
    }

    @Nullable
    static RosterEntry idleRider(VillageRecord v) {
        for (RosterEntry e : v.hywRoster.entries()) {
            if (LIGHT.contains(e.unitKey) && e.state() == UnitState.GARRISONED && e.duty.standing() && e.extra.isEmpty() && e.mercLook.isEmpty()) {
                return e;
            }
        }
        return null;
    }

    /** A scout rides out (he leaves the world until he comes back). */
    static void ride(ServerLevel overworld, GarrisonLedger ledger, VillageRecord v, RosterEntry rider, long tick, SplittableRandom r) {
        GarrisonService.stow(overworld, rider);
        rider.transition(UnitState.DEPLOYED, tick);
        rider.duty = Duty.SIEGE;
        long back = tick + ScoutRide.MIN_TICKS + r.nextLong(ScoutRide.MAX_TICKS - ScoutRide.MIN_TICKS + 1);
        ledger.scoutRides().add(new ScoutRide(v.villageId, rider.rosterId, tick, back));
        ledger.setDirty();
        HmLog.info("Scouts: a rider of {} rides out ({} s)", v.name, (back - tick) / 20);
    }

    /** Admin: a scout rides out now and comes back at once (forced: he finds something). */
    public static String forceScout(ServerLevel overworld, GarrisonLedger ledger, VillageRecord v, long tick) {
        RosterEntry rider = idleRider(v);
        if (rider == null) {
            return v.name + " has no light horseman at home";
        }
        SplittableRandom r = new SplittableRandom(tick);
        ride(overworld, ledger, v, rider, tick, r);
        ScoutRide sr = ledger.scoutRides().get(ledger.scoutRides().size() - 1);
        ledger.scoutRides().remove(sr);
        return back(overworld, ledger, new ScoutRide(sr.village(), sr.rider(), sr.out(), tick), tick, true);
    }

    private static void rides(ServerLevel overworld, GarrisonLedger ledger, long tick) {
        for (ScoutRide sr : new ArrayList<>(ledger.scoutRides())) {
            if (tick >= sr.back()) {
                ledger.scoutRides().remove(sr);
                ledger.setDirty();
                back(overworld, ledger, sr, tick, false);
            }
        }
    }

    /** A scout comes back (or not): killed one time in ten, else one in two found something. */
    private static String back(ServerLevel overworld, GarrisonLedger ledger, ScoutRide sr, long tick, boolean forced) {
        VillageRecord v = ledger.get(sr.village());
        if (v == null || v.hywRoster == null) {
            return "the village is gone";
        }
        RosterEntry e = v.hywRoster.entry(sr.rider());
        SplittableRandom r = new SplittableRandom(sr.rider().getLeastSignificantBits() ^ tick);
        if (!forced && r.nextDouble() < ScoutRide.LOST) {
            if (e != null && !e.state().terminal()) {
                SiegeService.kill(overworld, v, e.rosterId, tick);
            }
            String text = "A scout of " + v.name + " did not come back";
            tellAllies(overworld, ledger, v, text, null);
            HmLog.info("Scouts: {}", text);
            return text;
        }
        if (e != null && !e.state().terminal()) {
            SiegeService.stowHome(overworld, ledger, v, e, tick);
        }
        if (!forced && r.nextDouble() >= ScoutRide.FINDS) {
            HmLog.info("Scouts: a scout of {} came back with nothing", v.name);
            return "the scout found nothing";
        }
        // 1.9: a siege being prepared comes first: he counted the enemy (the host, or the defence)
        dev.hywmill.core.HywMillRuntime rt = dev.hywmill.core.HywMillRuntime.get();
        String counted = rt == null ? null : rt.sieges().scoutSiege(overworld, ledger, v, tick, forced ? 0 : r.nextDouble());
        if (counted != null) {
            tellAllies(overworld, ledger, v, counted.substring(0, 1).toUpperCase() + counted.substring(1), null);
            HmLog.info("Scouts: {}", counted);
            return counted;
        }
        Column found = null;
        double best = Double.MAX_VALUE;
        for (Column c : ledger.columns()) {
            if (!c.onRoad() || c.revealedBy != null || c.owner.equals(v.villageId) || !hunts(ledger, v.villageId, c) || tick < c.depart) {
                continue;
            }
            double[] p = c.position(tick);
            double d = Math.hypot(p[0] - v.center.getX(), p[1] - v.center.getZ());
            if (d < best) {
                best = d;
                found = c;
            }
        }
        if (found == null && (forced || r.nextDouble() < CONVOY_INSTEAD)) {
            found = convoy(overworld, ledger, v, tick, r);
        }
        if (found == null) {
            HmLog.info("Scouts: a scout of {} came back with nothing", v.name);
            return "the scout found nothing";
        }
        found.revealedBy = v.villageId;
        found.revealedAt = tick;
        ledger.setDirty();
        intel(overworld, ledger, v, found, tick, "scouts of " + v.name + " found");
        return "found " + found.label();
    }

    /** An enemy supply convoy camped for the night, somewhere between the scout's village and an enemy. */
    @Nullable
    static Column convoy(ServerLevel overworld, GarrisonLedger ledger, VillageRecord v, long tick, SplittableRandom r) {
        List<VillageRecord> enemies = new ArrayList<>();
        for (WarRecord w : ledger.wars().values()) {
            if (w.atWar() && (w.a.equals(v.villageId) || w.b.equals(v.villageId))) {
                VillageRecord o = ledger.get(w.a.equals(v.villageId) ? w.b : w.a);
                if (o != null) {
                    enemies.add(o);
                }
            }
        }
        if (enemies.isEmpty()) {
            return null;
        }
        VillageRecord en = enemies.get(r.nextInt(enemies.size()));
        double f = 0.35 + r.nextDouble() * 0.3;
        double dx = v.center.getX() - en.center.getX(), dz = v.center.getZ() - en.center.getZ(), dl = Math.max(1, Math.hypot(dx, dz));
        double off = (r.nextDouble() - 0.5) * 300;
        int x = (int) (en.center.getX() + dx * f - dz / dl * off), z = (int) (en.center.getZ() + dz * f + dx / dl * off);
        // 1.10: where a road leaves the enemy village towards the scout's, the convoy camps by it
        dev.hywmill.politics.war.Route road = dev.hywmill.integration.roads.VillageRoads.route(overworld, en.center.getX(), en.center.getZ(),
                v.center.getX(), v.center.getZ());
        double[] by = road.roadPoint(0.2 + r.nextDouble() * 0.4);
        if (by != null) {
            double[] h = road.headingNear(by[0], by[1]);
            x = (int) Math.round(by[0] - h[1] * 9);
            z = (int) Math.round(by[1] + h[0] * 9);
        }
        Column c = new Column(id(en.villageId + ">convoy>" + tick), Column.Kind.CONVOY, null, en.villageId, null, x, z, x, z, tick, tick + CAMP);
        if (by != null) {
            c.road(new int[]{x, z, (int) by[0], (int) by[1], x, z}); // remembered as camped by the road (it does not move)
        }
        c.theme = ConvoyLoot.THEMES.get(r.nextInt(ConvoyLoot.THEMES.size()));
        List<String> pool = new ArrayList<>();
        GarrisonTables tables = GarrisonTables.current();
        dev.hywmill.garrison.Recruitment.eligibleUnits(en.tier, tables.forCulture(en.culture), tables.units()).forEach(u -> {
            if (u.unitClass() != UnitClass.CAVALRY) {
                pool.add(u.key());
            }
        });
        MobilizationService.rule(en).levyUnits().keySet().forEach(pool::add);
        if (pool.isEmpty()) {
            pool.add("spear_man");
        }
        int n = 4 + r.nextInt(5);
        for (int i = 0; i < n; i++) {
            c.units.add(pool.get(r.nextInt(pool.size())));
            c.regular.add(r.nextDouble() < 0.4);
        }
        c.survivors = n;
        ledger.columns().add(c);
        return c;
    }

    // ------------------------------------------------------------------ news

    /** Intel: players on campaign with {@code v} and near it hear where the column is, from where they stand. */
    private static void intel(ServerLevel overworld, GarrisonLedger ledger, VillageRecord v, Column c, long tick, String how) {
        double[] p = c.position(tick);
        String owner = name(ledger, c.owner);
        String dest = (c.destination != null && c.kind != Column.Kind.CONVOY ? " riding for " + name(ledger, c.destination) : "")
                + (!c.route().roads() ? "" : c.kind == Column.Kind.CONVOY ? " camped by the road" : " by the road");
        for (ServerPlayer pl : recipients(overworld, ledger, v)) {
            pl.sendSystemMessage(Component.literal("[Scouts] " + capital(how) + " " + c.label() + " of " + owner + dest + " at " + (int) p[0] + ", "
                    + (int) p[1] + ": " + Column.where(pl.getX(), pl.getZ(), p[0], p[1]) + " of you" + eta(c, tick)
                    + ". See the War tab of the Politics screen.").withStyle(ChatFormatting.GOLD));
        }
        HmLog.info("Scouts: {} {} of {} at {}, {}", how, c.label(), owner, (int) p[0], (int) p[1]);
    }

    private static void tellAllies(ServerLevel overworld, GarrisonLedger ledger, VillageRecord v, String text, @Nullable ChatFormatting f) {
        for (ServerPlayer pl : recipients(overworld, ledger, v)) {
            pl.sendSystemMessage(Component.literal("[Scouts] " + text).withStyle(f == null ? ChatFormatting.GRAY : f));
        }
    }

    /** Tells the players on campaign for or against the column's village and those near it. */
    private static void tell(ServerLevel overworld, GarrisonLedger ledger, Column c, String text) {
        Set<UUID> to = new HashSet<>();
        for (Campaign camp : ledger.campaigns()) {
            if (camp.ally().equals(c.owner) || camp.enemy().equals(c.owner) || (c.revealedBy != null && camp.ally().equals(c.revealedBy))) {
                to.add(camp.player());
            }
        }
        if (c.takenBy != null) {
            to.add(c.takenBy);
        }
        double[] p = c.position(overworld.getGameTime());
        for (ServerPlayer pl : overworld.players()) {
            if (to.contains(pl.getUUID()) || Math.hypot(pl.getX() - p[0], pl.getZ() - p[1]) < HIDE) {
                pl.sendSystemMessage(Component.literal("[Road] " + text).withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    private static List<ServerPlayer> recipients(ServerLevel overworld, GarrisonLedger ledger, VillageRecord v) {
        Set<UUID> to = new HashSet<>();
        for (Campaign camp : ledger.campaigns()) {
            if (camp.ally().equals(v.villageId)) {
                to.add(camp.player());
            }
        }
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer pl : overworld.players()) {
            if (to.contains(pl.getUUID()) || pl.position().distanceToSqr(Vec3.atCenterOf(v.center)) < SiegeService.NEWS_RANGE * SiegeService.NEWS_RANGE) {
                out.add(pl);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    static Siege siegeOf(GarrisonLedger ledger, Column c) {
        if (c.siege == null) {
            return null;
        }
        for (Siege s : ledger.sieges()) {
            if (s.id.equals(c.siege)) {
                return s;
            }
        }
        return null;
    }

    /** Admin recall: every siege column leaves the road (its men leave the world). */
    static void dropSiegeColumns(ServerLevel overworld, GarrisonLedger ledger, long tick) {
        for (Column c : ledger.columns()) {
            if (c.siege != null && c.onRoad()) {
                for (UUID id : c.materialized) {
                    Entity e = overworld.getEntity(id);
                    if (e != null) {
                        e.discard();
                    }
                }
                c.materialized.clear();
                c.state = Column.State.FAILED;
                c.outcome = "recalled";
                c.arrive = tick;
            }
        }
        ledger.setDirty();
    }

    /** The culture's light horseman (scouts, messengers). */
    static String lightRider(VillageRecord v) {
        GarrisonTable table = GarrisonTables.current().forCulture(v.culture);
        return table.composition().containsKey("archer_rider") ? "archer_rider" : "light_lancer_rider";
    }

    static int levyLevel(VillageRecord v) {
        GarrisonTables tables = GarrisonTables.current();
        PoliticsTables.MobilizationRule mob = MobilizationService.rule(v);
        return dev.hywmill.garrison.Mobilization.equipmentLevel(dev.hywmill.garrison.Recruitment.equipmentLevel(v.tier, tables.forCulture(v.culture)),
                mob.equipmentFloor(), mob.equipmentDrop());
    }

    private static String name(GarrisonLedger ledger, @Nullable UUID v) {
        VillageRecord r = v == null ? null : ledger.get(v);
        return r == null ? "?" : r.name;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** 1.10: the column takes the roads when they serve its way (Village Roads); how long its journey takes at {@code speed}. */
    static long travel(ServerLevel overworld, Column c, double speed) {
        dev.hywmill.politics.war.Route r = dev.hywmill.integration.roads.VillageRoads.route(overworld, c.fromX, c.fromZ, c.toX, c.toZ);
        c.road(r.saved());
        return (long) (r.time() / speed);
    }

    static UUID id(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /** Admin: puts a column on the road now (tests). */
    public static Column spawn(ServerLevel overworld, GarrisonLedger ledger, Column.Kind kind, VillageRecord owner, VillageRecord to, long tick, boolean reveal,
                               @Nullable UUID revealer) {
        Siege s = SiegeService.against(ledger, to.villageId);
        if (s == null) {
            s = SiegeService.byAttacker(ledger, to.villageId);
        }
        SplittableRandom r = new SplittableRandom(tick);
        Column c;
        if (kind == Column.Kind.CONVOY) {
            VillageRecord scout = revealer != null && ledger.get(revealer) != null ? ledger.get(revealer) : to;
            c = convoy(overworld, ledger, scout, tick, r);
            if (c == null) {
                c = new Column(id(owner.villageId + ">convoy>" + tick), Column.Kind.CONVOY, null, owner.villageId, null, to.center.getX() + 40,
                        to.center.getZ() + 40, to.center.getX() + 40, to.center.getZ() + 40, tick, tick + CAMP);
                c.theme = ConvoyLoot.THEMES.get(r.nextInt(ConvoyLoot.THEMES.size()));
                for (int i = 0; i < 5; i++) {
                    c.units.add("spear_man");
                    c.regular.add(false);
                }
                c.survivors = 5;
                ledger.columns().add(c);
            }
        } else {
            double d = Math.sqrt(owner.center.distSqr(to.center));
            if (d < 50) {
                d = 300;
            }
            int fx = kind == Column.Kind.MERCS ? to.center.getX() + 300 : owner.center.getX();
            int fz = kind == Column.Kind.MERCS ? to.center.getZ() : owner.center.getZ();
            double speed = kind == Column.Kind.MESSENGER || kind == Column.Kind.ALARM ? RIDER_SPEED : SPEED;
            c = new Column(id(owner.villageId + ">" + kind + ">" + tick), kind, s == null ? null : s.id, kind == Column.Kind.MERCS ? to.villageId : owner.villageId,
                    to.villageId, fx, fz, to.center.getX(), to.center.getZ(), tick, tick + (long) (Math.hypot(to.center.getX() - fx, to.center.getZ() - fz) / speed));
            c.arrive = tick + Math.max(1, travel(overworld, c, speed));
            if (kind == Column.Kind.MERCS) {
                Mercenaries.Hire h = Mercenaries.hire(tick);
                c.units.addAll(h.units());
                for (int i = 0; i < h.units().size(); i++) {
                    c.regular.add(false);
                }
                c.look = h.company().look();
                c.name = h.company().name();
            } else if (kind == Column.Kind.MESSENGER) {
                c.helper = to.villageId;
                c.units.add(lightRider(owner));
                c.regular.add(true);
            } else {
                for (int i = 0; i < (kind == Column.Kind.ALARM ? 2 : 10); i++) {
                    c.units.add(kind == Column.Kind.ALARM ? lightRider(owner) : "spear_man");
                    c.regular.add(i % 3 == 0);
                }
            }
            c.survivors = c.units.size();
            ledger.columns().add(c);
        }
        if (reveal) {
            c.revealedBy = revealer != null ? revealer : to.villageId;
            c.revealedAt = tick;
        }
        ledger.setDirty();
        return c;
    }

    /** Admin: brings a column into the world at once, at a player's side (tests). */
    public static boolean forceMaterialize(ServerLevel overworld, GarrisonLedger ledger, Column c, ServerPlayer near, long tick) {
        VillageRecord owner = ledger.get(c.owner);
        if (owner == null || !c.materialized.isEmpty() || !c.onRoad()) {
            return false;
        }
        double[] p = {near.getX() + 24, near.getZ()};
        Column moved = new Column(c.id, c.kind, c.siege, c.owner, c.destination, (int) p[0], (int) p[1],
                c.kind == Column.Kind.CONVOY ? (int) p[0] : c.toX, c.kind == Column.Kind.CONVOY ? (int) p[1] : c.toZ, tick, Math.max(c.arrive, tick + 1200));
        copy(c, moved);
        ledger.columns().remove(c);
        ledger.columns().add(moved);
        materialize(overworld, ledger, moved, owner, moved.position(tick), tick);
        return !moved.materialized.isEmpty();
    }

    /** Keeps an unused import list honest for the item lookup used by {@link ConvoyLoot}. */
    static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).orElse(null);
    }

    static ItemStack stack(String id, int n) {
        Item it = item(id);
        return it == null ? ItemStack.EMPTY : new ItemStack(it, Math.max(1, Math.min(n, it.getDefaultMaxStackSize())));
    }

    static MilitaryTier gearTier(VillageRecord v) {
        return v.tier;
    }
}

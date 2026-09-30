package dev.hywmill.recruit;

import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.equip.EquipmentProfiles;
import dev.hywmill.garrison.service.DutyService;
import dev.hywmill.garrison.spi.EquipmentProvider;
import dev.hywmill.garrison.spi.SpawnRequest;
import dev.hywmill.garrison.spi.SpawnResult;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.garrison.tables.GarrisonTable;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.net.RecruitPayloads;
import dev.hywmill.politics.PoliticsRecord;
import dev.hywmill.politics.Standing;
import dev.hywmill.politics.api.PoliticsView;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Muster Roll recruitment (post-M5). A player hires soldiers of the village the block stands in, paid in Millénaire money
 * ({@link SettlementSource#takeMoney}); what is offered and its price depend on the player's standing there
 * ({@link RecruitOffers}). Hired units are owned by the player (HYW owner), equipped as that village's troops (culture
 * kits, livery, heraldry, at the offer's gear tier) and are not garrison units: no roster entry, no garrison tag.
 * Reputation, Favor and grievance are untouched. Every intent is re-validated here.
 */
public final class RecruitService {
    private RecruitService() {}

    public record Outcome(boolean ok, String message, int hired) {}

    /** The ledger record of the village whose bounds contain {@code pos} (nearest centre wins), or null. */
    @Nullable
    public static VillageRecord villageAt(ServerLevel overworld, BlockPos pos) {
        VillageRecord best = null;
        double bestD = Double.MAX_VALUE;
        for (VillageRecord rec : GarrisonLedger.get(overworld).all()) {
            double dx = rec.center.getX() - pos.getX(), dz = rec.center.getZ() - pos.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            int radius = rec.villageRadius > 0 ? rec.villageRadius : 48;
            if (d <= radius && d < bestD) {
                best = rec;
                bestD = d;
            }
        }
        return best;
    }

    public static void onPlaced(ServerPlayer player, BlockPos pos, MusterRollBlockEntity be) {
        ServerLevel level = player.serverLevel();
        VillageRecord rec = level == level.getServer().overworld() ? villageAt(level, pos) : null;
        be.bind(rec == null ? null : rec.villageId);
        player.displayClientMessage(Component.literal(rec == null ? "[Muster Roll] Not within a village: it offers nothing here."
                : "[Muster Roll] Bound to " + rec.name + ". Its soldiers can be hired here."), false);
    }

    /** Standing of {@code player} with the village, as the Politics screen shows it (word travels, campaigns included). */
    public static Standing standing(ServerLevel overworld, VillageRecord rec, UUID player) {
        SettlementSource source = Services.settlements();
        PoliticsRecord r = rec.politics.peek(player);
        Standing own = r == null ? Standing.STRANGER : r.status;
        return source == null ? own : PoliticsView.effective(overworld, GarrisonLedger.get(overworld), source, rec, player, own, null);
    }

    @Nullable
    private static MusterRollBlockEntity roll(ServerPlayer player, BlockPos pos) {
        RecruitTables t = RecruitTables.current();
        if (!(player.level().getBlockEntity(pos) instanceof MusterRollBlockEntity be)
                || player.distanceToSqr(Vec3.atCenterOf(pos)) > t.useRange() * t.useRange()) {
            return null;
        }
        return be;
    }

    @Nullable
    private static VillageRecord village(ServerLevel overworld, MusterRollBlockEntity be, BlockPos pos) {
        if (be.village() == null) {
            VillageRecord rec = villageAt(overworld, pos); // a village discovered after placement
            if (rec != null) {
                be.bind(rec.villageId);
            }
            return rec;
        }
        return GarrisonLedger.get(overworld).get(be.village());
    }

    public static List<RecruitOffers.Offer> offers(ServerLevel overworld, VillageRecord rec, UUID player) {
        GarrisonTables gt = GarrisonTables.current();
        return RecruitOffers.offers(standing(overworld, rec, player), rec.tier, gt.forCulture(rec.culture), gt.units(), RecruitTables.current());
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        RecruitPayloads.View v = view(player, pos);
        if (v != null) {
            PacketDistributor.sendToPlayer(player, v);
            PacketDistributor.sendToPlayer(player, new RecruitPayloads.Squads(pos, squadViews(player, pos)));
        }
    }

    /** Post-M5: the village's culture squads as this player sees them (each with its price, or why it cannot be hired). */
    static List<RecruitPayloads.SquadView> squadViews(ServerPlayer player, BlockPos pos) {
        MusterRollBlockEntity be = roll(player, pos);
        ServerLevel overworld = player.getServer().overworld();
        VillageRecord rec = be == null || player.level() != overworld ? null : village(overworld, be, pos);
        List<RecruitPayloads.SquadView> out = new ArrayList<>();
        if (rec == null) {
            return out;
        }
        Standing st = standing(overworld, rec, player.getUUID());
        Squads sq = Squads.current();
        for (Squads.Squad s : sq.forCulture(rec.culture)) {
            String why = Squads.refusal(s, st, rec.tier);
            out.add(new RecruitPayloads.SquadView(s.id(), s.name(), s.category().name(), s.quality().name(), s.roster(),
                    s.topGear().name(), s.size(), sq.price(s, GarrisonTables.current().units(), RecruitTables.current(), st), why == null ? "" : why,
                    s.description()));
        }
        return out;
    }

    @Nullable
    static RecruitPayloads.View view(ServerPlayer player, BlockPos pos) {
        MusterRollBlockEntity be = roll(player, pos);
        if (be == null) {
            return null;
        }
        ServerLevel overworld = player.getServer().overworld();
        SettlementSource source = Services.settlements();
        int money = source == null ? 0 : Math.max(0, source.playerMoney(player));
        boolean owner = player.getUUID().equals(be.owner());
        VillageRecord rec = player.level() == overworld ? village(overworld, be, pos) : null;
        if (rec == null) {
            return new RecruitPayloads.View(pos, "Not within a village: this Muster Roll offers nothing here.", money, be.radius(), owner, List.of());
        }
        Standing st = standing(overworld, rec, player.getUUID());
        MilitaryTier gear = RecruitOffers.gearTier(st, rec.tier, RecruitTables.current());
        String header = rec.name + " (" + rec.culture.replace("millenaire:", "") + ", " + rec.tier + ") | your standing: " + st.name().toLowerCase()
                + (RecruitOffers.refused(st) ? " | they will not hire out soldiers to you"
                : gear == MilitaryTier.NONE ? " | mercenaries only" : " | soldiers up to " + gear + " gear");
        List<RecruitPayloads.OfferView> list = new ArrayList<>();
        for (RecruitOffers.Offer o : offers(overworld, rec, player.getUUID())) {
            list.add(new RecruitPayloads.OfferView(o.key(), o.label(), o.gearTier().name(), o.price()));
        }
        return new RecruitPayloads.View(pos, header, money, be.radius(), owner, list);
    }

    /** Hires {@code count} of offer {@code key}; answers the player and refreshes the screen. */
    public static Outcome hire(ServerPlayer player, BlockPos pos, String key, int count) {
        Outcome o = doHire(player, player, pos, key, count);
        PacketDistributor.sendToPlayer(player, new RecruitPayloads.Result(o.ok(), o.message()));
        open(player, pos);
        return o;
    }

    /**
     * The hire itself. {@code payer} holds the money and becomes the owner ({@code player} too, except for the dev
     * command's stand-in); everything is re-validated.
     */
    public static Outcome doHire(ServerPlayer player, Player payer, BlockPos pos, String key, int count) {
        RecruitTables t = RecruitTables.current();
        MusterRollBlockEntity be = roll(player, pos);
        if (be == null) {
            return new Outcome(false, "Too far from the Muster Roll.", 0);
        }
        ServerLevel overworld = player.getServer().overworld();
        VillageRecord rec = player.level() == overworld ? village(overworld, be, pos) : null;
        SettlementSource source = Services.settlements();
        UnitProvider units = Services.units();
        if (rec == null || source == null || units == null) {
            return new Outcome(false, "This Muster Roll is not within a village.", 0);
        }
        if (key.startsWith("squad:")) {
            return hireSquad(overworld, payer, pos, be, rec, source, units, key.substring("squad:".length()));
        }
        RecruitOffers.Offer offer = offers(overworld, rec, payer.getUUID()).stream().filter(x -> x.key().equals(key)).findFirst().orElse(null);
        if (offer == null) {
            Standing st = standing(overworld, rec, payer.getUUID());
            return new Outcome(false, RecruitOffers.refused(st) ? rec.name + " will not hire out soldiers to you (" + st.name().toLowerCase() + ")."
                    : "That is not offered to you here.", 0);
        }
        int n = Math.max(1, Math.min(offer.engine() ? t.maxEngines() : t.maxPerPurchase(), count));
        long total = (long) offer.price() * n;
        int have = source.playerMoney(payer);
        if (have < total) {
            return new Outcome(false, "Not enough money: " + n + " × " + offer.label() + " costs " + RecruitOffers.money(total) + ", you have "
                    + RecruitOffers.money(Math.max(0, have)) + ".", 0);
        }
        if (!source.takeMoney(payer, (int) total)) {
            return new Outcome(false, "The payment could not be taken.", 0);
        }
        GarrisonTable table = GarrisonTables.current().forCulture(rec.culture);
        EquipmentProvider eq = Services.equipment(table.equipmentProvider());
        if (eq == null) {
            eq = Services.equipment("hyw");
        }
        int level = table.tier(offer.gearTier()).equipmentLevel();
        if (offer.engine()) {
            eq = Services.equipment("hyw"); // engines and engineers: HYW's own equipment, no profile overlay
            level = 0;
        }
        RandomSource rnd = overworld.getRandom();
        int hired = 0;
        for (int i = 0; i < n; i++) {
            BlockPos spot = spot(overworld, pos, be.radius(), rnd);
            if (spot == null || eq == null) {
                continue;
            }
            if (offer.engine()) {
                if (spawnEngine(overworld, units, eq, rec, offer, payer.getUUID(), spot)) {
                    hired++;
                }
                continue;
            }
            UUID id = UUID.randomUUID();
            SpawnResult r = units.spawn(overworld, new SpawnRequest(offer.unit(), payer.getUUID(), id, Vec3.atBottomCenterOf(spot), spot, level,
                    false, null, eq, new EquipmentProvider.Context(rec.culture, offer.gearTier(), "",
                    EquipmentProfiles.classRole(offer.unit().unitClass()), id)));
            if (r.ok()) {
                hired++;
            }
        }
        int refund = (n - hired) * offer.price();
        if (refund > 0) {
            source.giveMoney(payer, refund);
        }
        HmLog.info("Muster Roll: {} hired {} × {} ({} gear) from '{}' for {} deniers{}", payer.getName().getString(), hired, offer.unit().key(),
                offer.gearTier(), rec.name, (long) hired * offer.price(), refund > 0 ? " (" + (n - hired) + " not placed, refunded)" : "");
        if (hired == 0) {
            return new Outcome(false, "No safe ground within " + be.radius() + " blocks to place them; you were refunded.", 0);
        }
        return new Outcome(true, "Hired " + hired + " × " + offer.label() + " for " + RecruitOffers.money((long) hired * offer.price())
                + (refund > 0 ? "; " + (n - hired) + " could not be placed and were refunded" : "") + ".", hired);
    }

    /**
     * Post-M5: hires one whole culture squad. Every member is the player's own unit, equipped as this village's troops at the
     * member's gear tier (and kit: {@code levy} for light kits); they muster together round the block.
     */
    private static Outcome hireSquad(ServerLevel overworld, Player payer, BlockPos pos, MusterRollBlockEntity be, VillageRecord rec,
                                     SettlementSource source, UnitProvider units, String id) {
        Squads sq = Squads.current();
        Squads.Squad s = sq.find(rec.culture, id);
        if (s == null) {
            return new Outcome(false, "That squad is not raised here.", 0);
        }
        Standing st = standing(overworld, rec, payer.getUUID());
        String why = Squads.refusal(s, st, rec.tier);
        if (why != null) {
            return new Outcome(false, s.name() + ": " + why + ".", 0);
        }
        GarrisonTables gt = GarrisonTables.current();
        int price = sq.price(s, gt.units(), RecruitTables.current(), st);
        int have = source.playerMoney(payer);
        if (have < price) {
            return new Outcome(false, "Not enough money: " + s.name() + " costs " + RecruitOffers.money(price) + ", you have "
                    + RecruitOffers.money(Math.max(0, have)) + ".", 0);
        }
        if (!source.takeMoney(payer, price)) {
            return new Outcome(false, "The payment could not be taken.", 0);
        }
        GarrisonTable table = gt.forCulture(rec.culture);
        EquipmentProvider eq = Services.equipment(table.equipmentProvider());
        if (eq == null) {
            eq = Services.equipment("hyw");
        }
        RandomSource rnd = overworld.getRandom();
        int hired = 0;
        int[] livery = s.villageLivery() ? dev.hywmill.garrison.service.LiveryService.of(overworld, rec) : null; // generic squads: the village's colours
        for (Squads.Member m : s.members()) {
            dev.hywmill.garrison.tables.UnitSpec spec = gt.units().get(m.unit());
            int level = table.tier(m.gear()).equipmentLevel();
            for (int i = 0; i < m.count(); i++) {
                BlockPos spot = spot(overworld, pos, be.radius(), rnd);
                if (spot == null || eq == null || spec == null) {
                    continue;
                }
                UUID uid = UUID.randomUUID();
                SpawnResult r = units.spawn(overworld, new SpawnRequest(spec, payer.getUUID(), uid, Vec3.atBottomCenterOf(spot), spot, level, false,
                        null, eq, new EquipmentProvider.Context(rec.culture, m.gear(), s.role(m), EquipmentProfiles.classRole(spec.unitClass()), uid)
                        .withLivery(livery)));
                if (r.ok()) {
                    hired++;
                }
            }
        }
        int size = s.size();
        int refund = hired == size ? 0 : (int) Math.round((double) price * (size - hired) / size);
        if (refund > 0) {
            source.giveMoney(payer, refund);
        }
        HmLog.info("Muster Roll: {} hired squad {} ({} of {}) from '{}' for {} deniers{}", payer.getName().getString(), s.id(), hired, size, rec.name,
                price - refund, refund > 0 ? " (" + (size - hired) + " not placed, refunded)" : "");
        if (hired == 0) {
            return new Outcome(false, "No safe ground within " + be.radius() + " blocks to muster them; you were refunded.", 0);
        }
        return new Outcome(true, s.name() + " (" + hired + " soldiers) mustered for " + RecruitOffers.money(price - refund)
                + (refund > 0 ? "; " + (size - hired) + " could not be placed and were refunded" : "") + ".", hired);
    }

    /** Post-M5: a bought siege engine, owned by the player; a crewed one comes with an engineer who mounts it himself. */
    private static boolean spawnEngine(ServerLevel overworld, UnitProvider units, EquipmentProvider eq, VillageRecord rec, RecruitOffers.Offer offer,
                                       UUID owner, BlockPos spot) {
        UUID id = UUID.randomUUID();
        SpawnResult r = units.spawn(overworld, new SpawnRequest(offer.unit(), owner, id, Vec3.atBottomCenterOf(spot), spot, 0, false, null, eq, null));
        if (!r.ok()) {
            return false;
        }
        if (offer.crewed()) {
            dev.hywmill.garrison.tables.UnitSpec eng = new dev.hywmill.garrison.tables.UnitSpec("siege_engineer", "hundred_years_war:siege_engineer",
                    dev.hywmill.garrison.tables.UnitClass.LEVY, 1, MilitaryTier.WATCH, true);
            UUID eid = UUID.randomUUID();
            SpawnResult er = units.spawn(overworld, new SpawnRequest(eng, owner, eid, Vec3.atBottomCenterOf(spot.offset(2, 0, 0)), spot, 0, false, null, eq, null));
            if (!er.ok()) {
                HmLog.warn("Muster Roll: the engineer for a {} could not be placed", offer.unit().key());
            }
        }
        return true;
    }

    public static Outcome setRadius(ServerPlayer player, BlockPos pos, int radius) {
        RecruitTables t = RecruitTables.current();
        MusterRollBlockEntity be = roll(player, pos);
        Outcome o;
        if (be == null) {
            o = new Outcome(false, "Too far from the Muster Roll.", 0);
        } else if (!player.getUUID().equals(be.owner())) {
            o = new Outcome(false, "Only the player who placed this Muster Roll can change its radius.", 0);
        } else {
            be.setRadius(Math.max(t.minRadius(), Math.min(t.maxRadius(), radius)));
            o = new Outcome(true, "Recruits will muster within " + be.radius() + " blocks.", 0);
        }
        PacketDistributor.sendToPlayer(player, new RecruitPayloads.Result(o.ok(), o.message()));
        open(player, pos);
        return o;
    }

    private static final int[] DY = {0, 1, -1, 2, -2, 3, -3};

    /** Safe, loaded, standable ground within {@code radius} of the block (near its height first, then the surface); null if none. */
    @Nullable
    static BlockPos spot(ServerLevel level, BlockPos center, int radius, RandomSource rnd) {
        for (int i = 0; i < 32; i++) {
            int dx = rnd.nextInt(2 * radius + 1) - radius, dz = rnd.nextInt(2 * radius + 1) - radius;
            if ((dx == 0 && dz == 0) || dx * dx + dz * dz > radius * radius) {
                continue;
            }
            for (int dy : DY) {
                BlockPos f = center.offset(dx, dy, dz);
                if (level.isPositionEntityTicking(f) && DutyService.standable(level, f)) {
                    return f;
                }
            }
        }
        for (int i = 0; i < 16; i++) {
            int dx = rnd.nextInt(2 * radius + 1) - radius, dz = rnd.nextInt(2 * radius + 1) - radius;
            BlockPos col = center.offset(dx, 0, dz);
            if (!level.isPositionEntityTicking(col)) {
                continue;
            }
            BlockPos top = new BlockPos(col.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ()), col.getZ());
            if (Math.abs(top.getY() - center.getY()) <= 12 && DutyService.standable(level, top)) {
                return top;
            }
        }
        return null;
    }
}

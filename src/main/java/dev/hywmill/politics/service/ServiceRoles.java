package dev.hywmill.politics.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.HywMillRuntime;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.service.SiegeService;
import dev.hywmill.politics.PoliticsTables;
import dev.hywmill.politics.Standing;
import dev.hywmill.politics.realm.Officials;
import dev.hywmill.politics.realm.RealmPlan;
import dev.hywmill.politics.realm.Rulers;
import dev.hywmill.politics.realm.Service;
import dev.hywmill.politics.war.Campaign;
import dev.hywmill.politics.war.Siege;
import dev.hywmill.recruit.RecruitOffers;
import dev.hywmill.recruit.RecruitService;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.PoliticsNbt;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Players in a realm's service (docs/realm-design.md §11). A volunteer is called to the realm's fights and comes if he
 * will; a mercenary signs a contract (a starting purse and company, once per realm), is paid each dawn while the realm is at
 * war and for each order answered; a knight is sworn into the realm: a stipend each dawn, orders from its ruler shown on his
 * HUD (where to muster before an assault, which side of a besieged village to hold), a voice in its council, and its
 * officials' judgement: a knight who ignores too many orders, or whom they despise, is banished and exiled.
 */
public final class ServiceRoles {
    private ServiceRoles() {}

    public static final long DAY = PoliticsTables.DAY;
    /** Orders are watched this often (ticks). */
    public static final int INTERVAL = 20;

    public record Result(boolean ok, String message) {}

    public static boolean enabled() {
        return RealmService.enabled() && HywMillConfig.REALM_SERVICE.get();
    }

    static String key(UUID player, UUID head) {
        return player + "|" + head;
    }

    /** The officials of the realm whose capital is {@code head} (raised the first time they are needed). */
    public static List<Officials.Official> officials(GarrisonLedger ledger, VillageRecord head) {
        return ledger.officials().computeIfAbsent(head.villageId, k -> Officials.raise(head.villageId, head.culture));
    }

    static String ruler(VillageRecord head) {
        return "the " + Rulers.title(head.culture) + " of " + head.name;
    }

    // ------------------------------------------------------------------ joining and leaving

    /** {@code player} enters the service of the realm {@code at} belongs to, as {@code role}. */
    public static Result enlist(ServerLevel ow, ServerPlayer player, VillageRecord at, Service.Role role) {
        if (!enabled()) {
            return new Result(false, "Realm service is off on this server.");
        }
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        long now = ow.getGameTime();
        VillageRecord head = ledger.get(RealmService.sovereignOf(ledger, at.villageId, now));
        if (head == null || head.loneBuilding) {
            return new Result(false, "There is no realm to serve here.");
        }
        UUID me = player.getUUID();
        Long exiled = ledger.exiles().get(key(me, head.villageId));
        if (exiled != null && exiled > now) {
            return new Result(false, "The realm of " + head.name + " will not have you back for another " + Math.max(1, (exiled - now) / DAY) + " day(s).");
        }
        Standing st = RecruitService.standing(ow, head, me);
        if (st == Standing.OUTLAW || st == Standing.UNWELCOME) {
            return new Result(false, head.name + " will not take you into its service (" + st.name().toLowerCase() + ").");
        }
        if (role == Service.Role.KNIGHT && st.ordinal() < Standing.PATRON.ordinal()) {
            return new Result(false, "Only a patron or one sworn to " + head.name + " may be made " + Service.knightTitle(head.culture)
                    + " (you are " + st.name().toLowerCase() + ").");
        }
        List<UUID> realm = RealmService.members(ledger, head.villageId, now);
        boolean atWar = !enemies(ledger, realm).isEmpty();
        if (role == Service.Role.MERCENARY && !atWar) {
            return new Result(false, "The realm of " + head.name + " is at peace: it hires no swords now.");
        }
        Service old = ledger.services().get(me);
        if (old != null && !old.realm.equals(head.villageId)) {
            VillageRecord o = ledger.get(old.realm);
            return new Result(false, "You already serve " + (o == null ? "another realm" : "the realm of " + o.name) + ": leave its service first.");
        }
        Service s = old != null ? old : new Service(me);
        boolean renewal = old != null && old.role == role;
        s.role = role;
        s.realm = head.villageId;
        if (old == null) {
            s.since = now;
        }
        s.until = role == Service.Role.MERCENARY ? now + Service.CONTRACT_DAYS * DAY : Long.MAX_VALUE;
        ledger.services().put(me, s);
        StringBuilder msg = new StringBuilder();
        SettlementSource source = Services.settlements();
        switch (role) {
            case VOLUNTEER -> msg.append("You volunteer for the realm of ").append(head.name)
                    .append(": you will be called to its fights, and come if you will. Your calls appear at the left of your screen.");
            case MERCENARY -> {
                msg.append(renewal ? "Your contract with " : "You sign on with ").append("the realm of ").append(head.name).append(" for ")
                        .append(Service.CONTRACT_DAYS).append(" days: ").append(RecruitOffers.money(Service.wage(role, realm.size(), true)))
                        .append(" each dawn while it is at war, and more for each order you answer.");
                String k = key(me, head.villageId);
                if (!ledger.bonuses().contains(k)) {
                    ledger.bonuses().add(k);
                    if (source != null) {
                        source.giveMoney(player, Service.SIGN_BONUS);
                    }
                    int men = RecruitService.grant(player, head, Service.COMPANY);
                    msg.append(" Signing purse: ").append(RecruitOffers.money(Service.SIGN_BONUS)).append(men > 0 ? ", and " + men + " men of "
                            + head.name + " to lead (yours to keep)." : ".").append(" (Once only: it is not paid again by this realm.)");
                }
            }
            case KNIGHT -> {
                msg.append("You are made ").append(Service.knightTitle(head.culture)).append(" of the realm of ").append(head.name).append(": ")
                        .append(RecruitOffers.money(Service.wage(role, realm.size(), atWar))).append(" each dawn; you will receive the orders of ")
                        .append(ruler(head)).append(" at the left of your screen, and may counsel its council.");
                officials(ledger, head).forEach(o -> o.change(me, 10));
                if (source != null && old == null) {
                    PoliticsService.chronicle(ow, source, head, now, player.getName().getString() + " is made " + Service.knightTitle(head.culture)
                            + " of the realm of " + head.name);
                }
            }
        }
        s.note("Day " + now / DAY + ": you entered the service of " + head.name + " as " + Service.label(role, head.culture).toLowerCase());
        HmLog.info("Service: {} enters the service of '{}' as {}", player.getName().getString(), head.name, role);
        ledger.setDirty();
        return new Result(true, msg.toString());
    }

    /** {@code player} leaves the realm's service; a mercenary who breaks his contract early may not sign with it again for a while. */
    public static Result leave(ServerLevel ow, UUID player) {
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        Service s = ledger.services().get(player);
        if (s == null) {
            return new Result(false, "You serve no realm.");
        }
        long now = ow.getGameTime();
        VillageRecord head = ledger.get(s.realm);
        String name = head == null ? "the realm" : "the realm of " + head.name;
        String extra = "";
        if (s.role == Service.Role.MERCENARY && s.until > now) {
            ledger.exiles().put(key(player, s.realm), now + Service.BROKEN_DAYS * DAY);
            extra = " You broke your contract: " + name + " will not hire you again for " + Service.BROKEN_DAYS + " days.";
        }
        end(ow, ledger, s, now);
        return new Result(true, "You leave the service of " + name + "." + extra);
    }

    static void end(ServerLevel ow, GarrisonLedger ledger, Service s, long now) {
        ledger.services().remove(s.player);
        Campaign c = RelationProjector.campaignOf(ledger, s.player);
        HywMillRuntime rt = HywMillRuntime.get();
        if (c != null && rt != null && RealmService.realmOf(ledger, s.realm, now).contains(c.ally())) {
            rt.relations().leave(ow, rt, s.player);
        }
        ledger.setDirty();
    }

    static List<UUID> enemies(GarrisonLedger ledger, List<UUID> realm) {
        List<UUID> out = new ArrayList<>();
        for (VillageRecord e : ledger.all()) {
            if (!realm.contains(e.villageId)) {
                for (UUID m : realm) {
                    if (RelationProjector.atWar(ledger, m, e.villageId)) {
                        out.add(e.villageId);
                        break;
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ orders

    /**
     * A siege was launched: those in the service of the attacker's realm are ordered to its rally point (the side of the
     * target the host comes from); those serving the target's realm, to hold that side against it.
     */
    public static void onSiegeLaunched(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long now) {
        if (!enabled() || ledger.services().isEmpty()) {
            return;
        }
        UUID attackers = RealmService.sovereignOf(ledger, a.villageId, now), defenders = RealmService.sovereignOf(ledger, t.villageId, now);
        BlockPos rally = SiegeService.rallyPoint(a, t);
        String side = dev.hywmill.net.PoliticsNet.bearing(rally.getX() - t.center.getX(), rally.getZ() - t.center.getZ());
        HywMillRuntime rt = HywMillRuntime.get();
        for (Service sv : ledger.services().values()) {
            boolean attack = sv.realm.equals(attackers), defend = sv.realm.equals(defenders);
            if (!attack && !defend) {
                continue;
            }
            sv.orders.removeIf(o -> o.siege.equals(s.id));
            sv.orders.add(new Service.Order(s.id, t.villageId, defend, rally.getX(), rally.getZ(), side, now));
            while (sv.orders.size() > 3) {
                sv.orders.remove(0);
            }
            VillageRecord head = ledger.get(sv.realm);
            ServerPlayer p = PoliticsService.onlinePlayer(ow, sv.player);
            if (p != null && head != null) {
                p.displayClientMessage(Component.literal((sv.role == Service.Role.VOLUNTEER ? "A call from " : "New orders from ") + ruler(head)
                        + ": " + (defend ? "defend " : "muster against ") + t.name), true);
            }
            // the realm puts its sworn and hired swords on campaign against this enemy (volunteers choose for themselves)
            if (rt != null && sv.role != Service.Role.VOLUNTEER) {
                rt.relations().enlist(ow, rt, sv.player, defend ? t.villageId : a.villageId, defend ? a.villageId : t.villageId);
            }
        }
        ledger.setDirty();
    }

    /** A field battle (§13): those serving the intercepting realm are ordered to the meeting point on the road instead of the walls. */
    public static void onIntercept(ServerLevel ow, GarrisonLedger ledger, Siege s, VillageRecord a, VillageRecord t, long now) {
        if (!enabled() || s.interceptBy == null) {
            return;
        }
        for (Service sv : ledger.services().values()) {
            if (!sv.realm.equals(s.interceptBy)) {
                continue;
            }
            sv.orders.removeIf(o -> o.siege.equals(s.id));
            sv.orders.add(new Service.Order(s.id, t.villageId, true, s.interceptX, s.interceptZ, ROAD, now));
            ServerPlayer p = PoliticsService.onlinePlayer(ow, sv.player);
            VillageRecord head = ledger.get(sv.realm);
            if (p != null && head != null) {
                p.displayClientMessage(Component.literal("New orders from " + ruler(head) + ": meet the host of " + a.name + " on the road"), true);
            }
        }
        ledger.setDirty();
    }

    /** The side of a road order (a field battle's meeting point). */
    public static final String ROAD = "road";

    /** Once a second: orders watched and answered, wages paid at dawn, contracts ended, knights judged. */
    public static void tick(ServerLevel ow, long now) {
        if (now % INTERVAL != 0 || !enabled()) {
            return;
        }
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        if (ledger.services().isEmpty()) {
            return;
        }
        SettlementSource source = Services.settlements();
        long day = ow.getDayTime() / DAY;
        for (Service s : new ArrayList<>(ledger.services().values())) {
            VillageRecord head = ledger.get(s.realm);
            if (head == null) {
                end(ow, ledger, s, now);
                continue;
            }
            UUID crown = RealmService.sovereignOf(ledger, s.realm, now);
            if (!crown.equals(s.realm)) {
                s.realm = crown; // its capital swore to another: the service follows the crown
                head = ledger.get(crown);
                if (head == null) {
                    continue;
                }
            }
            ServerPlayer p = PoliticsService.onlinePlayer(ow, s.player);
            if (s.until <= now) {
                notify(p, s, "Day " + now / DAY + ": your contract with " + head.name + " ended; sign again to stay on.");
                end(ow, ledger, s, now);
                continue;
            }
            List<UUID> realm = RealmService.members(ledger, head.villageId, now);
            if (day > s.paidDay) {
                boolean first = s.paidDay < 0;
                s.paidDay = day;
                int wage = Service.wage(s.role, realm.size(), !enemies(ledger, realm).isEmpty());
                if (wage > 0 && !first) {
                    pay(ow, ledger, source, p, s.player, wage, (s.role == Service.Role.KNIGHT ? "Your stipend from " : "Your wage from ") + head.name);
                    s.note("Day " + day + ": paid " + RecruitOffers.money(wage) + " by " + head.name);
                }
                if (s.role == Service.Role.KNIGHT) {
                    Standing st = RecruitService.standing(ow, head, s.player);
                    if (st == Standing.OUTLAW || st == Standing.UNWELCOME) {
                        banish(ow, ledger, source, s, head, p, "you have wronged " + head.name, now);
                        continue;
                    }
                }
            }
            for (Service.Order o : new ArrayList<>(s.orders)) {
                Siege sg = siege(ledger, o.siege);
                VillageRecord t = ledger.get(o.target);
                if (ROAD.equals(o.side) && sg != null && t != null) {
                    // a field battle: seen at the meeting point when the armies meet; answered once it is fought
                    if (!sg.interceptDone && Math.abs(now - sg.interceptAt) <= 1200) {
                        o.fought = true;
                        if (p != null && p.level() == ow) {
                            double dr = Math.hypot(p.getX() - o.x, p.getZ() - o.z);
                            o.atRally |= dr <= dev.hywmill.politics.war.FieldBattle.REACH;
                            o.near |= dr <= 3 * dev.hywmill.politics.war.FieldBattle.REACH;
                        }
                        continue;
                    }
                    if (!sg.interceptDone) {
                        continue;
                    }
                    s.orders.remove(o);
                    answer(ow, ledger, source, s, head, p, o, t, now);
                    if (!ledger.services().containsKey(s.player)) {
                        break;
                    }
                    if (sg.outcome == Siege.Outcome.NONE && sg.phase != Siege.Phase.RETURN) {
                        // the host marches on: to the walls
                        VillageRecord a = ledger.get(sg.attacker);
                        if (a != null) {
                            BlockPos rally = SiegeService.rallyPoint(a, t);
                            s.orders.add(new Service.Order(sg.id, t.villageId, true, rally.getX(), rally.getZ(),
                                    dev.hywmill.net.PoliticsNet.bearing(rally.getX() - t.center.getX(), rally.getZ() - t.center.getZ()), now));
                        }
                    }
                    continue;
                }
                if (sg != null && t != null && sg.fighting() && sg.wave <= 1) {
                    o.fought = true;
                    if (p != null && p.level() == ow) {
                        double dr = Math.hypot(p.getX() - o.x, p.getZ() - o.z);
                        double dt = Math.hypot(p.getX() - t.center.getX(), p.getZ() - t.center.getZ());
                        o.atRally |= dr <= Service.RALLY;
                        o.near |= dt <= (t.villageRadius > 0 ? t.villageRadius : SiegeService.DEFAULT_RADIUS) + Service.NEAR;
                    }
                    continue;
                }
                boolean over = sg == null || sg.outcome != Siege.Outcome.NONE || sg.phase == Siege.Phase.RETURN || sg.wave > 1;
                if (!over) {
                    continue;
                }
                s.orders.remove(o);
                if (!o.fought) {
                    s.note("Day " + now / DAY + ": the orders for " + (t == null ? "the siege" : t.name) + " were withdrawn");
                    continue;
                }
                answer(ow, ledger, source, s, head, p, o, t, now);
                if (!ledger.services().containsKey(s.player)) {
                    break; // banished
                }
            }
        }
        ledger.setDirty();
    }

    @Nullable
    static Siege siege(GarrisonLedger ledger, UUID id) {
        for (Siege s : ledger.sieges()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    static void answer(ServerLevel ow, GarrisonLedger ledger, SettlementSource source, Service s, VillageRecord head, @Nullable ServerPlayer p,
                       Service.Order o, @Nullable VillageRecord t, long now) {
        Service.Answer a = Service.answer(o.atRally, o.near);
        int pay = Service.pay(s.role, a);
        String where = t == null ? "the siege" : t.name;
        List<Officials.Official> off = officials(ledger, head);
        if (s.role != Service.Role.VOLUNTEER) {
            Officials.answered(off, s.player, a);
        }
        switch (a) {
            case OBEYED -> s.obeyed++;
            case PRESENT -> s.present++;
            case ABSENT -> {
                if (s.role == Service.Role.KNIGHT) {
                    s.ignored++;
                }
            }
        }
        if (pay > 0) {
            pay(ow, ledger, source, p, s.player, pay, "For " + where);
        }
        Officials.Official marshal = Officials.of(off, Officials.Office.MARSHAL);
        String text = "Day " + now / DAY + ": " + switch (a) {
            case OBEYED -> (ROAD.equals(o.side) ? "you met the enemy on the road to " : "you held the " + o.side + " rally at ") + where + (pay > 0 ? " (+" + RecruitOffers.money(pay) + ")" : "")
                    + (s.role != Service.Role.VOLUNTEER && marshal != null ? "; the Marshal " + marshal.name + " is pleased" : "");
            case PRESENT -> "you fought at " + where + ", though not where you were ordered" + (pay > 0 ? " (+" + RecruitOffers.money(pay) + ")" : "");
            case ABSENT -> s.role == Service.Role.VOLUNTEER ? "you did not answer the call to " + where
                    : "you were not at " + where + (s.role == Service.Role.KNIGHT ? " (orders ignored: " + s.ignored + " of " + Service.IGNORED_LIMIT + ")" : "");
        };
        notify(p, s, text);
        if (s.role == Service.Role.KNIGHT && Officials.banish(s.ignored, Officials.average(off, s.player))) {
            banish(ow, ledger, source, s, head, p, s.ignored >= Service.IGNORED_LIMIT ? "you ignored " + s.ignored + " orders" : "the council despises you", now);
        }
    }

    /** The council banishes a knight: his service ends and he is exiled from the realm for {@link Service#EXILE_DAYS} days. */
    static void banish(ServerLevel ow, GarrisonLedger ledger, @Nullable SettlementSource source, Service s, VillageRecord head, @Nullable ServerPlayer p,
                       String why, long now) {
        ledger.exiles().put(key(s.player, head.villageId), now + Service.EXILE_DAYS * DAY);
        end(ow, ledger, s, now);
        String name = PoliticsService.playerName(ow, s.player);
        if (source != null) {
            PoliticsService.chronicle(ow, source, head, now, "the council banishes " + name + " from the realm of " + head.name + ": " + why);
        }
        RealmPlan plan = ledger.plans().get(head.villageId);
        if (plan != null) {
            plan.note("Day " + now / DAY + ": the council banishes " + name + " (" + why + ")");
        }
        if (p != null) {
            p.sendSystemMessage(Component.literal("[Realm] The council of " + head.name + " banishes you from the realm (" + why + "): exiled for "
                    + Service.EXILE_DAYS + " days."));
        }
        HmLog.info("Service: '{}' banishes {} ({})", head.name, name, why);
    }

    static void pay(ServerLevel ow, GarrisonLedger ledger, @Nullable SettlementSource source, @Nullable ServerPlayer p, UUID player, int deniers, String why) {
        if (source == null || deniers <= 0) {
            return;
        }
        if (p != null) {
            source.giveMoney(p, deniers);
        } else {
            ledger.pendingPay().add(new PoliticsNbt.PendingPay(player, deniers, why + ": " + RecruitOffers.money(deniers)));
        }
    }

    static void notify(@Nullable ServerPlayer p, Service s, String text) {
        s.note(text);
        if (p != null) {
            p.displayClientMessage(Component.literal(text.replaceFirst("^Day \\d+: ", "")), true);
        }
    }

    // ------------------------------------------------------------------ the council: proposals and bribes

    /** A knight counsels war on (or, at war, pressing the attack on) {@code target}, or peace with it; the officials vote. */
    public static Result propose(ServerLevel ow, UUID player, VillageRecord target, boolean war) {
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        long now = ow.getGameTime();
        Service s = ledger.services().get(player);
        if (s == null || s.role != Service.Role.KNIGHT) {
            return new Result(false, "Only a knight of a realm may counsel its council.");
        }
        VillageRecord head = ledger.get(s.realm);
        if (head == null) {
            return new Result(false, "Your realm is no more.");
        }
        if (s.lastProposal >= 0 && now - s.lastProposal < DAY / 2) {
            return new Result(false, "The council heard you lately; wait about " + Math.max(1, (DAY / 2 - (now - s.lastProposal)) / 1200) + " min.");
        }
        List<UUID> realm = RealmService.members(ledger, head.villageId, now);
        if (realm.contains(target.villageId) || target.loneBuilding) {
            return new Result(false, target.name + " is of the realm itself.");
        }
        VillageRecord theirs = ledger.get(RealmService.sovereignOf(ledger, target.villageId, now));
        boolean atWar = false;
        for (UUID m : realm) {
            atWar |= RelationProjector.atWar(ledger, m, target.villageId);
        }
        if (!war && !atWar) {
            return new Result(false, "The realm is not at war with " + target.name + ".");
        }
        s.lastProposal = now;
        List<Officials.Official> off = officials(ledger, head);
        Officials.Verdict v = Officials.vote(off, player, war, head.villageId.getLeastSignificantBits() ^ now ^ player.getMostSignificantBits());
        RealmPlan plan = ledger.plans().computeIfAbsent(head.villageId, k -> new RealmPlan());
        String name = PoliticsService.playerName(ow, player);
        String votes = " (" + v.describe() + ")";
        ledger.setDirty();
        if (!v.passed()) {
            plan.note("Day " + now / DAY + ": the council will not hear " + name + "'s counsel of " + (war ? "war on " : "peace with ") + target.name);
            return new Result(false, "The council rejects your counsel" + votes + ".");
        }
        if (war && atWar) {
            plan.urged.put(target.villageId, now + DAY);
            plan.note("Day " + now / DAY + ": at the counsel of " + name + ", the council presses the war on " + target.name);
            return new Result(true, "The council will press the war on " + target.name + " for a day" + votes + ".");
        }
        if (war) {
            plan.note("Day " + now / DAY + ": at the counsel of " + name + ", the council declares war on " + target.name);
            WarCounselService.declareWar(ow, ledger, head, theirs != null ? theirs : target, now, "the council of " + head.name + ", at the counsel of " + name);
            return new Result(true, "The council declares war on " + (theirs != null ? theirs.name : target.name) + votes + ".");
        }
        // peace: the enemy weighs it by the balance of arms
        double accept = Math.max(0.15, Math.min(0.85, 0.35 * plan.ratio));
        boolean agreed = new java.util.Random(now ^ target.villageId.getMostSignificantBits()).nextDouble() < accept;
        if (!agreed) {
            plan.note("Day " + now / DAY + ": " + (theirs != null ? theirs.name : target.name) + " refuses the council's offer of peace");
            return new Result(false, "The council offers peace, but " + (theirs != null ? theirs.name : target.name) + " refuses" + votes + ".");
        }
        plan.note("Day " + now / DAY + ": at the counsel of " + name + ", the realm makes peace with " + (theirs != null ? theirs.name : target.name));
        WarCounselService.makePeace(ow, ledger, head, theirs != null ? theirs : target, now, "peace at the counsel of " + name, true);
        return new Result(true, "Peace is made with " + (theirs != null ? theirs.name : target.name) + votes + ".");
    }

    /** A bribe of {@code deniers} to one of the realm's officials ({@code index} in the council's order). */
    public static Result bribe(ServerLevel ow, ServerPlayer player, int index, int deniers) {
        GarrisonLedger ledger = GarrisonLedger.get(ow);
        Service s = ledger.services().get(player.getUUID());
        if (s == null) {
            return new Result(false, "You serve no realm.");
        }
        VillageRecord head = ledger.get(s.realm);
        SettlementSource source = Services.settlements();
        List<Officials.Official> off = head == null ? List.of() : officials(ledger, head);
        if (index < 0 || index >= off.size() || source == null) {
            return new Result(false, "No such official.");
        }
        Officials.Official o = off.get(index);
        if (source.playerMoney(player) < deniers) {
            return new Result(false, "You do not have " + RecruitOffers.money(deniers) + ".");
        }
        int before = o.of(player.getUUID());
        if (!Officials.bribe(o, player.getUUID(), deniers)) {
            ledger.setDirty();
            return new Result(false, o.title() + " is " + o.temper.label + ": he refuses your purse, and thinks the less of you (" + o.of(player.getUUID()) + ").");
        }
        source.takeMoney(player, deniers);
        ledger.setDirty();
        return new Result(true, o.title() + " (" + o.temper.label + ") takes " + RecruitOffers.money(deniers) + ": his opinion of you " + before + " -> "
                + o.of(player.getUUID()) + ".");
    }

    // ------------------------------------------------------------------ what the player sees

    /** The orders panel at the left of the HUD: the player's service, orders and calls, and what he was last told. Empty: none. */
    public static List<String> hudLines(ServerLevel ow, GarrisonLedger ledger, UUID player, long now) {
        Service s = ledger.services().get(player);
        if (s == null || !enabled()) {
            return List.of();
        }
        VillageRecord head = ledger.get(s.realm);
        if (head == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(Service.label(s.role, head.culture) + " of the realm of " + head.name
                + (s.role == Service.Role.MERCENARY ? " (contract: " + Math.max(1, (s.until - now) / 1200) + " min left)" : ""));
        ServerPlayer p = PoliticsService.onlinePlayer(ow, player);
        for (Service.Order o : s.orders) {
            Siege sg = siege(ledger, o.siege);
            VillageRecord t = ledger.get(o.target);
            if (sg == null || t == null) {
                continue;
            }
            String from = s.role == Service.Role.VOLUNTEER ? capital(ruler(head)) + " calls for volunteers: " : "Orders of " + ruler(head) + ": ";
            if (ROAD.equals(o.side)) {
                VillageRecord a = ledger.get(sg.attacker);
                out.add(from + "meet the host of " + (a == null ? "the enemy" : a.name) + " on the road to " + t.name + " at (" + o.x + ", " + o.z
                        + "): battle in about " + Math.max(1, Math.round(Math.max(0, sg.interceptAt - now) / 1200.0)) + " min.");
                if (p != null && p.level() == ow) {
                    double dr = Math.hypot(p.getX() - o.x, p.getZ() - o.z);
                    out.add(dr <= dev.hywmill.politics.war.FieldBattle.REACH ? "  You are at the meeting point." : "  Meeting point: " + Math.round(dr)
                            + " blocks " + dev.hywmill.net.PoliticsNet.bearing((int) Math.round(o.x - p.getX()), (int) Math.round(o.z - p.getZ())) + ".");
                }
                continue;
            }
            String when;
            if (sg.fighting()) {
                when = "the battle for " + t.name + " is under way";
            } else {
                long left = Math.max(0, sg.arriveAt - sg.elapsed(now, ow.getDayTime()));
                when = "the " + (o.defend ? "enemy" : "army") + " is there at dawn, in about " + Math.max(1, Math.round(left / 1200.0)) + " min";
            }
            out.add(from + (o.defend ? "hold the " + o.side + " side of " + t.name + " (" + o.x + ", " + o.z + "), where the enemy will land; "
                    : "muster on the " + o.side + " side of " + t.name + " (" + o.x + ", " + o.z + "); we attack from there at first light; ") + when + ".");
            if (p != null && p.level() == ow) {
                double dr = Math.hypot(p.getX() - o.x, p.getZ() - o.z);
                out.add(dr <= Service.RALLY ? "  You are at the rally point." : "  Rally point: " + Math.round(dr) + " blocks "
                        + dev.hywmill.net.PoliticsNet.bearing((int) Math.round(o.x - p.getX()), (int) Math.round(o.z - p.getZ())) + ".");
            }
        }
        if (s.orders.isEmpty() && !s.notes.isEmpty()) {
            out.add(s.notes.get(0));
        }
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The Realm tab: the realm's officials (and what they think of {@code viewer}), and those in its service. */
    public static List<String> realmLines(ServerLevel ow, GarrisonLedger ledger, VillageRecord head, @Nullable UUID viewer) {
        List<String> out = new ArrayList<>();
        if (!enabled()) {
            return out;
        }
        List<Officials.Official> off = officials(ledger, head);
        out.add(" Officials of " + ruler(head) + ":");
        for (Officials.Official o : off) {
            out.add("  " + o.office.label + " " + o.name + " (" + o.temper.label + ")" + (viewer == null ? "" : ", thinks of you " + o.of(viewer)));
        }
        List<String> serving = new ArrayList<>();
        for (Service s : ledger.services().values()) {
            if (s.realm.equals(head.villageId)) {
                serving.add(PoliticsService.playerName(ow, s.player) + " (" + Service.label(s.role, head.culture).toLowerCase() + ", orders obeyed "
                        + s.obeyed + ", ignored " + s.ignored + ")");
            }
        }
        if (!serving.isEmpty()) {
            out.add(" In its service: " + String.join("; ", serving) + ".");
        }
        Service mine = viewer == null ? null : ledger.services().get(viewer);
        if (mine != null && mine.realm.equals(head.villageId)) {
            out.add(" You serve it as " + Service.label(mine.role, head.culture).toLowerCase() + ". Lately:");
            mine.notes.forEach(n -> out.add("  " + n));
        }
        return out;
    }
}

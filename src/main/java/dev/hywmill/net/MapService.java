package dev.hywmill.net;

import dev.hywmill.garrison.service.FieldService;
import dev.hywmill.garrison.service.SiegeService;
import dev.hywmill.net.MapPayloads.Kind;
import dev.hywmill.net.MapPayloads.Marker;
import dev.hywmill.politics.realm.Service;
import dev.hywmill.politics.service.RealmService;
import dev.hywmill.politics.war.FieldBattle;
import dev.hywmill.politics.war.Relief;
import dev.hywmill.politics.war.Siege;
import dev.hywmill.politics.war.SiegeWaves;
import dev.hywmill.politics.war.Vassalage;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Map markers (1.8): every few seconds, for each player whose client takes them, the villages near him (what each is, whom it
 * answers to) and the wars' events (hosts on the march, sieges, field battles, armies of relief, the player's own orders),
 * each with its clock; sent only when they change. JourneyMap shows them, if installed (integration.journeymap). Owned by
 * the runtime (one per server).
 */
public final class MapService {
    public static final int INTERVAL = 100;
    /** Villages this near a player are on his map (blocks); events this near. */
    static final int VILLAGES = 4000, EVENTS = 8000;

    private final Map<UUID, List<Marker>> sent = new HashMap<>();
    /** 1.10: the borders last made, and who has them. */
    private MapPayloads.Borders borders = MapPayloads.Borders.EMPTY;
    private final java.util.Set<UUID> bordersSent = new java.util.HashSet<>();
    /** 1.10: the borders are made again this often (ticks). */
    public static final int BORDERS = 600;

    public void tick(ServerLevel overworld, long now) {
        if (now % BORDERS == 237) {
            sendBorders(overworld, GarrisonLedger.get(overworld), now);
        }
        if (now % INTERVAL != 37) {
            return;
        }
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        List<Marker> events = events(overworld, ledger, now);
        for (ServerPlayer p : overworld.getServer().getPlayerList().getPlayers()) {
            if (!p.connection.hasChannel(MapPayloads.Markers.TYPE)) {
                continue;
            }
            List<Marker> out = new ArrayList<>();
            if (p.level() == overworld) {
                for (VillageRecord v : ledger.all()) {
                    if (Math.hypot(v.center.getX() - p.getX(), v.center.getZ() - p.getZ()) <= VILLAGES) {
                        out.add(village(ledger, v, now));
                    }
                }
                for (Marker m : events) {
                    if (Math.hypot(m.x() - p.getX(), m.z() - p.getZ()) <= EVENTS) {
                        out.add(m);
                    }
                }
                out.addAll(orders(overworld, ledger, p.getUUID(), now));
            }
            if (out.size() > MapPayloads.Markers.MAX) {
                out = out.subList(0, MapPayloads.Markers.MAX);
            }
            if (!out.equals(sent.get(p.getUUID()))) {
                sent.put(p.getUUID(), out);
                PacketDistributor.sendToPlayer(p, new MapPayloads.Markers(out));
            }
        }
        sent.keySet().removeIf(id -> overworld.getServer().getPlayerList().getPlayer(id) == null);
    }

    /** Forget what a player was sent (they logged in again: send it all). */
    public void forget(UUID player) {
        sent.remove(player);
        bordersSent.remove(player);
    }

    /** 1.10: makes the borders again and sends them to whoever lacks them (all, when they changed). */
    void sendBorders(ServerLevel overworld, GarrisonLedger ledger, long now) {
        MapPayloads.Borders b = borders(ledger, now);
        if (!b.equals(borders)) {
            borders = b;
            bordersSent.clear();
        }
        for (ServerPlayer p : overworld.getServer().getPlayerList().getPlayers()) {
            if (p.connection.hasChannel(MapPayloads.Borders.TYPE) && bordersSent.add(p.getUUID())) {
                PacketDistributor.sendToPlayer(p, borders);
            }
        }
        bordersSent.removeIf(id -> overworld.getServer().getPlayerList().getPlayer(id) == null);
    }

    /** The borders now (1.10, also for the harness). */
    public MapPayloads.Borders borders() {
        return borders;
    }

    /**
     * 1.10: the states on the map: each village's land is its realm's (its sovereign's colours; a lone village is a state of
     * its own); a realm's vassals' land is drawn apart, fainter, in the same colours. Lone buildings hold none.
     */
    public static MapPayloads.Borders borders(GarrisonLedger ledger, long now) {
        List<MapPayloads.Owner> owners = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        List<dev.hywmill.politics.realm.Territory.Site> sites = new ArrayList<>();
        for (VillageRecord v : ledger.all()) {
            if (v.loneBuilding || sites.size() >= MapPayloads.Borders.MAX) {
                continue;
            }
            UUID head = RealmService.sovereignOf(ledger, v.villageId, now);
            VillageRecord h = ledger.get(head);
            if (h == null) {
                h = v;
                head = v.villageId;
            }
            boolean vassal = sworn(ledger, v.villageId, now);
            String key = head + (vassal ? ":v" : "");
            Integer o = index.get(key);
            if (o == null) {
                o = owners.size();
                index.put(key, o);
                int members = RealmService.members(ledger, head, now).size();
                String name = vassal ? "Vassals of the realm of " + h.name : members > 1 ? "Realm of " + h.name : h.name;
                String detail = vassal ? "Sworn to the realm of " + h.name : HudService.describe(ledger, h, now)[0];
                owners.add(new MapPayloads.Owner(cut(name, 128), cut(detail, 512), colour(h), vassal));
            }
            sites.add(new dev.hywmill.politics.realm.Territory.Site(v.center.getX(), v.center.getZ(),
                    dev.hywmill.politics.realm.Territory.radius(v.tier.ordinal()), o));
        }
        return new MapPayloads.Borders(owners, sites);
    }

    /** Whether a village answers to its sovereign through a vassal's oath somewhere up the chain (not as a province only). */
    static boolean sworn(GarrisonLedger ledger, UUID v, long now) {
        UUID at = v;
        for (int i = 0; i < 8; i++) {
            Vassalage t = RealmService.tieOf(ledger, at, now);
            if (t == null) {
                return false;
            }
            if (!t.province) {
                return true;
            }
            at = t.overlord;
        }
        return false;
    }

    static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static int colour(VillageRecord v) {
        return v.liveryPrimary >= 0 ? DyeColor.byId(v.liveryPrimary).getTextColor() : 0xD8C8A0;
    }

    static Marker village(GarrisonLedger ledger, VillageRecord v, long now) {
        String[] d = HudService.describe(ledger, v, now);
        Kind kind = Kind.VILLAGE;
        if (!v.loneBuilding) {
            Vassalage t = RealmService.tieOf(ledger, v.villageId, now);
            kind = t != null ? (t.province ? Kind.PROVINCE : Kind.VASSAL) : RealmService.subjectsOf(ledger, v.villageId, now).isEmpty() ? Kind.VILLAGE : Kind.CAPITAL;
        }
        return new Marker("v:" + v.villageId, kind, v.center.getX(), v.center.getZ(), v.name, d[0] + (d[1].isEmpty() ? "" : " · " + d[1]), colour(v), -1);
    }

    /** The wars' events: hosts gathering and on the march, sieges, field battles, armies of relief. */
    static List<Marker> events(ServerLevel ow, GarrisonLedger ledger, long now) {
        List<Marker> out = new ArrayList<>();
        long dayTime = ow.getDayTime();
        for (Siege s : ledger.sieges()) {
            VillageRecord a = ledger.get(s.attacker), t = ledger.get(s.target);
            if (a == null || t == null || s.outcome != Siege.Outcome.NONE) {
                continue;
            }
            String id = s.id.toString().substring(0, 8);
            long left = Math.max(0, s.arriveAt - s.elapsed(now, dayTime));
            String host = (RealmService.national(ledger, a.villageId, now) ? "Army of the realm of " : "Host of ") + a.name;
            int men = SiegeService.hostSize(a, s);
            switch (s.phase) {
                case PREPARE, MUSTER -> out.add(new Marker("h:" + id, Kind.HOST, a.center.getX(), a.center.getZ(), host + " gathers",
                        host + " gathers to march on " + t.name + (men > 0 ? " (" + men + " men)" : "") + "; before its walls in", colour(a), now + left));
                case MARCH, WAIT -> {
                    if (s.fieldPhase == Siege.Field.FORMING || s.fieldPhase == Siege.Field.FIGHT) {
                        break; // halted at the field: the battle marker shows it
                    }
                    double f = FieldBattle.along(s.elapsed(now, dayTime), s.arriveAt, s.march);
                    double[] p = dev.hywmill.garrison.service.SiegeService.route(s, a, t).at(f); // 1.10: along its road
                    int x = (int) Math.round(p[0]);
                    int z = (int) Math.round(p[1]);
                    out.add(new Marker("h:" + id, Kind.HOST, x, z, host + " → " + t.name, host + " (" + men + " men) marches on " + t.name
                            + "; before its walls in", colour(a), now + left));
                }
                case BATTLE -> out.add(new Marker("s:" + id, Kind.SIEGE, t.center.getX(), t.center.getZ(), "Siege of " + t.name + " (day " + s.wave + ")",
                        host + " besieges " + t.name + ", day " + s.wave + " of " + s.maxDays + (s.assault > 1 ? " (assault " + s.assault + ")" : "")
                                + "; sundown at the latest in", colour(a), s.phaseSince + SiegeWaves.WAVE_MAX));
                case NIGHT -> out.add(new Marker("s:" + id, Kind.SIEGE, t.center.getX(), t.center.getZ(), "Siege of " + t.name + (s.hostFellBack ? " (regrouping)" : " (night)"),
                        host + (s.hostFellBack ? " fell back to its lines before " : " is camped before ") + t.name + "; it comes on again in",
                        colour(a), s.phaseSince + (s.hostFellBack ? SiegeWaves.REGROUP : SiegeWaves.NIGHT_MIN)));
                case RETURN -> { }
            }
            if (s.interceptBy != null && !s.interceptDone) {
                VillageRecord by = ledger.get(s.interceptBy);
                String realm = by == null ? "the realm" : "the realm of " + by.name;
                switch (s.fieldPhase) {
                    case NONE -> out.add(new Marker("f:" + id, Kind.BATTLE, s.interceptX, s.interceptZ, "Armies meet",
                            "The army of " + realm + " (" + s.interceptMen + " men) marches to meet " + host + " on the road; they meet in", colour(by == null ? a : by),
                            s.interceptAt));
                    case FORMING -> out.add(new Marker("f:" + id, Kind.BATTLE, s.interceptX, s.interceptZ, "Ceasefire: battle",
                            "The army of " + realm + " (" + s.fieldRealmStart + ", " + FieldService.doctrineLabel(s.realmDoctrine) + ") faces " + host + " ("
                                    + s.fieldHostStart + ", " + FieldService.doctrineLabel(s.hostDoctrine) + ") under a ceasefire; battle in",
                            colour(by == null ? a : by), s.fieldBattleAt));
                    case FIGHT -> out.add(new Marker("f:" + id, Kind.BATTLE, s.interceptX, s.interceptZ, "Battle!",
                            "The army of " + realm + " fights " + host + " on the road to " + t.name, 0xFF4040, -1));
                    default -> { }
                }
            }
            for (Relief rl : s.reliefs) {
                VillageRecord h = ledger.get(rl.helper);
                if (h == null || rl.phase != Relief.Phase.MARCH) {
                    continue;
                }
                long total = Math.max(1, rl.phaseEnd - s.phaseSince);
                double f = Math.max(0, Math.min(1, 1 - (rl.phaseEnd - now) / (double) total));
                double[] p = dev.hywmill.integration.roads.VillageRoads.route(ow, h.center.getX(), h.center.getZ(), t.center.getX(), t.center.getZ()).at(f);
                int x = (int) Math.round(p[0]); // 1.10: along the roads when they serve
                int z = (int) Math.round(p[1]);
                out.add(new Marker("r:" + id + ":" + h.villageId.toString().substring(0, 8), Kind.RELIEF, x, z, "Relief of " + t.name,
                        h.name + " sends " + rl.units.size() + " men to relieve " + t.name + "; they arrive in", colour(h), rl.phaseEnd));
            }
        }
        return out;
    }

    /** The player's orders in a realm's service: each rally point or line, with its clock. */
    static List<Marker> orders(ServerLevel ow, GarrisonLedger ledger, UUID player, long now) {
        List<Marker> out = new ArrayList<>();
        // 1.10: columns on the road his side's scouts found (supply trains, messengers, mercenaries...), moving along their roads
        for (dev.hywmill.politics.war.Column c : dev.hywmill.garrison.service.ColumnService.visible(ledger, player)) {
            VillageRecord ov = ledger.get(c.owner);
            if (!c.onRoad() || now < c.depart || ov == null) {
                continue;
            }
            double[] p = c.position(now);
            String what = switch (c.kind) {
                case SUPPLY -> "Supply train";
                case MERCS -> "Mercenaries";
                case VASSAL -> "Vassal's men";
                case MESSENGER -> "Messenger";
                case ALARM -> "Scouts";
                case CONVOY -> "Convoy";
            };
            VillageRecord dv = c.destination == null ? null : ledger.get(c.destination);
            out.add(new Marker("c:" + c.id.toString().substring(0, 8), Kind.COLUMN, (int) Math.round(p[0]), (int) Math.round(p[1]), what + " of " + ov.name,
                    c.label() + " of " + ov.name + (dv != null && c.kind != dev.hywmill.politics.war.Column.Kind.CONVOY ? " → " + dv.name : "")
                            + (c.onTheRoad(now) ? ", on the road" : "") + (c.kind == dev.hywmill.politics.war.Column.Kind.CONVOY ? "; breaks camp in" : "; arrives in"),
                    colour(ov), c.arrive));
        }
        dev.hywmill.politics.war.Assault a = dev.hywmill.garrison.service.AssaultService.active(ledger, player);
        VillageRecord at = a == null ? null : ledger.get(a.target);
        if (at != null) {
            // 1.9: the rally point of the player's own assault, with the hour his men go in
            long due = switch (a.phase) {
                case GATHER -> a.assaultAt;
                case REGROUP -> a.phaseSince + dev.hywmill.politics.war.Assault.REGROUP;
                default -> -1;
            };
            out.add(new Marker("a:" + a.id.toString().substring(0, 8), Kind.ORDER, a.rallyX, a.rallyZ, "Your assault: rally",
                    "Your assault on " + at.name + ": rally on the " + a.sideName() + " side at (" + a.rallyX + ", " + a.rallyZ + ")"
                            + (due >= 0 ? "; your men go in in" : ""), 0xFF8C00, due));
        }
        Service sv = ledger.services().get(player);
        if (sv == null) {
            return out;
        }
        for (Service.Order o : sv.orders) {
            Siege sg = null;
            for (Siege s : ledger.sieges()) {
                if (s.id.equals(o.siege)) {
                    sg = s;
                }
            }
            VillageRecord t = ledger.get(o.target);
            if (sg == null || t == null) {
                continue;
            }
            long due = dev.hywmill.politics.service.ServiceRoles.ROAD.equals(o.side)
                    ? (sg.fieldPhase == Siege.Field.FORMING ? sg.fieldBattleAt : sg.fieldPhase == Siege.Field.NONE ? sg.interceptAt : -1)
                    : sg.fighting() ? -1 : now + Math.max(0, sg.arriveAt - sg.elapsed(now, ow.getDayTime()));
            String what = dev.hywmill.politics.service.ServiceRoles.ROAD.equals(o.side) ? (o.defend ? "Our line" : "The host's line")
                    : (o.defend ? "Hold the " : "Muster on the ") + o.side + " side";
            out.add(new Marker("o:" + o.siege.toString().substring(0, 8), Kind.ORDER, o.x, o.z, "Your orders: " + what,
                    "Your orders (" + t.name + "): " + what + " at (" + o.x + ", " + o.z + ")" + (due >= 0 ? "; due in" : ""), 0xFFD700, due));
        }
        return out;
    }
}

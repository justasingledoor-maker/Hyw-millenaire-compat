package dev.hywmill.net;

import dev.hywmill.politics.realm.Rulers;
import dev.hywmill.politics.service.RealmService;
import dev.hywmill.politics.war.Vassalage;
import dev.hywmill.politics.war.WarRecord;
import dev.hywmill.recruit.RecruitService;
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
 * The HUD (1.7): once a second, for each player, the village they stand in (its name, what it is, whom it answers to) and
 * their orders; sent only when it changes. Owned by the runtime (one per server).
 */
public final class HudService {
    public static final int INTERVAL = 20;

    private final Map<UUID, HudPayloads.Hud> sent = new HashMap<>();

    public void tick(ServerLevel overworld, long now) {
        if (now % INTERVAL != 0) {
            return;
        }
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        for (ServerPlayer p : overworld.getServer().getPlayerList().getPlayers()) {
            if (!p.connection.hasChannel(HudPayloads.Hud.TYPE)) {
                continue;
            }
            VillageRecord v = p.level() == overworld ? RecruitService.villageAt(overworld, p.blockPosition()) : null;
            List<String> orders = dev.hywmill.politics.service.ServiceRoles.hudLines(overworld, ledger, p.getUUID(), now);
            HudPayloads.Hud hud = v == null ? new HudPayloads.Hud("", "", "", -1, orders) : bar(ledger, v, orders, now);
            if (!hud.equals(sent.get(p.getUUID()))) {
                sent.put(p.getUUID(), hud);
                PacketDistributor.sendToPlayer(p, hud);
            }
        }
        sent.keySet().removeIf(id -> overworld.getServer().getPlayerList().getPlayer(id) == null);
    }

    /** Forget what a player was sent (they logged in again: send it all). */
    public void forget(UUID player) {
        sent.remove(player);
    }

    static HudPayloads.Hud bar(GarrisonLedger ledger, VillageRecord v, List<String> orders, long now) {
        String[] d = describe(ledger, v, now);
        int colour = v.liveryPrimary >= 0 ? DyeColor.byId(v.liveryPrimary).getTextColor() : -1;
        return new HudPayloads.Hud(v.name, d[0], d[1], colour, orders);
    }

    /** {what it is, whom it answers to}: "Province" / "Ruled by X, its King"; "Capital of a realm of 4 villages" / "". */
    public static String[] describe(GarrisonLedger ledger, VillageRecord v, long now) {
        String status, owner = "";
        if (v.loneBuilding) {
            status = "Lone building";
        } else if (!RealmService.enabled()) {
            status = "Village";
        } else {
            Vassalage t = RealmService.tieOf(ledger, v.villageId, now);
            if (t != null) {
                VillageRecord o = ledger.get(t.overlord);
                VillageRecord head = ledger.get(RealmService.sovereignOf(ledger, t.overlord, now));
                String by = o == null ? "?" : o.name;
                status = t.province ? "Province" : "Vassal";
                owner = (t.province ? "Ruled by " : "Sworn to ") + by + (head != null ? ", its " + Rulers.title(head.culture) : "");
            } else {
                int subjects = RealmService.subjectsOf(ledger, v.villageId, now).size();
                status = subjects == 0 ? "Independent village" : RealmService.national(ledger, v.villageId, now)
                        ? "Capital of a realm of " + (subjects + 1) + " villages" : "Overlord of " + subjects + (subjects == 1 ? " village" : " villages");
                owner = subjects == 0 ? "" : "Seat of the " + Rulers.title(v.culture);
            }
        }
        List<String> enemies = new ArrayList<>();
        for (WarRecord w : ledger.wars().values()) {
            if (w.atWar() && (w.a.equals(v.villageId) || w.b.equals(v.villageId))) {
                VillageRecord e = ledger.get(w.a.equals(v.villageId) ? w.b : w.a);
                if (e != null) {
                    enemies.add(e.name);
                }
            }
        }
        if (!enemies.isEmpty()) {
            status += enemies.size() == 1 ? " · at war with " + enemies.get(0) : " · at war with " + enemies.size() + " villages";
        }
        return new String[]{status, owner};
    }
}

package dev.hywmill.garrison.service;

import dev.hywmill.core.HmLog;
import dev.hywmill.garrison.equip.EquipmentProfiles;
import dev.hywmill.garrison.equip.VillageLivery;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Post-M5 village liveries ({@link VillageLivery}): a village's two colours are chosen the first time they are needed, away
 * from the liveries of the villages within {@link #NEIGHBOURHOOD} blocks, and kept in its record.
 */
public final class LiveryService {
    private LiveryService() {}

    public static final int NEIGHBOURHOOD = 3000;

    /** The village's {primary, secondary} dye ids, chosen now if it has none (lone buildings: null, they wear their own). */
    @Nullable
    public static int[] of(ServerLevel overworld, VillageRecord rec) {
        if (rec.loneBuilding) {
            return null;
        }
        if (dev.hywmill.politics.service.RealmService.enabled()) {
            // post-M5 realms: a province's soldiers wear its sovereign's colours (its own are kept for the day it rebels)
            GarrisonLedger ledger = GarrisonLedger.get(overworld);
            dev.hywmill.politics.war.Vassalage t = dev.hywmill.politics.service.RealmService.tieOf(ledger, rec.villageId, overworld.getGameTime());
            VillageRecord sov = t != null && t.province ? ledger.get(t.overlord) : null;
            if (sov != null && sov != rec && !sov.loneBuilding) {
                return of(overworld, sov);
            }
        }
        if (rec.liveryPrimary < 0 || rec.liverySecondary < 0) {
            GarrisonLedger ledger = GarrisonLedger.get(overworld);
            List<int[]> near = new ArrayList<>();
            for (VillageRecord v : ledger.all()) {
                if (v != rec && v.liveryPrimary >= 0 && v.center.distSqr(rec.center) <= (double) NEIGHBOURHOOD * NEIGHBOURHOOD) {
                    near.add(new int[]{v.liveryPrimary, v.liverySecondary});
                }
            }
            int[] l = VillageLivery.choose(EquipmentProfiles.current().heraldryOf(rec.culture).colours(), near, rec.villageId);
            rec.liveryPrimary = l[0];
            rec.liverySecondary = l[1];
            ledger.setDirty();
            HmLog.info("Village '{}' takes the colours {} and {} ({} livery/liveries nearby)", rec.name,
                    net.minecraft.world.item.DyeColor.byId(l[0]).getName(), net.minecraft.world.item.DyeColor.byId(l[1]).getName(), near.size());
        }
        return new int[]{rec.liveryPrimary, rec.liverySecondary};
    }
}

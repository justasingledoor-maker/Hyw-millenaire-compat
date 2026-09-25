package dev.hywmill.military.defense;

import net.minecraft.nbt.CompoundTag;

/** Persistent per-village military statistics (garrison ledger, format 3+). Mutable; server thread only. */
public final class DefenseStats {
    public int alerts;
    public int engagements;
    public int hywKills;
    public int residentLosses;
    public long lastAlertTick = -1;
    public long lastEngagedTick = -1;
    public long lastKillTick = -1;
    public long lastLossTick = -1;

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("alerts", alerts);
        t.putInt("engagements", engagements);
        t.putInt("hywKills", hywKills);
        t.putInt("residentLosses", residentLosses);
        t.putLong("lastAlertTick", lastAlertTick);
        t.putLong("lastEngagedTick", lastEngagedTick);
        t.putLong("lastKillTick", lastKillTick);
        t.putLong("lastLossTick", lastLossTick);
        return t;
    }

    public static DefenseStats load(CompoundTag t) {
        DefenseStats s = new DefenseStats();
        s.alerts = t.getInt("alerts");
        s.engagements = t.getInt("engagements");
        s.hywKills = t.getInt("hywKills");
        s.residentLosses = t.getInt("residentLosses");
        s.lastAlertTick = t.contains("lastAlertTick") ? t.getLong("lastAlertTick") : -1;
        s.lastEngagedTick = t.contains("lastEngagedTick") ? t.getLong("lastEngagedTick") : -1;
        s.lastKillTick = t.contains("lastKillTick") ? t.getLong("lastKillTick") : -1;
        s.lastLossTick = t.contains("lastLossTick") ? t.getLong("lastLossTick") : -1;
        return s;
    }

    @Override
    public String toString() {
        return "alerts=" + alerts + " engagements=" + engagements + " hywKills=" + hywKills + " residentLosses=" + residentLosses
                + " lastAlert=" + lastAlertTick + " lastEngaged=" + lastEngagedTick;
    }
}

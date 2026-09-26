package dev.hywmill.garrison;

import dev.hywmill.garrison.tag.GarrisonTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The persistent HYW garrison of one village (stored in its {@code VillageRecord}, ledger format 4).
 * The roster is authoritative: entities are only created from its RECRUITED entries and only
 * bound to it through their {@code GarrisonTag}.
 */
public final class GarrisonRoster {
    public boolean startingGranted;
    public double levyPoints;
    public long lastAccrualTick;
    public int nextSeq;
    public boolean paused;
    /** Next recruit is allowed at {@code lastRecruitTick + recruitInterval}; cooldowns push it forward. */
    public long lastRecruitTick;
    /** Tick the village first went missing from the settlement list, or -1. */
    public long goneSinceTick = -1;
    public final Totals totals = new Totals();
    private final List<RosterEntry> entries = new ArrayList<>();

    public static final class Totals {
        public int recruited, spawned, killed, lost, recovered, duplicatesDiscarded;
    }

    public GarrisonRoster(long tick) {
        this.lastAccrualTick = tick;
        this.lastRecruitTick = Long.MIN_VALUE / 2;
    }

    public List<RosterEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    @Nullable
    public RosterEntry entry(UUID rosterId) {
        for (RosterEntry e : entries) {
            if (e.rosterId.equals(rosterId)) {
                return e;
            }
        }
        return null;
    }

    @Nullable
    public RosterEntry boundTo(UUID entityUuid) {
        for (RosterEntry e : entries) {
            if (entityUuid.equals(e.entityUuid) && !e.state().terminal()) {
                return e;
            }
        }
        return null;
    }

    /** Deterministic slot identity: village + sequence number. */
    public static UUID rosterId(UUID villageId, int seq) {
        return UUID.nameUUIDFromBytes(("hywmill:garrison_slot:" + villageId + ":" + seq).getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a RECRUITED entry with the next sequence number. */
    public RosterEntry recruit(UUID villageId, String unitKey, String entityType, int equipmentLevel, long tick, boolean paid) {
        RosterEntry e = new RosterEntry(rosterId(villageId, nextSeq++), unitKey, entityType, equipmentLevel, tick, paid);
        entries.add(e);
        totals.recruited++;
        return e;
    }

    /**
     * Roster-first spawn step: the slot moves RECRUITED -> SPAWNED with the next generation and its
     * deterministic entity UUID <em>before</em> the entity is added. Returns the tag to stamp.
     */
    public GarrisonTag beginSpawn(UUID villageId, RosterEntry e, long tick) {
        e.transition(UnitState.SPAWNED, tick);
        e.generation++;
        e.entityUuid = GarrisonTag.entityUuid(e.rosterId, e.generation);
        return new GarrisonTag(villageId, e.rosterId, e.generation);
    }

    /**
     * The add failed: back to RECRUITED with the previous generation, so every retry uses the same
     * deterministic UUID. A failure because that UUID is already loaded (a stale roster whose unit
     * exists) can therefore never turn into a second unit; reconciliation adopts the loaded one.
     */
    public void revertSpawn(RosterEntry e, long tick) {
        e.transition(UnitState.RECRUITED, tick);
        e.entityUuid = null;
        e.generation = Math.max(0, e.generation - 1);
    }

    /** The add succeeded. */
    public void spawned(RosterEntry e, int appliedLevel, long tick) {
        e.equipmentLevel = appliedLevel;
        e.lastSeenTick = tick;
        totals.spawned++;
    }

    /**
     * DEV ONLY (M3-0 spike, {@code /hywmill dev spike-spawn}): registers a slot with a given id and
     * generation, already SPAWNED and bound to its deterministic UUID, so a spike unit is
     * roster-backed like a production unit. Returns the existing slot if it is already registered.
     */
    public RosterEntry devBind(UUID rosterId, String unitKey, String entityType, int level, int generation, long tick) {
        RosterEntry e = entry(rosterId);
        if (e == null) {
            e = new RosterEntry(rosterId, unitKey, entityType, level, UnitState.SPAWNED, GarrisonTag.entityUuid(rosterId, generation),
                    generation, tick, tick, tick, null, false);
            entries.add(e);
        }
        return e;
    }

    /** Non-terminal entries (the garrison strength, including RECRUITED ones not yet spawned). */
    public int live() {
        int n = 0;
        for (RosterEntry e : entries) {
            if (!e.state().terminal()) {
                n++;
            }
        }
        return n;
    }

    public Map<UnitState, Integer> countByState() {
        Map<UnitState, Integer> m = new EnumMap<>(UnitState.class);
        for (RosterEntry e : entries) {
            m.merge(e.state(), 1, Integer::sum);
        }
        return m;
    }

    /** Drops terminal entries older than {@code retention} ticks (totals are kept). */
    public int pruneTerminal(long tick, long retention) {
        int n = 0;
        for (Iterator<RosterEntry> it = entries.iterator(); it.hasNext(); ) {
            RosterEntry e = it.next();
            if (e.state().terminal() && tick - e.stateSinceTick >= retention) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    // ---- NBT ----

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putBoolean("startingGranted", startingGranted);
        t.putDouble("levyPoints", levyPoints);
        t.putLong("lastAccrualTick", lastAccrualTick);
        t.putInt("nextSeq", nextSeq);
        t.putBoolean("paused", paused);
        t.putLong("lastRecruitTick", lastRecruitTick);
        t.putLong("goneSinceTick", goneSinceTick);
        CompoundTag tot = new CompoundTag();
        tot.putInt("recruited", totals.recruited);
        tot.putInt("spawned", totals.spawned);
        tot.putInt("killed", totals.killed);
        tot.putInt("lost", totals.lost);
        tot.putInt("recovered", totals.recovered);
        tot.putInt("duplicatesDiscarded", totals.duplicatesDiscarded);
        t.put("totals", tot);
        ListTag list = new ListTag();
        for (RosterEntry e : entries) {
            CompoundTag c = new CompoundTag();
            c.putUUID("rosterId", e.rosterId);
            c.putString("unitKey", e.unitKey);
            c.putString("entityType", e.entityType);
            c.putInt("equipmentLevel", e.equipmentLevel);
            c.putString("state", e.state().name());
            if (e.entityUuid != null) {
                c.putUUID("entityUuid", e.entityUuid);
            }
            c.putInt("generation", e.generation);
            c.putLong("stateSinceTick", e.stateSinceTick);
            c.putLong("lastSeenTick", e.lastSeenTick);
            c.putLongArray("lastSeenPos", new long[]{e.lastSeenX, e.lastSeenY, e.lastSeenZ});
            c.putLong("recruitedTick", e.recruitedTick);
            if (e.lossReason() != null) {
                c.putString("lossReason", e.lossReason().name());
            }
            c.putBoolean("paid", e.paid);
            if (e.assignedDuty != dev.hywmill.garrison.duty.Duty.GARRISON || e.duty != dev.hywmill.garrison.duty.Duty.GARRISON || e.dutyIndex >= 0) {
                CompoundTag d = new CompoundTag();
                d.putString("assigned", e.assignedDuty.name());
                d.putString("current", e.duty.name());
                d.putInt("index", e.dutyIndex);
                d.putInt("step", e.dutyStep);
                d.putLong("since", e.dutySince);
                c.put("duty", d);
            }
            list.add(c);
        }
        t.put("entries", list);
        return t;
    }

    public static GarrisonRoster load(CompoundTag t, long tick) {
        GarrisonRoster r = new GarrisonRoster(tick);
        r.startingGranted = t.getBoolean("startingGranted");
        r.levyPoints = t.getDouble("levyPoints");
        r.lastAccrualTick = t.contains("lastAccrualTick") ? t.getLong("lastAccrualTick") : tick;
        r.nextSeq = t.getInt("nextSeq");
        r.paused = t.getBoolean("paused");
        if (t.contains("lastRecruitTick")) {
            r.lastRecruitTick = t.getLong("lastRecruitTick");
        }
        r.goneSinceTick = t.contains("goneSinceTick") ? t.getLong("goneSinceTick") : -1;
        CompoundTag tot = t.getCompound("totals");
        r.totals.recruited = tot.getInt("recruited");
        r.totals.spawned = tot.getInt("spawned");
        r.totals.killed = tot.getInt("killed");
        r.totals.lost = tot.getInt("lost");
        r.totals.recovered = tot.getInt("recovered");
        r.totals.duplicatesDiscarded = tot.getInt("duplicatesDiscarded");
        ListTag list = t.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            UnitState state;
            try {
                state = UnitState.valueOf(c.getString("state"));
            } catch (IllegalArgumentException ex) {
                state = UnitState.MISSING;
            }
            LossReason reason = null;
            if (c.contains("lossReason")) {
                try {
                    reason = LossReason.valueOf(c.getString("lossReason"));
                } catch (IllegalArgumentException ex) {
                    reason = LossReason.REMOVED;
                }
            }
            long[] pos = c.getLongArray("lastSeenPos");
            RosterEntry e = new RosterEntry(c.getUUID("rosterId"), c.getString("unitKey"), c.getString("entityType"),
                    c.getInt("equipmentLevel"), state, c.hasUUID("entityUuid") ? c.getUUID("entityUuid") : null, c.getInt("generation"),
                    c.getLong("stateSinceTick"), c.getLong("lastSeenTick"), c.getLong("recruitedTick"), reason, c.getBoolean("paid"));
            if (c.contains("duty", Tag.TAG_COMPOUND)) {
                CompoundTag d = c.getCompound("duty");
                e.assignedDuty = dev.hywmill.garrison.duty.Duty.parse(d.getString("assigned"), dev.hywmill.garrison.duty.Duty.GARRISON);
                if (!e.assignedDuty.standing()) {
                    e.assignedDuty = dev.hywmill.garrison.duty.Duty.GARRISON;
                }
                e.duty = dev.hywmill.garrison.duty.Duty.parse(d.getString("current"), e.assignedDuty);
                e.dutyIndex = d.getInt("index");
                e.dutyStep = d.getInt("step");
                e.dutySince = d.getLong("since");
            }
            if (pos.length == 3) {
                e.lastSeenX = pos[0];
                e.lastSeenY = pos[1];
                e.lastSeenZ = pos[2];
            }
            r.entries.add(e);
        }
        return r;
    }
}

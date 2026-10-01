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
    /**
     * Post-M5 war arsenal: siege engines and their engineers a village fields while it is at war. Kept apart from the
     * garrison (never counted, recruited, reconciled or given duties) but found by {@link #entry} and {@link #boundTo}, so
     * joins, deaths and duplicate refusal work as for garrison units. Lost engines are not replaced during the war.
     */
    private final List<RosterEntry> arsenal = new ArrayList<>();
    private int arsenalSeq;
    /** An arsenal was granted for the current war period (cleared when the village is at peace again). */
    public boolean arsenalWar;
    /** M4: this village's own current Millénaire raid, if a contingent was sent (persisted as an optional key). */
    @Nullable public RaidRecord raid;
    /** Start tick of the last Millénaire raid a contingent was chosen for (never join the same raid twice). */
    public long lastRaidStart;
    /** Post-M5: tick the village last launched a siege (its own decisions wait {@code aiCooldown} after it); -1 never. */
    public long lastSiegeTick = -1;
    /** Post-M5: the village has mobilized for its current war (reset when it is at peace and its levies went home). */
    public boolean mobilizedWar;
    /** Post-M5: tick of the last wartime levy top-up (-1: none this war). */
    public long lastLevyTick = -1;

    /**
     * M4 raid in progress. {@code performedBase}: the attacker's raid-history length when the raid
     * started (it grows by one when the raid ends); {@code phase}: MUSTER until the contingent is
     * moved to the landing point, AWAY while it fights, then the record is cleared.
     */
    public static final class RaidRecord {
        public final UUID target;
        public final long raidStart;
        public final int performedBase;
        public String phase;
        public long phaseSince;
        public int sent;

        public RaidRecord(UUID target, long raidStart, int performedBase, String phase, long phaseSince, int sent) {
            this.target = target;
            this.raidStart = raidStart;
            this.performedBase = performedBase;
            this.phase = phase;
            this.phaseSince = phaseSince;
            this.sent = sent;
        }
    }

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
        for (RosterEntry e : arsenal) {
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
        for (RosterEntry e : arsenal) {
            if (entityUuid.equals(e.entityUuid) && !e.state().terminal()) {
                return e;
            }
        }
        return null;
    }

    public List<RosterEntry> arsenal() {
        return Collections.unmodifiableList(arsenal);
    }

    public boolean isArsenal(RosterEntry e) {
        return arsenal.contains(e);
    }

    /** Adds a RECRUITED arsenal entry (an engine or an engineer) with the next arsenal sequence number. */
    public RosterEntry arm(UUID villageId, String key, String entityType, long tick) {
        UUID id = UUID.nameUUIDFromBytes(("hywmill:arsenal_slot:" + villageId + ":" + arsenalSeq++).getBytes(StandardCharsets.UTF_8));
        RosterEntry e = new RosterEntry(id, key, entityType, 0, tick, false);
        arsenal.add(e);
        return e;
    }

    /** Removes an arsenal entry (war over): its slot id is never reused, so a stale entity is refused as a duplicate. */
    public void disarm(RosterEntry e) {
        arsenal.remove(e);
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
            if (!e.state().terminal() && !e.temporary()) { // hired mercenaries and a siege's temporary defenders are not its garrison
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
        if (lastRaidStart != 0) {
            t.putLong("lastRaidStart", lastRaidStart);
        }
        if (lastSiegeTick >= 0) {
            t.putLong("lastSiegeTick", lastSiegeTick);
        }
        if (mobilizedWar) {
            t.putBoolean("mobilizedWar", true);
        }
        if (lastLevyTick >= 0) {
            t.putLong("lastLevyTick", lastLevyTick);
        }
        if (raid != null) {
            CompoundTag rd = new CompoundTag();
            rd.putUUID("target", raid.target);
            rd.putLong("raidStart", raid.raidStart);
            rd.putInt("performedBase", raid.performedBase);
            rd.putString("phase", raid.phase);
            rd.putLong("phaseSince", raid.phaseSince);
            rd.putInt("sent", raid.sent);
            t.put("raid", rd);
        }
        ListTag list = new ListTag();
        for (RosterEntry e : entries) {
            list.add(entryTag(e));
        }
        t.put("entries", list);
        if (!arsenal.isEmpty() || arsenalSeq > 0 || arsenalWar) {
            ListTag al = new ListTag();
            for (RosterEntry e : arsenal) {
                al.add(entryTag(e));
            }
            t.put("arsenal", al);
            t.putInt("arsenalSeq", arsenalSeq);
            t.putBoolean("arsenalWar", arsenalWar);
        }
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
        r.lastRaidStart = t.getLong("lastRaidStart");
        r.lastSiegeTick = t.contains("lastSiegeTick") ? t.getLong("lastSiegeTick") : -1;
        r.mobilizedWar = t.getBoolean("mobilizedWar");
        r.lastLevyTick = t.contains("lastLevyTick") ? t.getLong("lastLevyTick") : -1;
        if (t.contains("raid", Tag.TAG_COMPOUND)) {
            CompoundTag rd = t.getCompound("raid");
            if (rd.hasUUID("target")) {
                r.raid = new RaidRecord(rd.getUUID("target"), rd.getLong("raidStart"), rd.getInt("performedBase"), rd.getString("phase"),
                        rd.getLong("phaseSince"), rd.getInt("sent"));
            }
        }
        ListTag list = t.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            r.entries.add(entryFrom(list.getCompound(i)));
        }
        ListTag al = t.getList("arsenal", Tag.TAG_COMPOUND);
        for (int i = 0; i < al.size(); i++) {
            r.arsenal.add(entryFrom(al.getCompound(i)));
        }
        r.arsenalSeq = t.getInt("arsenalSeq");
        r.arsenalWar = t.getBoolean("arsenalWar");
        return r;
    }

    private static CompoundTag entryTag(RosterEntry e) {
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
        if (e.mobilized) {
            c.putBoolean("mobilized", true);
        }
        if (!e.mercLook.isEmpty()) {
            c.putString("merc", e.mercLook);
        }
        if (!e.extra.isEmpty()) {
            c.putString("extra", e.extra);
        }
        if (e.assignedDuty != dev.hywmill.garrison.duty.Duty.GARRISON || e.duty != dev.hywmill.garrison.duty.Duty.GARRISON || e.dutyIndex >= 0
                || !e.equipRole.isEmpty() || e.errandPlayer != null) {
            CompoundTag d = new CompoundTag();
            d.putString("assigned", e.assignedDuty.name());
            d.putString("current", e.duty.name());
            d.putInt("index", e.dutyIndex);
            d.putInt("step", e.dutyStep);
            d.putLong("since", e.dutySince);
            if (!e.equipRole.isEmpty()) {
                d.putString("equipRole", e.equipRole);
            }
            if (e.errandPlayer != null) {
                d.putUUID("errandPlayer", e.errandPlayer);
                d.putLong("errandUntil", e.errandUntil);
                if (e.errandPoint != Long.MIN_VALUE) {
                    d.putLong("errandPoint", e.errandPoint);
                }
            }
            c.put("duty", d);
        }
        return c;
    }

    private static RosterEntry entryFrom(CompoundTag c) {
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
            e.equipRole = d.getString("equipRole");
            if (d.hasUUID("errandPlayer") && e.duty.errand()) {
                e.errandPlayer = d.getUUID("errandPlayer");
                e.errandUntil = d.getLong("errandUntil");
                e.errandPoint = d.contains("errandPoint") ? d.getLong("errandPoint") : Long.MIN_VALUE;
            }
            // an errand duty this version does not know (e.g. a deferred escort saved by a development build) loads as
            // its standing duty; a DEPLOYED entry then takes the normal M2 return path home
        }
        if (pos.length == 3) {
            e.lastSeenX = pos[0];
            e.lastSeenY = pos[1];
            e.lastSeenZ = pos[2];
        }
        e.mobilized = c.getBoolean("mobilized");
        e.mercLook = c.getString("merc");
        e.extra = c.getString("extra");
        return e;
    }
}

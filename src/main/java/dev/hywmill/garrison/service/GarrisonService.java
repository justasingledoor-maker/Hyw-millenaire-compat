package dev.hywmill.garrison.service;

import dev.hywmill.config.HywMillConfig;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.PerfCounters;
import dev.hywmill.core.Services;
import dev.hywmill.faction.CombatFactionService;
import dev.hywmill.garrison.Deployment;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.GarrisonSettings;
import dev.hywmill.garrison.JoinAdjudicator;
import dev.hywmill.garrison.LossReason;
import dev.hywmill.garrison.Reconciler;
import dev.hywmill.garrison.ScalingGate;
import dev.hywmill.garrison.Recruitment;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.SpawnSpots;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.spi.EquipmentProvider;
import dev.hywmill.garrison.spi.SpawnRequest;
import dev.hywmill.garrison.spi.SpawnResult;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.garrison.tables.GarrisonTable;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.garrison.tag.GarrisonAttachments;
import dev.hywmill.garrison.tag.GarrisonTag;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.defense.AlertState;
import dev.hywmill.military.defense.DefenseCoordinator;
import dev.hywmill.military.defense.VillageDefenseState;
import dev.hywmill.military.doctrine.Doctrine;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * World glue of the M3 garrison: one instance per server (owned by {@code HywMillRuntime}),
 * runtime state only; the roster in the ledger is the persistent truth.
 *
 * <ul>
 *   <li>{@link #slot}: once per village per ledger interval (its own staggered offset): inactive-time
 *       exclusion, levy accrual, reconciliation, starting grant, recruitment, throttled spawning.</li>
 *   <li>{@link #afterScan}: after each M2 threat scan: deployment from M2's threats, and return.</li>
 *   <li>Entity join/death/leave handlers: O(1) tag checks; join adjudication against duplicates.</li>
 * </ul>
 * Entities are only ever found by UUID ({@code ServerLevel.getEntity}); nothing scans HYW entities.
 */
public final class GarrisonService {
    public static final String C_DUPLICATES = "garrison.duplicatesDiscarded";
    public static final String C_ADOPTED = "garrison.adopted";
    public static final String C_ORPHANS = "garrison.orphans";

    private final PerfCounters perf;
    private final Map<UUID, VillageRt> villages = new HashMap<>();
    private final Map<String, Long> counters = new HashMap<>();
    private long budgetTick = -1;
    private int spawnsThisTick;

    /** Runtime (not persisted) garrison state of one village. Server thread only. */
    static final class VillageRt {
        long lastSlotTick = Long.MIN_VALUE;
        long activeSince;
        int deathsThisAlert;
        AlertState lastAlert = AlertState.CALM;
        Map<UUID, UUID> assignments = Map.of();
        Set<UUID> reserve = Set.of();
        boolean recalled;
        String lastBlocker = "";
        /** M5-G: target inputs must be authoritative before the target may grow (see ScalingGate). */
        final ScalingGate gate = new ScalingGate();
        boolean authoritative;
        int computedTarget;
        /** Post-M5 garrison rest: the village's soldiers are out of the world while nobody is near; since when it has been quiet. */
        boolean resting;
        long quietSince = -1;
    }

    /** Post-M5 garrison rest: a player within this many blocks of a village's edge wakes it; beyond REST_RANGE for REST_DELAY it rests. */
    public static final int WAKE_RANGE = 96, REST_RANGE = 128;
    public static final long REST_DELAY = 600;
    public static final int WAKE_BURST = 24;
    public static final String C_RESTED = "garrison.rested";

    public GarrisonService(PerfCounters perf) {
        this.perf = perf;
    }

    public long counter(String key) {
        return counters.getOrDefault(key, 0L);
    }

    private void count(String key) {
        counters.merge(key, 1L, Long::sum);
    }

    // ------------------------------------------------------------------ slot

    /** The per-village garrison slot. Only called for active villages. */
    public void slot(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, long tick) {
        UnitProvider units = Services.units();
        if (units == null) {
            return;
        }
        long t0 = perf.start();
        GarrisonSettings s = HywMillConfig.garrison();
        GarrisonRoster r = roster(rec, tick);
        VillageRt v = villages.computeIfAbsent(rec.villageId, k -> new VillageRt());
        long prevSlot = v.lastSlotTick;
        if (v.lastSlotTick == Long.MIN_VALUE || tick - v.lastSlotTick > s.maxActiveStep()) {
            v.activeSince = tick;
        }
        v.lastSlotTick = tick;
        boolean settled = tick - v.activeSince >= s.settleTicks();

        GarrisonTables tables = GarrisonTables.current();
        GarrisonTable table = tables.forCulture(rec.culture);
        MilitaryTier tier = rec.tier;
        int computed = target(rec, table);
        v.authoritative = v.gate.observe(inputsKey(rec), tick, v.activeSince, rec.lastUpdateTick,
                rec.updateCount > 0 && !rec.needsRecompute, s.settleTicks());
        v.computedTarget = computed;
        int target = ScalingGate.gated(computed, v.authoritative, r.live());
        int tierMax = table.tier(tier).maxUnits();

        Reconciler.excludeInactive(r, tick, s);
        Recruitment.accrue(r, tick, s.maxActiveStep(), Recruitment.dailyRate(rec.capacity, target, tier, table),
                Recruitment.poolCap(target, tier, table));

        for (RosterEntry e : r.entries()) {
            if (!e.state().terminal() && !units.isValidUnitType(e.entityType)) {
                e.transition(UnitState.LOST, tick, LossReason.REMOVED);
                r.totals.lost++;
                HmLog.warn("Garrison slot {} of village {} has unknown entity type {}: LOST(REMOVED)", e.shortId(), rec.villageId, e.entityType);
            }
        }
        MinecraftServer server = overworld.getServer();
        long step = prevSlot == Long.MIN_VALUE ? 0 : Math.min(Math.max(0, tick - prevSlot), s.maxActiveStep());
        java.util.function.Predicate<RosterEntry> awayUnloaded = e -> e.duty.errand()
                && !overworld.isPositionEntityTicking(new BlockPos((int) e.lastSeenX, (int) e.lastSeenY, (int) e.lastSeenZ));
        for (Reconciler.Event ev : Reconciler.reconcile(r, rec.factionId, id -> observe(server, units, id), tick, settled, s, awayUnloaded, step)) {
            switch (ev.kind()) {
                case CAPTURED -> {
                    Entity ent = find(server, ev.entityUuid());
                    if (ent != null) {
                        GarrisonAttachments.clear(ent);
                    }
                    HmLog.info("Garrison unit {} of village '{}' has a new owner ({}): LOST(CAPTURED); owner left unchanged",
                            ev.entry(), rec.name, ent != null ? units.ownerOf(ent) : "?");
                }
                case MISSING -> HmLog.info("Garrison unit {} of village '{}' not seen for {} active ticks: MISSING", ev.entry(), rec.name, s.missingGrace());
                case LOST_TIMEOUT -> HmLog.info("Garrison unit {} of village '{}' missing too long: LOST(MISSING_TIMEOUT)", ev.entry(), rec.name);
                case RECOVERED -> HmLog.info("Garrison unit {} of village '{}' found again: RECOVERED", ev.entry(), rec.name);
                case ADOPTED -> {
                    count(C_ADOPTED);
                    HmLog.info("Garrison slot {} of village '{}' adopted its loaded unit (roster was older than the entity)", ev.entry(), rec.name);
                }
                case CONFIRMED -> HmLog.diag("Garrison unit {} of village '{}' confirmed", ev.entry(), rec.name);
            }
        }

        int equipmentLevel = Recruitment.equipmentLevel(tier, table);
        if (s.enabled() && !r.startingGranted && rec.updateCount > 0 && !rec.needsRecompute && target > 0) {
            List<RosterEntry> granted = Recruitment.grantStarting(r, rec.villageId, target, tier, table, tables.units(), equipmentLevel, tick);
            if (r.startingGranted) {
                HmLog.info("Starting garrison granted to village '{}' ({} {}, capacity {}, target {}): {}", rec.name, rec.culture, tier,
                        rec.capacity, target, granted.stream().map(e -> e.unitKey).toList());
            }
        }

        AlertState alert = alertState(rec.villageId);
        List<UnitSpec> eligible = Recruitment.eligibleUnits(tier, table, tables.units());
        UnitSpec next = Recruitment.chooseUnit(rec.villageId, r.nextSeq, eligible, table.composition(), Recruitment.liveCounts(r));
        // post-M5: the alarm of a coming siege opened the coffers: regular recruitment four times as fast for a while
        long interval = tick < r.coffersUntil ? Math.max(20, s.recruitInterval() / 4) : s.recruitInterval();
        Recruitment.Blocker blocker = Recruitment.blocker(r, s.enabled(), alert == AlertState.CALM, target, tierMax, tick, interval, next);
        if (blocker == Recruitment.Blocker.NONE && MobilizationService.inSiegeBattle(ledger, rec)) {
            blocker = Recruitment.Blocker.NOT_CALM; // no recruiting while a siege is being fought at the village
        }
        if (blocker == Recruitment.Blocker.NONE) {
            RosterEntry e = Recruitment.recruitPaid(r, rec.villageId, next, equipmentLevel, tick);
            HmLog.info("Village '{}' recruits {} ({} levy left, {}/{} target)", rec.name, e, String.format("%.2f", r.levyPoints), r.live(), target);
        }
        v.lastBlocker = blocker.name();

        r.pruneTerminal(tick, s.terminalRetention());
        ledger.setDirty();
        // the slot's own work; spawning is measured per unit as garrison.spawn
        perf.stop("garrison.slot", t0);
        if (rest(overworld, ledger, rec, r, v, alert, tick)) {
            return; // nobody near: the garrison rests out of the world (nothing to spawn either)
        }
        if (s.enabled() && settled && (alert == AlertState.CALM || alert == AlertState.RECOVERY) && !MobilizationService.inSiegeBattle(ledger, rec)) {
            // wartime levies muster fast: a village raising a whole levy at once fills its ranks in a few slots, not minutes
            boolean levies = r.entries().stream().anyMatch(e -> e.mobilized && e.state() == UnitState.RECRUITED);
            spawnPending(overworld, rec, r, table, tables, units, s, tick, levies ? Math.max(s.spawnsPerSlot(), LEVY_BURST) : s.spawnsPerSlot());
            respawnStowed(overworld, rec, r, tick, LEVY_BURST);
        }
    }

    /**
     * Post-M5 garrison rest (performance): soldiers nobody can see still cost the server their AI every tick. A village with
     * no player within {@link #REST_RANGE} blocks of its edge for {@link #REST_DELAY} ticks, calm and not besieged, takes its
     * garrison out of the world (stowed at home; nothing is lost: they count, recruit, march and defend as before). A player
     * within {@link #WAKE_RANGE}, an alert or a siege brings them back, {@link #WAKE_BURST} a slot. Returns true while resting.
     */
    private boolean rest(ServerLevel overworld, GarrisonLedger ledger, VillageRecord rec, GarrisonRoster r, VillageRt v, AlertState alert, long tick) {
        int radius = rec.villageRadius > 0 ? rec.villageRadius : 48;
        boolean busy = alert != AlertState.CALM || SiegeService.against(ledger, rec.villageId) != null || rec.loneBuilding;
        double wake = radius + WAKE_RANGE, keep = radius + REST_RANGE;
        double nearest = Double.MAX_VALUE;
        for (net.minecraft.server.level.ServerPlayer p : overworld.players()) {
            if (!p.isSpectator()) {
                nearest = Math.min(nearest, Math.hypot(p.getX() - rec.center.getX(), p.getZ() - rec.center.getZ()));
            }
        }
        if (busy || nearest < (v.resting ? wake : keep)) {
            v.quietSince = -1;
            if (v.resting) {
                v.resting = false;
                int n = respawnStowed(overworld, rec, r, tick, WAKE_BURST);
                HmLog.info("Village '{}' wakes: {} soldier(s) back in the world ({})", rec.name, n, busy ? "alert or siege" : "a player is near");
            }
            return false;
        }
        if (v.quietSince < 0) {
            v.quietSince = tick;
        }
        if (!v.resting && tick - v.quietSince >= REST_DELAY) {
            int n = 0;
            for (RosterEntry e : r.entries()) {
                if ((e.state() == UnitState.GARRISONED || e.state() == UnitState.RECOVERED) && e.entityUuid != null && e.duty.standing()
                        && e.extra.isEmpty() && !e.wounded && !r.isArsenal(e)) {
                    stow(overworld, e);
                    n++;
                }
            }
            v.resting = true;
            count(C_RESTED);
            ledger.setDirty();
            HmLog.info("Village '{}' rests: {} soldier(s) out of the world while nobody is near", rec.name, n);
        }
        if (v.resting) {
            // recruits of a resting village join the garrison at home, out of the world like the rest (they come with it on waking)
            for (RosterEntry e : r.entries()) {
                if (e.state() == UnitState.RECRUITED && e.entityUuid == null) {
                    e.transition(UnitState.SPAWNED, tick);
                    e.transition(UnitState.GARRISONED, tick);
                    ledger.setDirty();
                }
            }
        }
        return v.resting;
    }

    /** Whether the village's garrison is resting out of the world (nobody near). */
    public boolean resting(UUID village) {
        VillageRt v = villages.get(village);
        return v != null && v.resting;
    }

    /**
     * Post-M5: slots at home without an entity (a host brought home by an admin while its village was unloaded) reappear in
     * spaced ranks round the anchor, up to {@code max} per slot, once it is loaded. Returns how many.
     */
    static int respawnStowed(ServerLevel overworld, VillageRecord rec, GarrisonRoster r, long tick, int max) {
        BlockPos anchor = anchorOf(rec);
        if (!overworld.isPositionEntityTicking(anchor)) {
            return 0;
        }
        int n = 0, i = 0;
        for (RosterEntry e : new ArrayList<>(r.entries())) {
            if (n >= max) {
                break;
            }
            UnitState st = e.state();
            if ((st != UnitState.GARRISONED && st != UnitState.RECOVERED) || e.entityUuid != null || e.duty.away() || !e.extra.isEmpty()) {
                continue;
            }
            Vec3 spot = spotNear(overworld, anchor.offset((i % 8) * 3 - 10, 0, (i / 8) * 3 + 3), e.rosterId);
            i++;
            if (spot != null && materialize(overworld, rec, e, spot, BlockPos.containing(spot), tick)) {
                n++;
            }
        }
        if (n > 0) {
            HmLog.info("Village '{}': {} soldier(s) brought home while it was unloaded rejoin the garrison", rec.name, n);
        }
        return n;
    }

    /** The garrison target of a village from its record (M5-G formula; the scaling gate is applied by the slot). */
    public static int target(VillageRecord rec, GarrisonTable table) {
        return Recruitment.target(new Recruitment.TargetInputs(rec.capacity, rec.adults, rec.population, rec.buildingRoles,
                rec.fortification, rec.type), rec.tier, rec.loneBuilding, table);
    }

    static int inputsKey(VillageRecord rec) {
        return Objects.hash(rec.tier, rec.capacity, rec.loneBuilding, rec.adults, rec.population, rec.fortification, rec.buildingRoles,
                rec.type, rec.culture);
    }

    /** Whether the village's target inputs were authoritative at its last slot (false before the first slot). */
    public boolean scalingAuthoritative(UUID village) {
        VillageRt v = villages.get(village);
        return v != null && v.authoritative;
    }

    /** The village's garrison target as its last slot acted on it, or the computed target if it has had no slot. */
    public int effectiveTarget(VillageRecord rec) {
        VillageRt v = villages.get(rec.villageId);
        int computed = target(rec, GarrisonTables.current().forCulture(rec.culture));
        GarrisonRoster r = rec.hywRoster;
        return v == null || r == null ? computed : ScalingGate.gated(computed, v.authoritative, r.live());
    }

    /** The village's roster, created on first use (new village or migrated from ledger format 3). */
    public static GarrisonRoster roster(VillageRecord rec, long tick) {
        if (rec.hywRoster == null) {
            rec.hywRoster = new GarrisonRoster(tick);
            HmLog.info("Garrison roster created for village '{}' {} (starting grant pending)", rec.name, rec.villageId);
        }
        return rec.hywRoster;
    }

    /** Spawns up to {@code max} RECRUITED entries (bounded by the per-tick budget). Returns the number spawned. */
    /** Most mobilized levies one village musters (spawns) in one garrison slot. */
    public static final int LEVY_BURST = 8;

    public int spawnPending(ServerLevel overworld, VillageRecord rec, GarrisonRoster r, GarrisonTable table, GarrisonTables tables,
                            UnitProvider units, GarrisonSettings s, long tick, int max) {
        if (budgetTick != tick) {
            budgetTick = tick;
            spawnsThisTick = 0;
        }
        BlockPos anchor = anchor(rec);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!overworld.hasChunk((anchor.getX() >> 4) + dx, (anchor.getZ() >> 4) + dz)) {
                    return 0; // anchor terrain not loaded: never force-load, retry next slot
                }
            }
        }
        EquipmentProvider eq = Services.equipment(table.equipmentProvider());
        if (eq == null) {
            HmLog.warnThrottled("garrison-provider-" + table.equipmentProvider(), 600_000L,
                    "Equipment provider '{}' unavailable; using 'hyw'", table.equipmentProvider());
            eq = Services.equipment("hyw");
        }
        if (eq == null) {
            return 0;
        }
        int n = 0;
        int tickCap = max > s.spawnsPerSlot() ? Math.max(s.spawnsPerTick(), max) : s.spawnsPerTick(); // a levy burst gets its own room
        for (RosterEntry e : new ArrayList<>(r.entries())) {
            if (n >= max || spawnsThisTick >= tickCap) {
                break;
            }
            if (e.state() != UnitState.RECRUITED || !units.isValidUnitType(e.entityType)) {
                continue;
            }
            // the defending position first (M3 search, unchanged); the village centre only for this attempt if it has no safe spot
            SpawnSpots.Choice<Vec3> choice = SpawnSpots.choose(anchor, rec.center, e.rosterId, (a, id) -> findSpot(overworld, a, id));
            if (choice == null) {
                HmLog.warnThrottled("garrison-spot-" + rec.villageId, 60_000L,
                        "No safe spawn spot near {} or the village centre {} for the garrison of village '{}'; retrying next slot",
                        anchor.toShortString(), rec.center.toShortString(), rec.name);
                break;
            }
            Vec3 pos = choice.spot();
            if (choice.fallback()) {
                HmLog.info("No safe spawn spot near the defending position {} of village '{}'; slot {} spawns near the village centre {}",
                        anchor.toShortString(), rec.name, e.shortId(), rec.center.toShortString());
            }
            UnitSpec spec = tables.units().getOrDefault(e.unitKey,
                    new UnitSpec(e.unitKey, e.entityType, UnitClass.LINE, 1, MilitaryTier.WATCH, true));
            long t0 = perf.start();
            GarrisonTag tag = r.beginSpawn(rec.villageId, e, tick);
            int[] livery = LiveryService.of(overworld, rec); // post-M5: the village's colours
            e.equipRole = dev.hywmill.garrison.equip.EquipmentProfiles.stamp(e.mobilized ? dev.hywmill.garrison.equip.EquipmentProfiles.LEVY : "", livery); // equipped below with the current data
            SpawnResult res = units.spawn(overworld, new SpawnRequest(spec, rec.factionId, e.entityUuid, pos, anchor, e.equipmentLevel,
                    s.equipmentDrops(), tag, eq, new EquipmentProvider.Context(rec.culture,
                    dev.hywmill.garrison.equip.EquipmentProfiles.gearTier(e.mobilized, e.equipmentLevel, rec.tier),
                    e.mobilized ? dev.hywmill.garrison.equip.EquipmentProfiles.LEVY : "",
                    dev.hywmill.garrison.equip.EquipmentProfiles.classRole(spec.unitClass()), e.rosterId).withLivery(livery)));
            perf.stop("garrison.spawn", t0);
            spawnsThisTick++;
            if (res.ok()) {
                r.spawned(e, res.appliedLevel(), tick);
                n++;
                HmLog.info("Garrison unit spawned for village '{}': {} at {}", rec.name, e, BlockPos.containing(pos).toShortString());
            } else {
                r.revertSpawn(e, tick);
                HmLog.warn("Garrison spawn failed for village '{}' slot {}: {}", rec.name, e.shortId(), res.failure());
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ post-M5 sieges: stow and materialize

    /**
     * Takes a slot's unit out of the world for a siege march: the slot drops its entity first (so the discard is not a
     * loss and the Reconciler ignores the slot), then a loaded entity and its mount are discarded. An unloaded entity
     * stays in its chunk; if it ever loads, {@link JoinAdjudicator} refuses it as a duplicate. The slot keeps its state.
     */
    public static void stow(ServerLevel overworld, RosterEntry e) {
        UUID id = e.entityUuid;
        e.entityUuid = null;
        Entity ent = id != null ? find(overworld.getServer(), id) : null;
        if (ent != null) {
            Entity root = ent.getRootVehicle();
            if (root != ent && GarrisonAttachments.get(root) == null) {
                root.discard(); // an untracked mount (an HYW rider's horse) leaves with its rider
            } else if (root != ent) {
                ent.stopRiding(); // a tracked vehicle (a siege engine) has its own slot
            }
            for (Entity passenger : new ArrayList<>(ent.getPassengers())) {
                passenger.stopRiding(); // an engineer on this engine stays with its own slot
            }
            ent.discard();
        }
    }

    /**
     * Brings a stowed slot back into the world at {@code pos} as its next generation (deterministic UUID), equipped as the
     * village's garrison. The slot's state is unchanged. Returns false (and leaves the slot stowed) if the spawn failed.
     */
    public static boolean materialize(ServerLevel overworld, VillageRecord rec, RosterEntry e, Vec3 pos, BlockPos home, long tick) {
        UnitProvider units = Services.units();
        if (units == null || e.entityUuid != null || e.state().terminal() || e.wounded || !units.isValidUnitType(e.entityType)) {
            return false; // post-M5: a wounded soldier stays off the field until the next dawn
        }
        GarrisonTables tables = GarrisonTables.current();
        GarrisonTable table = tables.forCulture(rec.culture);
        EquipmentProvider eq = Services.equipment(table.equipmentProvider());
        if (eq == null) {
            eq = Services.equipment("hyw");
        }
        if (eq == null) {
            return false;
        }
        UnitSpec known = tables.units().get(e.unitKey);
        if (known == null) {
            // not a garrison unit (a post-M5 war engine or engineer, a bought engine): HYW's own equipment, no profile overlay
            EquipmentProvider base = Services.equipment("hyw");
            eq = base != null ? base : eq;
        }
        UnitSpec spec = known != null ? known : new UnitSpec(e.unitKey, e.entityType, UnitClass.LINE, 1, MilitaryTier.WATCH, true);
        e.generation++;
        e.entityUuid = GarrisonTag.entityUuid(e.rosterId, e.generation);
        GarrisonTag tag = new GarrisonTag(rec.villageId, e.rosterId, e.generation);
        boolean merc = !e.mercLook.isEmpty();
        // garrison soldiers wear the village's colours; engines and hired mercenaries (their company's look) do not
        int[] livery = known != null && (!merc || e.extra.equals("household")) ? LiveryService.of(overworld, rec) : null; // the lord's guard wears his colours
        String role = merc ? dev.hywmill.garrison.equip.EquipmentProfiles.LOOK_PREFIX + e.mercLook
                : e.mobilized ? dev.hywmill.garrison.equip.EquipmentProfiles.LEVY : "";
        e.equipRole = dev.hywmill.garrison.equip.EquipmentProfiles.stamp(role, livery);
        SpawnResult res = units.spawn(overworld, new SpawnRequest(spec, rec.factionId, e.entityUuid, pos, home, e.equipmentLevel,
                HywMillConfig.garrison().equipmentDrops(), tag, eq, new EquipmentProvider.Context(rec.culture,
                dev.hywmill.garrison.equip.EquipmentProfiles.gearTier(e.mobilized, e.equipmentLevel, rec.tier), // mercenaries are mobilized: a levy's tier
                role, dev.hywmill.garrison.equip.EquipmentProfiles.classRole(spec.unitClass()), e.rosterId).withLivery(livery)));
        if (!res.ok()) {
            e.generation = Math.max(0, e.generation - 1);
            e.entityUuid = null;
            HmLog.warn("Siege: could not bring slot {} of village '{}' back into the world: {}", e.shortId(), rec.name, res.failure());
            return false;
        }
        e.equipmentLevel = res.appliedLevel();
        e.seen(tick, BlockPos.containing(pos).getX(), BlockPos.containing(pos).getY(), BlockPos.containing(pos).getZ());
        return true;
    }

    /** A safe spot near {@code anchor} for a slot (loaded chunks only), or null. */
    @Nullable
    public static Vec3 spotNear(ServerLevel level, BlockPos anchor, UUID rosterId) {
        return findSpot(level, anchor, rosterId);
    }

    /** Spawn anchor: Millénaire's defending position (as resolved at the last profile refresh), else the village centre. */
    BlockPos anchor(VillageRecord rec) {
        return anchorOf(rec);
    }

    /** The preferred spawn anchor (read-only; for the garrison summary). */
    public static BlockPos spawnAnchor(VillageRecord rec) {
        return anchorOf(rec);
    }

    static BlockPos anchorOf(VillageRecord rec) {
        dev.hywmill.core.HywMillRuntime rt = dev.hywmill.core.HywMillRuntime.get();
        VillageDefenseState st = rt != null ? rt.defense().get(rec.villageId) : null;
        if (st != null && st.defendingPos() != null) {
            DefenseCoordinator.Pos p = st.defendingPos();
            return BlockPos.containing(p.x(), p.y(), p.z());
        }
        return rec.center;
    }

    /** First safe candidate spot (deterministic order, at most 24 checked, loaded chunks only). */
    @Nullable
    static Vec3 findSpot(ServerLevel level, BlockPos anchor, UUID rosterId) {
        for (int[] o : SpawnSpots.candidates(rosterId)) {
            int x = anchor.getX() + o[0];
            int z = anchor.getZ() + o[1];
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y > anchor.getY() + 3 || y < anchor.getY() - 6) {
                continue; // roofs and pits
            }
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos below = feet.below();
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                    || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    || !level.getFluidState(feet).isEmpty() || !level.getFluidState(below).isEmpty()) {
                continue;
            }
            if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(feet), LivingEntity::isAlive).isEmpty()) {
                continue; // taken: many units spawned on one block die of entity cramming (and drop their gear)
            }
            return Vec3.atBottomCenterOf(feet);
        }
        return null;
    }

    // ------------------------------------------------------------------ village deletion

    /** Called when the village list is re-read: villages absent past the grace period lose their garrison. */
    public void goneCheck(ServerLevel overworld, GarrisonLedger ledger, Set<UUID> present, long tick) {
        GarrisonSettings s = HywMillConfig.garrison();
        for (VillageRecord rec : ledger.all()) {
            GarrisonRoster r = rec.hywRoster;
            if (r == null) {
                continue;
            }
            boolean wasGone = r.goneSinceTick >= 0;
            List<RosterEntry> lost = Reconciler.villageGone(r, present.contains(rec.villageId), tick, s);
            if (!wasGone && r.goneSinceTick >= 0) {
                HmLog.info("Village '{}' {} is missing from Millénaire's village list; garrison grace period {} ticks", rec.name,
                        rec.villageId, s.villageGoneGrace());
                ledger.setDirty();
            }
            if (!lost.isEmpty()) {
                int applied = 0;
                for (RosterEntry e : lost) {
                    Entity ent = e.entityUuid != null ? find(overworld.getServer(), e.entityUuid) : null;
                    if (ent != null) {
                        applyOrphanPolicy(ent, s);
                        applied++;
                    }
                }
                HmLog.info("Village '{}' {} is gone: {} garrison slot(s) LOST(VILLAGE_GONE); orphan policy {} applied to {} loaded unit(s)",
                        rec.name, rec.villageId, lost.size(), s.orphanPolicy(), applied);
                villages.remove(rec.villageId);
                ledger.setDirty();
            }
        }
    }

    private void applyOrphanPolicy(Entity ent, GarrisonSettings s) {
        count(C_ORPHANS);
        if (s.orphanPolicy() == GarrisonSettings.OrphanPolicy.DISCARD) {
            ent.discard();
        } else {
            GarrisonAttachments.clear(ent);
        }
    }

    // ------------------------------------------------------------------ entity events

    /**
     * Join adjudication for tagged units. Returns false if the join must be cancelled (duplicate,
     * or orphan under DISCARD).
     */
    public boolean onJoin(Entity entity, ServerLevel level) {
        GarrisonTag tag = GarrisonAttachments.get(entity);
        if (tag == null) {
            return true;
        }
        UnitProvider units = Services.units();
        if (units == null) {
            return true;
        }
        long t0 = perf.start();
        ServerLevel overworld = level.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord rec = ledger.get(tag.villageId());
        GarrisonRoster roster = rec != null ? rec.hywRoster : null;
        UUID faction = dev.hywmill.faction.FactionIds.forVillage(tag.villageId());
        long tick = overworld.getGameTime();
        JoinAdjudicator.Decision d = JoinAdjudicator.onJoin(roster, tag, entity.getUUID(), units.ownerOf(entity), faction, tick);
        boolean keep = true;
        switch (d) {
            case BIND -> {
                RosterEntry e = roster.entry(tag.rosterId());
                if (e != null) {
                    e.seen(tick, entity.getBlockX(), entity.getBlockY(), entity.getBlockZ());
                }
            }
            case ADOPT -> {
                count(C_ADOPTED);
                HmLog.info("Garrison unit {} adopted by its slot (roster was older than the entity): {}", entity.getUUID(), roster.entry(tag.rosterId()));
                ledger.setDirty();
            }
            case DUPLICATE -> {
                count(C_DUPLICATES);
                keep = false;
                HmLog.warn("Duplicate garrison unit refused: {} claims slot {} gen {} of village {} ({}); slot is {}", entity.getUUID(),
                        tag.rosterId(), tag.generation(), tag.villageId(), units.describe(entity),
                        roster != null ? String.valueOf(roster.entry(tag.rosterId())) : "none");
                ledger.setDirty();
            }
            case RELEASE -> {
                GarrisonAttachments.clear(entity);
                HmLog.info("Garrison unit {} has a new owner ({}): released from village {}", entity.getUUID(), units.ownerOf(entity), tag.villageId());
                ledger.setDirty();
            }
            case ORPHAN -> {
                GarrisonSettings s = HywMillConfig.garrison();
                count(C_ORPHANS);
                if (s.orphanPolicy() == GarrisonSettings.OrphanPolicy.DISCARD) {
                    keep = false;
                } else {
                    GarrisonAttachments.clear(entity);
                }
                HmLog.info("Orphaned garrison unit {} of village {}: {}", entity.getUUID(), tag.villageId(), keep ? "kept as an ordinary HYW unit" : "discarded");
            }
        }
        perf.stop("garrison.event", t0);
        return keep;
    }

    public void onDeath(LivingEntity entity, ServerLevel level) {
        GarrisonTag tag = GarrisonAttachments.get(entity);
        if (tag == null) {
            return;
        }
        long t0 = perf.start();
        ServerLevel overworld = level.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord rec = ledger.get(tag.villageId());
        GarrisonRoster r = rec != null ? rec.hywRoster : null;
        RosterEntry e = r != null ? r.entry(tag.rosterId()) : null;
        if (e == null || e.state().terminal() || !entity.getUUID().equals(e.entityUuid)) {
            perf.stop("garrison.event", t0);
            return;
        }
        long tick = overworld.getGameTime();
        GarrisonSettings s = HywMillConfig.garrison();
        e.transition(UnitState.DEAD, tick, LossReason.KILLED);
        if (r.isArsenal(e)) {
            // post-M5 war arsenal: a destroyed engine (or its engineer) is gone for this war; no recruitment effects
            HmLog.info("War engine of village '{}' destroyed: {} (not replaced during this war)", rec.name, e);
            ledger.setDirty();
            perf.stop("garrison.event", t0);
            return;
        }
        ErrandService.onDeath(rec, e, tick); // M5-5: a soldier lost on a player's errand
        r.totals.killed++;
        Recruitment.cooldown(r, tick, s.deathCooldown(), s.recruitInterval());
        VillageRt v = villages.computeIfAbsent(rec.villageId, k -> new VillageRt());
        if (alertState(rec.villageId) != AlertState.CALM) {
            v.deathsThisAlert++;
            int target = target(rec, GarrisonTables.current().forCulture(rec.culture));
            if (Recruitment.wipedOut(v.deathsThisAlert, target)) {
                Recruitment.cooldown(r, tick, s.wipeoutCooldown(), s.recruitInterval());
                HmLog.info("Village '{}' garrison wiped out ({} of target {} killed in this alert): recruitment cooldown {} ticks",
                        rec.name, v.deathsThisAlert, target, s.wipeoutCooldown());
            }
        }
        HmLog.info("Garrison unit of village '{}' killed: {} (no respawn; replacement is a new recruit)", rec.name, e);
        ledger.setDirty();
        perf.stop("garrison.event", t0);
    }

    /** Removal that is neither death nor unload (discarded by a command or another mod): LOST(REMOVED). */
    public void onLeave(Entity entity, ServerLevel level) {
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason != Entity.RemovalReason.DISCARDED) {
            return;
        }
        GarrisonTag tag = GarrisonAttachments.get(entity);
        if (tag == null) {
            return;
        }
        ServerLevel overworld = level.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord rec = ledger.get(tag.villageId());
        GarrisonRoster r = rec != null ? rec.hywRoster : null;
        RosterEntry e = r != null ? r.entry(tag.rosterId()) : null;
        if (e == null || e.state().terminal() || !entity.getUUID().equals(e.entityUuid)) {
            return;
        }
        e.transition(UnitState.LOST, overworld.getGameTime(), LossReason.REMOVED);
        r.totals.lost++;
        HmLog.info("Garrison unit of village '{}' removed from the world (discarded): {}", rec.name, e);
        ledger.setDirty();
    }

    // ------------------------------------------------------------------ deployment

    /**
     * Called after every M2 threat scan of a village. M2 decided the alert state and the threats;
     * this only assigns garrison units to M2's threats (M2's own coordinator rules) and brings
     * them back afterwards.
     */
    public void afterScan(ServerLevel level, UUID village, AlertState state, Doctrine doctrine, List<DefenseCoordinator.ThreatView> threats,
                          Map<UUID, LivingEntity> threatEntities, DefenseCoordinator.Pos anchor) {
        VillageRt v = villages.get(village);
        if (state == AlertState.CALM && v != null && v.lastAlert != AlertState.CALM) {
            v.deathsThisAlert = 0;
            v.recalled = false;
        }
        if (v != null) {
            v.lastAlert = state;
        }
        ServerLevel overworld = level.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord rec = ledger.get(village);
        GarrisonRoster r = rec != null ? rec.hywRoster : null;
        UnitProvider units = Services.units();
        if (r == null || units == null) {
            return;
        }
        if (state == AlertState.CALM && !hasMoving(r)) {
            return;
        }
        long t0 = perf.start();
        if (v == null) {
            v = villages.computeIfAbsent(village, k -> new VillageRt());
            v.lastAlert = state;
        }
        long tick = overworld.getGameTime();
        GarrisonSettings s = HywMillConfig.garrison();
        MinecraftServer server = level.getServer();
        List<Deployment.UnitView> views = new ArrayList<>();
        Map<UUID, Entity> entities = new HashMap<>();
        for (RosterEntry e : r.entries()) {
            if (e.entityUuid == null || !e.state().deployable() || e.duty.away()) {
                continue; // M4: a raid contingent is away with its village's raid, not part of the home defense
            }
            Entity ent = find(server, e.entityUuid);
            if (ent == null || !ent.isAlive()) {
                continue;
            }
            entities.put(e.entityUuid, ent);
            BlockPos home = units.home(ent);
            views.add(new Deployment.UnitView(e, new DefenseCoordinator.Pos(ent.getX(), ent.getY(), ent.getZ()),
                    home != null ? new DefenseCoordinator.Pos(home.getX() + 0.5, home.getY(), home.getZ() + 0.5) : null));
        }
        CombatFactionService factions = Services.factions();
        List<DefenseCoordinator.ThreatView> valid = new ArrayList<>();
        for (DefenseCoordinator.ThreatView t : threats) {
            LivingEntity te = threatEntities.get(t.id());
            // hard filter: never a target carrying one of the village's own identities (own units: faction; own residents: M5 resident identity)
            UUID teId = te != null && factions != null ? factions.relationIdentity(te) : null;
            if (te != null && te.isAlive() && (factions == null
                    || !(rec.factionId.equals(teId) || dev.hywmill.faction.FactionIds.residentsOf(rec.villageId).equals(teId)))) {
                valid.add(t);
            }
        }
        DefenseCoordinator.Result plan = state == AlertState.CALM || v.recalled || !s.enabled()
                ? DefenseCoordinator.Result.EMPTY
                : Deployment.plan(doctrine, GarrisonTables.current().forCulture(rec.culture).commitPerThreat(), state, views, valid,
                anchor, v.assignments, v.reserve);
        boolean changed = false;
        for (Deployment.Action a : Deployment.apply(views, plan.assignments(), anchor, tick, s.returnTimeout())) {
            Entity ent = entities.get(a.entry().entityUuid);
            if (ent == null) {
                continue;
            }
            if (a.kind() == Deployment.ActionKind.ENGAGE) {
                LivingEntity target = threatEntities.get(a.threat());
                if (target != null && units.target(ent) != target) {
                    units.engage(ent, target);
                    changed = true;
                }
            } else {
                units.disengage(ent);
                changed = true;
            }
        }
        if (!plan.assignments().equals(v.assignments)) {
            HmLog.diag("Garrison deployment in village {} ({}): {} | reserve {}", village, state, plan.byThreat(), plan.reserve());
            changed = true;
        }
        v.assignments = plan.assignments();
        v.reserve = plan.reserve();
        if (changed) {
            ledger.setDirty();
        }
        perf.stop("garrison.deploy", t0);
    }

    private static boolean hasMoving(GarrisonRoster r) {
        for (RosterEntry e : r.entries()) {
            if (e.state() == UnitState.DEPLOYED || e.state() == UnitState.RETURNING) {
                return true;
            }
        }
        return false;
    }

    /** Recall: every deployed unit returns now; deployment stays off until the alert is over. */
    public int recall(MinecraftServer server, VillageRecord rec) {
        GarrisonRoster r = rec.hywRoster;
        UnitProvider units = Services.units();
        if (r == null || units == null) {
            return 0;
        }
        villages.computeIfAbsent(rec.villageId, k -> new VillageRt()).recalled = true;
        long tick = server.overworld().getGameTime();
        int n = 0;
        for (RosterEntry e : r.entries()) {
            if (e.state() == UnitState.DEPLOYED && !e.duty.away()) {
                e.transition(UnitState.RETURNING, tick);
                e.duty = dev.hywmill.garrison.duty.Duty.RETURNING;
                Entity ent = find(server, e.entityUuid);
                if (ent != null) {
                    units.disengage(ent);
                }
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ helpers and queries

    public AlertState alertState(UUID village) {
        dev.hywmill.core.HywMillRuntime rt = dev.hywmill.core.HywMillRuntime.get();
        return rt == null ? AlertState.CALM : rt.defense().state(village);
    }

    public String lastBlocker(UUID village) {
        VillageRt v = villages.get(village);
        return v == null ? "" : v.lastBlocker;
    }

    public Map<UUID, UUID> assignments(UUID village) {
        VillageRt v = villages.get(village);
        return v == null ? Map.of() : v.assignments;
    }

    public boolean settled(UUID village, long tick) {
        VillageRt v = villages.get(village);
        return v != null && tick - v.activeSince >= HywMillConfig.garrison().settleTicks();
    }

    /** A loaded, living entity by UUID in any level (hash lookups only). */
    @Nullable
    public static Entity find(MinecraftServer server, UUID id) {
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null && !e.isRemoved()) {
                return e;
            }
        }
        return null;
    }

    @Nullable
    static Reconciler.Observation observe(MinecraftServer server, UnitProvider units, UUID id) {
        Entity e = find(server, id);
        if (e == null || !e.isAlive()) {
            return null;
        }
        return new Reconciler.Observation(units.ownerOf(e), e.getBlockX(), e.getBlockY(), e.getBlockZ());
    }

    public void prune(Collection<UUID> known) {
        villages.keySet().retainAll(known);
    }
}

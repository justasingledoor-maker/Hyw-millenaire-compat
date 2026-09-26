package dev.hywmill.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.hywmill.config.HywMillConfig;
import dev.hywmill.garrison.GarrisonRoster;
import dev.hywmill.garrison.GarrisonSettings;
import dev.hywmill.garrison.LossReason;
import dev.hywmill.garrison.Recruitment;
import dev.hywmill.garrison.RosterEntry;
import dev.hywmill.garrison.UnitState;
import dev.hywmill.garrison.service.GarrisonService;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.garrison.tables.GarrisonTable;
import dev.hywmill.garrison.tables.GarrisonTables;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.garrison.tag.GarrisonAttachments;
import dev.hywmill.garrison.tag.GarrisonTag;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.hywmill.core.HywMillCommands.send;

/**
 * M3 garrison commands (nearest village, like {@code village military}):
 * <ul>
 *   <li>{@code village garrison}: summary (everyone); {@code units} (op 2 or controller);
 *       {@code pause|resume|recall} (op 2 or the controller of a player-controlled village).</li>
 *   <li>{@code admin grant|purge|reconcile|setpoints|equipcheck} (op 3, like the other admin commands).</li>
 *   <li>{@code dev spawn-now|rewind|census} (op 2 and devCommands=true; tests only).</li>
 * </ul>
 */
final class GarrisonCommands {
    private static final SuggestionProvider<CommandSourceStack> UNITS = (ctx, b) -> {
        GarrisonTables.current().units().keySet().forEach(b::suggest);
        return b.buildFuture();
    };

    private GarrisonCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hywmill")
                .then(Commands.literal("village")
                        .then(Commands.literal("garrison").executes(GarrisonCommands::summary)
                                .then(Commands.literal("units").executes(GarrisonCommands::units))
                                .then(Commands.literal("duties").executes(GarrisonCommands::duties))
                                .then(Commands.literal("pause").executes(ctx -> pause(ctx, true)))
                                .then(Commands.literal("resume").executes(ctx -> pause(ctx, false)))
                                .then(Commands.literal("recall").executes(GarrisonCommands::recall))))
                .then(Commands.literal("admin").requires(s -> s.hasPermission(3))
                        .then(Commands.literal("grant")
                                .then(Commands.argument("unit", StringArgumentType.word()).suggests(UNITS)
                                        .executes(ctx -> grant(ctx, 1))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> grant(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(Commands.literal("purge").executes(GarrisonCommands::purge))
                        .then(M4SpikeCommands.equipCheck())
                        .then(Commands.literal("reconcile").executes(GarrisonCommands::reconcile))
                        .then(Commands.literal("setpoints")
                                .then(Commands.argument("points", DoubleArgumentType.doubleArg(0, 100000))
                                        .executes(GarrisonCommands::setPoints))))
                .then(Commands.literal("dev").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("spawn-now").executes(GarrisonCommands::spawnNow))
                        .then(Commands.literal("rewind")
                                .then(Commands.argument("slot", StringArgumentType.word()).executes(GarrisonCommands::rewind)))
                        .then(Commands.literal("census").executes(GarrisonCommands::census))
                        .then(Commands.literal("remove-village").executes(GarrisonCommands::removeVillage))
                        .then(Commands.literal("runas")
                                .then(Commands.argument("player", net.minecraft.commands.arguments.UuidArgument.uuid())
                                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                                .executes(GarrisonCommands::runAs))))));
    }

    private static Optional<VillageRecord> record(CommandSourceStack src) {
        return MilitaryCommands.record(src);
    }

    private static boolean mayControl(CommandSourceStack src, VillageRecord r) {
        if (src.hasPermission(2)) {
            return true;
        }
        ServerPlayer p = src.getPlayer();
        if (p != null && r.controllerPlayerId != null && r.controllerPlayerId.equals(p.getUUID())) {
            return true;
        }
        src.sendFailure(Component.literal("Only an operator or the controller of this player-controlled village may do that."));
        return false;
    }

    /** One-line garrison summary (also used by village military). */
    static String line(VillageRecord r, long tick) {
        GarrisonRoster g = r.hywRoster;
        GarrisonTable table = GarrisonTables.current().forCulture(r.culture);
        int target = Recruitment.target(r.capacity, r.tier, r.loneBuilding, table);
        int cap = table.tier(r.tier).maxUnits();
        if (g == null) {
            return "Garrison: not created yet | target " + target + " (tier cap " + cap + ")";
        }
        Map<UnitState, Integer> c = g.countByState();
        int alive = c.getOrDefault(UnitState.GARRISONED, 0) + c.getOrDefault(UnitState.DEPLOYED, 0) + c.getOrDefault(UnitState.RETURNING, 0)
                + c.getOrDefault(UnitState.RECOVERED, 0) + c.getOrDefault(UnitState.SPAWNED, 0);
        return "Garrison: " + g.live() + "/" + target + " (tier cap " + cap + ") | alive " + alive + ", recruited " + c.getOrDefault(UnitState.RECRUITED, 0)
                + ", missing " + c.getOrDefault(UnitState.MISSING, 0) + ", deployed " + c.getOrDefault(UnitState.DEPLOYED, 0)
                + ", returning " + c.getOrDefault(UnitState.RETURNING, 0) + " | levy " + String.format("%.2f", g.levyPoints) + "/"
                + String.format("%.0f", table.tier(r.tier).poolCap()) + " (+" + String.format("%.2f", Recruitment.dailyRate(r.capacity, r.tier, table))
                + "/day) | equipment level " + Recruitment.equipmentLevel(r.tier, table) + (g.paused ? " | PAUSED" : "");
    }

    private static int summary(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty()) {
            return 0;
        }
        VillageRecord r = or.get();
        long tick = src.getServer().overworld().getGameTime();
        GarrisonService gs = HywMillRuntime.require().garrison();
        send(src, "== Garrison of " + r.name + " (" + r.culture + ", " + r.tier + ", capacity " + r.capacity + ") faction " + r.factionId);
        send(src, line(r, tick));
        GarrisonRoster g = r.hywRoster;
        if (g != null) {
            send(src, "Starting grant: " + (g.startingGranted ? "done" : "pending") + " | next recruit: "
                    + (tick >= g.lastRecruitTick + HywMillConfig.GARRISON_RECRUIT_INTERVAL.get() ? "now" : "in " + (g.lastRecruitTick + HywMillConfig.GARRISON_RECRUIT_INTERVAL.get() - tick) + " ticks")
                    + " | last slot: " + gs.lastBlocker(r.villageId) + " | settled " + gs.settled(r.villageId, tick)
                    + " | alert " + gs.alertState(r.villageId));
            send(src, "Totals: recruited " + g.totals.recruited + ", spawned " + g.totals.spawned + ", killed " + g.totals.killed + ", lost "
                    + g.totals.lost + ", recovered " + g.totals.recovered + ", duplicates refused " + g.totals.duplicatesDiscarded
                    + (g.goneSinceTick >= 0 ? " | village missing since " + g.goneSinceTick : ""));
        }
        return 1;
    }

    private static int units(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty() || !mayControl(src, or.get())) {
            return 0;
        }
        VillageRecord r = or.get();
        GarrisonRoster g = r.hywRoster;
        if (g == null) {
            send(src, "No garrison roster yet.");
            return 0;
        }
        long tick = src.getServer().overworld().getGameTime();
        send(src, "Garrison units of " + r.name + ": " + g.entries().size());
        for (RosterEntry e : g.entries()) {
            send(src, " " + e + " seen=" + (e.lastSeenTick < 0 ? "never" : (tick - e.lastSeenTick) + "t ago")
                    + " at " + e.lastSeenX + "," + e.lastSeenY + "," + e.lastSeenZ + (e.paid ? "" : " unpaid"));
        }
        return g.entries().size();
    }

    /** M4: the duty plan and every living unit's duty, position and HYW home (one DUTY line per unit). */
    private static int duties(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty() || !mayControl(src, or.get())) {
            return 0;
        }
        VillageRecord r = or.get();
        GarrisonRoster g = r.hywRoster;
        dev.hywmill.garrison.service.DutyService ds = HywMillRuntime.require().duties();
        dev.hywmill.garrison.duty.DutyPlan plan = ds.plan(r.villageId);
        dev.hywmill.garrison.duty.DutyQuota q = ds.quota(r.villageId);
        send(src, "== Duties of " + r.name + " (" + r.culture + ", " + r.tier + ")" + (dev.hywmill.garrison.service.DutyService.enabled() ? "" : " | DUTIES DISABLED"));
        if (plan == null) {
            send(src, "No duty plan yet (computed on the village's next duty tick).");
        } else {
            send(src, "Plan: " + plan.sentryPosts().size() + " sentry post(s) " + posList(plan.sentryPosts()) + " | patrol " + posList(plan.patrol())
                    + " | scout posts " + posList(plan.scoutPosts()) + " | reserve " + plan.reserve().toShortString() + " | muster " + plan.muster().size());
            send(src, "Quota: " + q.sentryPairs() + " sentry pair(s), " + q.patrol() + " patrol, " + q.scouts() + " scout(s), " + q.reserve() + " reserve");
        }
        if (g == null) {
            return 0;
        }
        int n = 0;
        for (RosterEntry e : g.entries()) {
            if (!e.state().bound()) {
                continue;
            }
            net.minecraft.world.entity.Entity ent = e.entityUuid != null ? GarrisonService.find(src.getServer(), e.entityUuid) : null;
            dev.hywmill.garrison.spi.UnitProvider units = Services.units();
            net.minecraft.core.BlockPos home = ent != null && units != null ? units.home(ent) : null;
            net.minecraft.world.entity.Entity mount = ent != null && units != null ? units.mount(ent) : null;
            send(src, "DUTY " + e.shortId() + " " + e.unitKey + " " + e.state() + " " + e.duty + "/" + e.assignedDuty + "#" + e.dutyIndex
                    + " " + dev.hywmill.garrison.duty.DutyMotion.progress(e)
                    + (ent != null ? " pos " + ent.getBlockX() + "," + ent.getBlockY() + "," + ent.getBlockZ() : " unloaded")
                    + (home != null ? " home " + home.getX() + "," + home.getY() + "," + home.getZ() : "")
                    + (mount != null ? " mounted" : ""));
            n++;
        }
        return n;
    }

    private static String posList(java.util.List<net.minecraft.core.BlockPos> l) {
        StringBuilder b = new StringBuilder("[");
        for (net.minecraft.core.BlockPos p : l) {
            b.append(b.length() > 1 ? " " : "").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
        }
        return b.append(']').toString();
    }

    private static int pause(CommandContext<CommandSourceStack> ctx, boolean paused) {
        CommandSourceStack src = ctx.getSource();
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty() || !mayControl(src, or.get())) {
            return 0;
        }
        VillageRecord r = or.get();
        GarrisonService.roster(r, src.getServer().overworld().getGameTime()).paused = paused;
        GarrisonLedger.get(src.getServer().overworld()).setDirty();
        HmLog.info("Garrison recruitment of village '{}' {} by {}", r.name, paused ? "paused" : "resumed", src.getTextName());
        send(src, "Garrison recruitment of " + r.name + (paused ? " paused." : " resumed."));
        return 1;
    }

    private static int recall(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty() || !mayControl(src, or.get())) {
            return 0;
        }
        int n = HywMillRuntime.require().garrison().recall(src.getServer(), or.get());
        GarrisonLedger.get(src.getServer().overworld()).setDirty();
        send(src, "Recalled " + n + " deployed unit(s) of " + or.get().name + "; deployment stays off until the alert ends.");
        return 1;
    }

    // ---- admin ----

    private static int grant(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack src = ctx.getSource();
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty()) {
            return 0;
        }
        VillageRecord r = or.get();
        String key = StringArgumentType.getString(ctx, "unit");
        UnitSpec u = GarrisonTables.current().units().get(key);
        if (u == null) {
            src.sendFailure(Component.literal("Unknown garrison unit '" + key + "'. Units: " + GarrisonTables.current().units().keySet()));
            return 0;
        }
        long tick = src.getServer().overworld().getGameTime();
        GarrisonRoster g = GarrisonService.roster(r, tick);
        GarrisonTable table = GarrisonTables.current().forCulture(r.culture);
        if (!Recruitment.allowedAtTier(u, r.tier, table)) {
            src.sendFailure(Component.literal("A " + r.tier + " village may not have '" + key + "' (" + u.unitClass()
                    + ", minTier " + u.minTier() + (u.enabled() ? "" : ", disabled") + ")."));
            return 0;
        }
        int headroom = table.tier(r.tier).maxUnits() - g.live();
        int n = Math.min(count, Math.max(0, headroom));
        for (int i = 0; i < n; i++) {
            g.recruit(r.villageId, u.key(), u.entityType(), Recruitment.equipmentLevel(r.tier, table), tick, false);
        }
        GarrisonLedger.get(src.getServer().overworld()).setDirty();
        HmLog.info("Admin {} granted {} x {} to the garrison of '{}'", src.getTextName(), n, key, r.name);
        send(src, "Granted " + n + " x " + key + " to " + r.name + (n < count ? " (tier cap " + table.tier(r.tier).maxUnits() + " reached)" : "")
                + "; they spawn at the next eligible garrison slot.");
        return n;
    }

    private static int purge(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty() || or.get().hywRoster == null) {
            return 0;
        }
        VillageRecord r = or.get();
        long tick = src.getServer().overworld().getGameTime();
        int n = 0;
        int discarded = 0;
        for (RosterEntry e : r.hywRoster.entries()) {
            if (e.state().terminal()) {
                continue;
            }
            UUID uuid = e.entityUuid;
            e.transition(UnitState.LOST, tick, LossReason.ADMIN);
            r.hywRoster.totals.lost++;
            n++;
            Entity ent = uuid != null ? GarrisonService.find(src.getServer(), uuid) : null;
            if (ent != null) {
                ent.discard();
                discarded++;
            }
        }
        GarrisonLedger.get(src.getServer().overworld()).setDirty();
        HmLog.info("Admin {} purged the garrison of '{}': {} slot(s) LOST(ADMIN), {} loaded unit(s) discarded", src.getTextName(), r.name, n, discarded);
        send(src, "Purged " + n + " slot(s) of " + r.name + "; " + discarded + " loaded unit(s) discarded (unloaded ones are refused when they load).");
        return n;
    }

    private static int reconcile(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty()) {
            return 0;
        }
        ServerLevel overworld = src.getServer().overworld();
        HywMillRuntime.require().garrison().slot(overworld, GarrisonLedger.get(overworld), or.get(), overworld.getGameTime());
        send(src, "Garrison slot run for " + or.get().name + ". " + line(or.get(), overworld.getGameTime()));
        return 1;
    }

    private static int setPoints(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty()) {
            return 0;
        }
        double p = DoubleArgumentType.getDouble(ctx, "points");
        GarrisonService.roster(or.get(), src.getServer().overworld().getGameTime()).levyPoints = p;
        GarrisonLedger.get(src.getServer().overworld()).setDirty();
        send(src, "Levy points of " + or.get().name + " set to " + p + " (capped by the tier pool at the next accrual).");
        return 1;
    }

    // ---- dev ----

    private static int spawnNow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UnitProvider units = Services.units();
        if (!HywMillCommands.devEnabled(src) || units == null) {
            return 0;
        }
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty()) {
            return 0;
        }
        VillageRecord r = or.get();
        ServerLevel overworld = src.getServer().overworld();
        long tick = overworld.getGameTime();
        GarrisonSettings s = HywMillConfig.garrison();
        GarrisonTable table = GarrisonTables.current().forCulture(r.culture);
        int n = HywMillRuntime.require().garrison().spawnPending(overworld, r, GarrisonService.roster(r, tick), table, GarrisonTables.current(),
                units, s, tick, 64);
        GarrisonLedger.get(overworld).setDirty();
        send(src, "spawn-now: " + n + " unit(s) spawned for " + r.name);
        return n;
    }

    private static int rewind(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!HywMillCommands.devEnabled(src)) {
            return 0;
        }
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty() || or.get().hywRoster == null) {
            return 0;
        }
        String prefix = StringArgumentType.getString(ctx, "slot");
        for (RosterEntry e : or.get().hywRoster.entries()) {
            if (e.rosterId.toString().startsWith(prefix) && !e.state().terminal()) {
                String before = e.toString();
                e.devRewind(src.getServer().overworld().getGameTime());
                GarrisonLedger.get(src.getServer().overworld()).setDirty();
                send(src, "rewind: " + before + " -> " + e + " (entity untouched)");
                return 1;
            }
        }
        src.sendFailure(Component.literal("No live slot starting with " + prefix));
        return 0;
    }

    /** Test-only census: every loaded entity tagged for the nearest village (all levels), versus the roster. */
    private static int census(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!HywMillCommands.devEnabled(src)) {
            return 0;
        }
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty()) {
            return 0;
        }
        VillageRecord r = or.get();
        UnitProvider units = Services.units();
        Map<UUID, Integer> perSlot = new HashMap<>();
        int tagged = 0;
        int factionOwned = 0;
        int badOwner = 0;
        List<String> bad = new ArrayList<>();
        for (ServerLevel l : src.getServer().getAllLevels()) {
            for (Entity e : l.getAllEntities()) {
                GarrisonTag t = GarrisonAttachments.get(e);
                if (units != null && units.isUnit(e) && r.factionId.equals(units.ownerOf(e))) {
                    factionOwned++;
                }
                if (t == null || !t.villageId().equals(r.villageId) || !e.isAlive()) {
                    continue;
                }
                tagged++;
                perSlot.merge(t.rosterId(), 1, Integer::sum);
                if (units != null && !r.factionId.equals(units.ownerOf(e))) {
                    badOwner++;
                }
                RosterEntry entry = r.hywRoster != null ? r.hywRoster.entry(t.rosterId()) : null;
                if (entry == null || entry.state().terminal() || !e.getUUID().equals(entry.entityUuid)) {
                    bad.add(e.getUUID().toString().substring(0, 8));
                }
            }
        }
        int dupSlots = (int) perSlot.values().stream().filter(v -> v > 1).count();
        int live = r.hywRoster != null ? r.hywRoster.live() : 0;
        int bound = 0;
        if (r.hywRoster != null) {
            for (RosterEntry e : r.hywRoster.entries()) {
                if (e.state().bound()) {
                    bound++;
                }
            }
        }
        send(src, "census " + r.name + ": tagged=" + tagged + " slots=" + perSlot.size() + " duplicateSlots=" + dupSlots + " unbound=" + bad.size()
                + " badOwner=" + badOwner + " factionOwned=" + factionOwned + " rosterLive=" + live + " rosterBound=" + bound + (bad.isEmpty() ? "" : " " + bad));
        return tagged;
    }

    /** Test-only: Millénaire's own village deletion path on the nearest village (G3-14). */
    private static int removeVillage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!HywMillCommands.devEnabled(src)) {
            return 0;
        }
        Optional<dev.hywmill.settlement.SettlementSource.SettlementRef> ref = HywMillCommands.nearest(src);
        if (ref.isEmpty()) {
            return 0;
        }
        boolean ok = Services.settlements().devRemove(src.getServer().overworld(), ref.get().id());
        HmLog.info("dev remove-village {} ({}): {}", ref.get().name(), ref.get().id(), ok);
        send(src, "remove-village " + ref.get().name() + " " + ref.get().id() + ": " + ok);
        return ok ? 1 : 0;
    }

    /**
     * Test-only: runs a command as a non-op fake player with the given UUID at the source position
     * (permission and controller checks on a headless server). Its chat output is logged as
     * {@code [runas <uuid8>] ...}.
     */
    private static int runAs(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!HywMillCommands.devEnabled(src)) {
            return 0;
        }
        UUID id = net.minecraft.commands.arguments.UuidArgument.getUuid(ctx, "player");
        String command = StringArgumentType.getString(ctx, "command");
        ServerLevel level = src.getLevel();
        net.neoforged.neoforge.common.util.FakePlayer fp = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(id, "hw" + id.toString().substring(0, 8)));
        fp.moveTo(src.getPosition().x, src.getPosition().y, src.getPosition().z);
        String tag = "[runas " + id.toString().substring(0, 8) + "] ";
        net.minecraft.commands.CommandSource sink = new net.minecraft.commands.CommandSource() {
            @Override
            public void sendSystemMessage(Component c) {
                HmLog.info("{}{}", tag, c.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack as = fp.createCommandSourceStack().withSource(sink);
        HmLog.info("{}permission level {} runs: {}", tag, fp.createCommandSourceStack().hasPermission(2) ? ">=2" : "<2", command);
        src.getServer().getCommands().performPrefixedCommand(as, command);
        return 1;
    }
}

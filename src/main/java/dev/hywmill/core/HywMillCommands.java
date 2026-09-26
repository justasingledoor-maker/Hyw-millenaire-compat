package dev.hywmill.core;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.hywmill.config.HywMillConfig;
import dev.hywmill.faction.CombatFactionService;
import dev.hywmill.faction.FactionMarker;
import dev.hywmill.faction.IdentityClearance;
import dev.hywmill.military.EscalationGuard;
import dev.hywmill.military.IncidentLedger;
import dev.hywmill.military.ThreatTracker;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.GarrisonUpdater;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /hywmill ...} (alias {@code /ourmod}). Read-only except for the config-gated dev
 * subcommands used by the acceptance tests.
 */
public final class HywMillCommands {
    private static final double NEAREST_RANGE = 512.0;

    private HywMillCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("hywmill")
                .then(Commands.literal("status").executes(HywMillCommands::status))
                .then(Commands.literal("village")
                        .then(Commands.literal("info").executes(HywMillCommands::villageInfo))
                        .then(Commands.literal("list").executes(HywMillCommands::villageList))
                        .then(Commands.literal("residents").requires(s -> s.hasPermission(2)).executes(HywMillCommands::villageResidents)))
                .then(Commands.literal("threats").executes(HywMillCommands::threats))
                .then(Commands.literal("incidents").requires(s -> s.hasPermission(2))
                        .executes(ctx -> incidents(ctx, 10))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> incidents(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("admin").requires(s -> s.hasPermission(3))
                        .then(Commands.literal("clear-identities")
                                .executes(ctx -> clearIdentities(ctx, false))
                                .then(Commands.literal("all").executes(ctx -> clearIdentities(ctx, true))))
                        .then(Commands.literal("restore-identities")
                                .executes(ctx -> restoreIdentities(ctx, false))
                                .then(Commands.literal("all").executes(ctx -> restoreIdentities(ctx, true)))))
                .then(Commands.literal("dev").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("playerhit")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> devPlayerHit(ctx, 1.0f, 1))
                                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0.1f, 100f))
                                                .executes(ctx -> devPlayerHit(ctx, FloatArgumentType.getFloat(ctx, "amount"), 1))
                                                .then(Commands.argument("repeat", IntegerArgumentType.integer(1, 10))
                                                        .executes(ctx -> devPlayerHit(ctx, FloatArgumentType.getFloat(ctx, "amount"),
                                                                IntegerArgumentType.getInteger(ctx, "repeat")))))))
                        .then(Commands.literal("relation")
                                .then(Commands.argument("other", UuidArgument.uuid())
                                        .executes(HywMillCommands::devRelation)))
                        .then(GarrisonSpikeCommands.spawnNode())
                        .then(GarrisonSpikeCommands.engageNode())
                        .then(GarrisonSpikeCommands.infoNode())
                        .then(M4SpikeCommands.raidState())
                        .then(M4SpikeCommands.raidPoint())
                        .then(M4SpikeCommands.negate())
                        .then(M4SpikeCommands.home())
                        .then(M4SpikeCommands.duties())
                        .then(M4SpikeCommands.equip())
                        .then(Commands.literal("inspect")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(HywMillCommands::devInspect)))));
        // Placeholder alias requested for M1 acceptance tests.
        MilitaryCommands.register(dispatcher);
        GarrisonCommands.register(dispatcher);
        dispatcher.register(Commands.literal("ourmod").redirect(root));
    }

    static void send(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        send(src, "hywmill integrations: " + Integrations.describe());
        send(src, "settlement source: " + (Services.settlements() != null ? Services.settlements().name() : "none")
                + ", faction service: " + (Services.factions() != null ? "HYW" : "none"));
        for (Map.Entry<String, String> e : Services.diagnostics().entrySet()) {
            send(src, "  " + e.getKey() + ": " + e.getValue());
        }
        HywMillRuntime rt = HywMillRuntime.require();
        send(src, "identities: markedOnJoin=" + rt.counter(FactionMarker.C_MARKED_ON_JOIN)
                + " fixedBySweep=" + rt.counter(FactionMarker.C_FIXED_BY_SWEEP)
                + " raidersSkipped=" + rt.counter(FactionMarker.C_RAIDERS_SKIPPED));
        send(src, "escalation guard: detected=" + rt.counter(EscalationGuard.C_DETECTED) + " reverted=" + rt.counter(EscalationGuard.C_REVERTED));
        IdentityClearance clearance = IdentityClearance.get(src.getServer().overworld());
        send(src, "identity clearance: markVillagers=" + HywMillConfig.MARK_VILLAGERS.get() + " clearedAll=" + clearance.all()
                + " clearedVillages=" + clearance.villages().size() + " markersRemoved=" + rt.counter(FactionMarker.C_CLEARED));
        return 1;
    }

    static Optional<SettlementSource.SettlementRef> nearest(CommandSourceStack src) {
        SettlementSource source = Services.settlements();
        if (source == null) {
            src.sendFailure(Component.literal("Millénaire integration is not active."));
            return Optional.empty();
        }
        ServerLevel overworld = src.getServer().overworld();
        if (src.getLevel() != overworld) {
            src.sendFailure(Component.literal("Millénaire villages only exist in the Overworld."));
            return Optional.empty();
        }
        Optional<SettlementSource.SettlementRef> ref = source.nearest(overworld, BlockPos.containing(src.getPosition()), NEAREST_RANGE);
        if (ref.isEmpty()) {
            src.sendFailure(Component.literal("No Millénaire village within " + (int) NEAREST_RANGE + " blocks."));
        }
        return ref;
    }

    private static int villageInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<SettlementSource.SettlementRef> ref = nearest(src);
        if (ref.isEmpty()) {
            return 0;
        }
        ServerLevel overworld = src.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord r = ledger.get(ref.get().id());
        if (r == null || r.updateCount == 0) {
            r = GarrisonUpdater.refreshOne(overworld, HywMillRuntime.require(), Services.settlements(), ledger, ref.get().id(), overworld.getGameTime()).orElse(null);
        }
        if (r == null) {
            src.sendFailure(Component.literal("Village " + ref.get().id() + " could not be read."));
            return 0;
        }
        double dist = Math.sqrt(r.center.distSqr(BlockPos.containing(src.getPosition())));
        send(src, "== " + (r.name.isEmpty() ? "(unnamed)" : r.name) + " (" + r.culture + " / " + r.type + ") at " + r.center.toShortString()
                + ", " + (int) dist + " blocks away, active=" + ref.get().active());
        send(src, "VillageId: " + r.villageId);
        send(src, "Faction UUID (synthetic): " + r.factionId);
        send(src, "Tier: " + r.tier + " | garrison: " + r.garrison + " | population: " + r.population + " (adults " + r.adults + ")");
        send(src, "Defending strength (Millénaire): " + r.defendingStrength + " | fortification: " + r.fortification);
        send(src, "Villager roles: " + r.villagerRoles + (r.ambiguousTypes.isEmpty() ? "" : " | unlisted (fallback MILITIA): " + r.ambiguousTypes));
        send(src, "Building roles: " + r.buildingRoles + " | wall segments planned/unbuilt: " + r.wallSegmentsPending
                + " | operational buildings: " + r.buildingsOperational);
        send(src, "Tags (reported only): " + r.tagCounts + " | town hall: " + r.townhallPlan);
        send(src, "Residents loaded/marked at last update: " + r.loadedResidents + "/" + r.markedResidents
                + " | last update tick " + r.lastUpdateTick + " (#" + r.updateCount + "), first seen " + r.firstSeenTick);
        ServerPlayer player = src.getPlayer();
        if (player != null) {
            int rep = Services.settlements().playerReputation(overworld, r.villageId, player.getUUID());
            send(src, "Your Millénaire reputation here: " + rep);
            CombatFactionService factions = Services.factions();
            if (factions != null) {
                send(src, "HYW relation village->you: " + factions.relation(r.factionId, player.getUUID())
                        + ", you->village: " + factions.relation(player.getUUID(), r.factionId));
            } else {
                send(src, "HYW relation: n/a (HYW integration inactive)");
            }
        }
        send(src, "Threats now: " + HywMillRuntime.require().threats().threats(r.villageId).size());
        return 1;
    }

    private static int villageList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        GarrisonLedger ledger = GarrisonLedger.get(src.getServer().overworld());
        send(src, "Ledger villages: " + ledger.all().size());
        for (VillageRecord r : ledger.all()) {
            send(src, " - " + r.name + " " + r.center.toShortString() + " tier=" + r.tier + " garrison=" + r.garrison
                    + " fort=" + r.fortification + " id=" + r.villageId);
        }
        return 1;
    }

    private static int villageResidents(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<SettlementSource.SettlementRef> ref = nearest(src);
        if (ref.isEmpty()) {
            return 0;
        }
        List<String> lines = Services.settlements().describeResidents(src.getServer().overworld(), ref.get().id());
        send(src, "Loaded residents of " + ref.get().name() + ": " + lines.size());
        lines.forEach(l -> send(src, " " + l));
        return 1;
    }

    private static int threats(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<SettlementSource.SettlementRef> ref = nearest(src);
        if (ref.isEmpty()) {
            return 0;
        }
        List<ThreatTracker.Threat> list = HywMillRuntime.require().threats().threats(ref.get().id());
        send(src, "Threats in " + ref.get().name() + ": " + list.size());
        CombatFactionService factions = Services.factions();
        for (ThreatTracker.Threat t : list) {
            send(src, " - " + (factions != null ? factions.describe(t.entity()) : t.entity().getUUID().toString()) + " " + t.reasons());
        }
        return 1;
    }

    private static int incidents(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack src = ctx.getSource();
        List<IncidentLedger.Incident> list = HywMillRuntime.require().incidents().recent(count);
        send(src, "Last " + list.size() + " combat incident(s):");
        for (IncidentLedger.Incident i : list) {
            send(src, " t=" + i.tick() + " " + i.attackerType() + "[" + shortId(i.attacker()) + " fac=" + shortId(i.attackerFaction()) + "]"
                    + " -> " + i.victimType() + "[" + shortId(i.victim()) + " fac=" + shortId(i.victimFaction()) + "]"
                    + " dmg=" + i.amount() + " inherent=" + i.attackerInherentlyHostile()
                    + " resident=" + (i.victimResidentOf() != null) + " inVillage=" + (i.insideVillage() != null));
        }
        return 1;
    }

    /**
     * Removes our faction identity marker from residents (nearest village, or all) and keeps it off,
     * persisted: loaded residents now, unloaded ones when they next load. Run before uninstalling
     * the mod; nothing can clean up after the mod is gone.
     */
    private static int clearIdentities(CommandContext<CommandSourceStack> ctx, boolean all) {
        CommandSourceStack src = ctx.getSource();
        List<UUID> villages = targetVillages(src, all);
        if (villages == null) {
            return 0;
        }
        ServerLevel overworld = src.getServer().overworld();
        IdentityClearance clearance = IdentityClearance.get(overworld);
        if (all) {
            clearance.clearAll();
        } else {
            villages.forEach(clearance::clear);
        }
        int removed = 0;
        for (UUID v : villages) {
            removed += FactionMarker.sweep(overworld, v).cleared();
        }
        HmLog.info("Identity clearance {}: removed the faction identity from {} loaded resident(s) in {} village(s)",
                all ? "for all villages" : "for village " + villages.get(0), removed, villages.size());
        send(src, "Removed the village faction identity from " + removed + " loaded resident(s) in " + villages.size()
                + " village(s). Unloaded residents are cleared when they load. Markers stay off until /hywmill admin restore-identities"
                + (all ? " all." : "."));
        return removed;
    }

    private static int restoreIdentities(CommandContext<CommandSourceStack> ctx, boolean all) {
        CommandSourceStack src = ctx.getSource();
        List<UUID> villages = targetVillages(src, all);
        if (villages == null) {
            return 0;
        }
        ServerLevel overworld = src.getServer().overworld();
        IdentityClearance clearance = IdentityClearance.get(overworld);
        if (all) {
            clearance.restoreAll();
        } else if (!clearance.restore(villages.get(0)) && clearance.all()) {
            src.sendFailure(Component.literal("All villages are cleared; use /hywmill admin restore-identities all."));
            return 0;
        }
        int marked = 0;
        for (UUID v : villages) {
            marked += FactionMarker.sweep(overworld, v).fixed();
        }
        send(src, "Identity clearance lifted for " + villages.size() + " village(s); " + marked + " loaded resident(s) re-marked"
                + (HywMillConfig.MARK_VILLAGERS.get() ? "." : " (markVillagers=false: none will be marked)."));
        return 1;
    }

    @Nullable
    private static List<UUID> targetVillages(CommandSourceStack src, boolean all) {
        SettlementSource source = Services.settlements();
        if (source == null || Services.factions() == null) {
            src.sendFailure(Component.literal("Millénaire and HYW integrations must both be active."));
            return null;
        }
        if (all) {
            return source.list(src.getServer().overworld()).stream().map(SettlementSource.SettlementRef::id).toList();
        }
        Optional<SettlementSource.SettlementRef> ref = nearest(src);
        return ref.map(r -> List.of(r.id())).orElse(null);
    }

    private static String shortId(UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }

    static boolean devEnabled(CommandSourceStack src) {
        if (!HywMillConfig.DEV_COMMANDS.get()) {
            src.sendFailure(Component.literal("Dev commands are disabled (general.devCommands=false)."));
            return false;
        }
        return true;
    }

    /**
     * Simulates a player melee hit on each target with a NeoForge FakePlayer, so HYW's player
     * retaliation path can be exercised on a headless server. Reports HYW's resulting state.
     * With {@code repeat > 1} the target is hit several times in the same tick, so every hit after
     * the first lands inside the invulnerability window (HYW still counts it; NeoForge fires no
     * damage event for it).
     */
    private static int devPlayerHit(CommandContext<CommandSourceStack> ctx, float amount, int repeat) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!devEnabled(src)) {
            return 0;
        }
        ServerLevel level = src.getLevel();
        FakePlayer fake = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5f1c7d5e-0000-4000-8000-00000000beef"), "[hywmill-test]"));
        CombatFactionService factions = Services.factions();
        int n = 0;
        for (Entity e : EntityArgument.getEntities(ctx, "targets")) {
            if (!(e instanceof LivingEntity target)) {
                continue;
            }
            fake.moveTo(target.getX() + 1.0, target.getY(), target.getZ(), 0f, 0f);
            boolean hurt = false;
            for (int i = 0; i < repeat; i++) {
                hurt |= target.hurt(level.damageSources().playerAttack(fake), amount);
            }
            n++;
            String state = "";
            if (factions != null) {
                UUID identity = factions.relationIdentity(target);
                state = " tempHostile(unit->fakePlayer)=" + (factions.isCombatUnit(target) && factions.isTemporarilyHostile(target, fake))
                        + " unitTarget=" + describeEntity(factions.currentTarget(target))
                        + " relation(target->fakePlayer)=" + (identity == null ? "none" : factions.relation(identity, fake.getUUID()));
            }
            send(src, "fake player hit " + e.getUUID() + " hurt=" + hurt + state);
        }
        return n;
    }

    private static int devInspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!devEnabled(src)) {
            return 0;
        }
        CombatFactionService factions = Services.factions();
        SettlementSource settlements = Services.settlements();
        for (Entity e : EntityArgument.getEntities(ctx, "targets")) {
            StringBuilder sb = new StringBuilder(e.getUUID().toString());
            if (settlements != null) {
                sb.append(" resident=").append(settlements.residentInfo(e).map(Object::toString).orElse("no"));
            }
            if (factions != null) {
                sb.append(" marker=").append(factions.markedIdentity(e))
                        .append(" relationId=").append(factions.relationIdentity(e));
                if (factions.isCombatUnit(e)) {
                    sb.append(" unit=").append(factions.describe(e))
                            .append(" target=").append(describeEntity(factions.currentTarget(e)));
                }
            }
            send(src, sb.toString());
        }
        return 1;
    }

    /** HYW relation between the nearest village's faction and any UUID, both directions (console-friendly). */
    private static int devRelation(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!devEnabled(src)) {
            return 0;
        }
        CombatFactionService factions = Services.factions();
        Optional<SettlementSource.SettlementRef> ref = nearest(src);
        if (factions == null || ref.isEmpty()) {
            return 0;
        }
        UUID faction = dev.hywmill.faction.FactionIds.forVillage(ref.get().id());
        UUID other = UuidArgument.getUuid(ctx, "other");
        send(src, "HYW relation " + ref.get().name() + "[" + faction + "] -> " + other + ": " + factions.relation(faction, other)
                + " | reverse: " + factions.relation(other, faction));
        return 1;
    }

    private static String describeEntity(Entity e) {
        return e == null ? "none" : e.getType().toShortString() + "[" + e.getUUID().toString().substring(0, 8) + "]";
    }
}

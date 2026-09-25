package dev.hywmill.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.hywmill.faction.CombatFactionService;
import dev.hywmill.military.ThreatTracker;
import dev.hywmill.military.classify.RoleClassifier;
import dev.hywmill.military.classify.RoleTables;
import dev.hywmill.military.defense.VillageDefenseState;
import dev.hywmill.military.doctrine.Doctrine;
import dev.hywmill.military.doctrine.DoctrineField;
import dev.hywmill.military.doctrine.DoctrineResolver;
import dev.hywmill.military.profile.MilitaryProfile;
import dev.hywmill.military.profile.ProfileCalculator;
import dev.hywmill.settlement.GarrisonLedger;
import dev.hywmill.settlement.GarrisonUpdater;
import dev.hywmill.settlement.SettlementSnapshot;
import dev.hywmill.settlement.SettlementSource;
import dev.hywmill.settlement.VillageRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static dev.hywmill.core.HywMillCommands.nearest;
import static dev.hywmill.core.HywMillCommands.send;

/**
 * M2 commands: {@code village military}, {@code doctrine get|set|reset}, {@code alerts}, {@code perf},
 * {@code dev capacity}. Doctrine changes need op level 2, or being the controller of the
 * (player-controlled) village; reputation never grants permission.
 */
final class MilitaryCommands {
    private MilitaryCommands() {}

    private static final SuggestionProvider<CommandSourceStack> FIELDS = (ctx, b) ->
            SharedSuggestionProvider.suggest(Arrays.stream(DoctrineField.values()).map(f -> f.key), b);

    private static final SuggestionProvider<CommandSourceStack> VALUES = (ctx, b) -> {
        DoctrineField f = DoctrineField.byKey(StringArgumentType.getString(ctx, "field"));
        if (f == null) {
            return b.buildFuture();
        }
        if (f.enumType() != null) {
            return SharedSuggestionProvider.suggest(Arrays.stream(f.enumType().getEnumConstants()).map(Enum::name), b);
        }
        if (f == DoctrineField.PROACTIVE || f == DoctrineField.ASSIST_PROVOKING_PLAYER) {
            return SharedSuggestionProvider.suggest(List.of("true", "false"), b);
        }
        return b.buildFuture();
    };

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hywmill")
                .then(Commands.literal("village")
                        .then(Commands.literal("military").executes(MilitaryCommands::villageMilitary)))
                .then(Commands.literal("doctrine")
                        .then(Commands.literal("get").executes(MilitaryCommands::doctrineGet))
                        .then(Commands.literal("set")
                                .then(Commands.argument("field", StringArgumentType.word()).suggests(FIELDS)
                                        .then(Commands.argument("value", StringArgumentType.word()).suggests(VALUES)
                                                .executes(MilitaryCommands::doctrineSet))))
                        .then(Commands.literal("reset")
                                .executes(ctx -> doctrineReset(ctx, null))
                                .then(Commands.argument("field", StringArgumentType.word()).suggests(FIELDS)
                                        .executes(ctx -> doctrineReset(ctx, StringArgumentType.getString(ctx, "field"))))))
                .then(Commands.literal("alerts").executes(MilitaryCommands::alerts))
                .then(Commands.literal("perf").requires(s -> s.hasPermission(2))
                        .executes(MilitaryCommands::perf)
                        .then(Commands.literal("reset").executes(ctx -> {
                            HywMillRuntime.require().perf().reset();
                            send(ctx.getSource(), "hywmill perf counters reset");
                            return 1;
                        })))
                .then(Commands.literal("dev").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("capacity").executes(MilitaryCommands::devCapacity))));
    }

    static Optional<VillageRecord> record(CommandSourceStack src) {
        Optional<SettlementSource.SettlementRef> ref = nearest(src);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        ServerLevel overworld = src.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        VillageRecord r = ledger.get(ref.get().id());
        if (r == null || r.updateCount == 0 || r.needsRecompute) {
            r = GarrisonUpdater.refreshOne(overworld, HywMillRuntime.require(), Services.settlements(), ledger, ref.get().id(),
                    overworld.getGameTime()).orElse(null);
        }
        if (r == null) {
            src.sendFailure(Component.literal("Village " + ref.get().id() + " could not be read."));
        }
        return Optional.ofNullable(r);
    }

    private static int villageMilitary(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty()) {
            return 0;
        }
        VillageRecord r = or.get();
        HywMillRuntime rt = HywMillRuntime.require();
        MilitaryProfile p = r.profile();
        DoctrineResolver.Resolved res = GarrisonUpdater.resolveDoctrine(r);
        Doctrine d = res.doctrine();
        send(src, "== Military: " + r.name + " (" + r.type + ")" + (r.loneBuilding ? " [lone building]" : "")
                + (r.controllerPlayerId != null ? " controller=" + r.controllerPlayerId : ""));
        send(src, "Tier: " + p.tier() + " | soldiers " + p.soldiers() + ", leaders " + p.leaders() + ", militia " + p.militia()
                + ", defenders " + p.defenders() + " | outlaws " + p.outlaws() + ", civilians " + p.civilians());
        send(src, "Capacity: " + p.capacity() + " | readiness: " + p.readiness() + "% | equipment: "
                + (p.equipmentScore() < 0 ? "n/a (no defender loaded yet)" : p.equipmentScore()) + " | fortification: " + p.fortification());
        send(src, "Building roles: " + p.buildingRoles() + " | infrastructure: " + p.infrastructure());
        send(src, "Doctrine: " + summary(d));
        send(src, "Doctrine sources: " + nonBaselineSources(res));
        VillageDefenseState st = rt.defense().get(r.villageId);
        List<ThreatTracker.Threat> threats = rt.threats().threats(r.villageId);
        CombatFactionService factions = Services.factions();
        if (st == null) {
            send(src, "Alert: CALM (not scanned yet)");
        } else {
            long now = src.getServer().overworld().getGameTime();
            send(src, "Alert: " + st.state() + " for " + (now - st.stateSince()) + " ticks | eligible " + st.eligible()
                    + " | committed " + st.assignments().size() + " | reserve " + st.reserve().size() + " " + shortIds(st.reserve()));
            for (Map.Entry<UUID, List<UUID>> e : st.byThreat().entrySet()) {
                send(src, " threat " + shortId(e.getKey()) + " <- " + shortIds(e.getValue()));
            }
        }
        send(src, "Active threats: " + threats.size());
        for (ThreatTracker.Threat t : threats) {
            send(src, " - " + (factions != null ? factions.describe(t.entity()) : t.entity().getUUID()) + " " + t.reasons());
        }
        send(src, "Stats: " + r.stats);
        send(src, GarrisonCommands.line(r, src.getServer().overworld().getGameTime()));
        return 1;
    }

    static String summary(Doctrine d) {
        return "radius " + d.defenseRadius() + " (offset " + d.radiusOffset() + "), proactive " + d.proactive()
                + ", commit " + d.commitPerThreat() + ", reserve " + d.reserve() + ", militia " + d.militiaPolicy()
                + ", shelter " + (d.shelterRadius() == Doctrine.VILLAGE_WIDE ? "village-wide" : d.shelterRadius())
                + ", assist " + d.assistPlayers() + (d.assistPlayers() == dev.hywmill.military.doctrine.AssistMode.MIN_REPUTATION ? " " + d.assistMinReputation() : "")
                + ", provoking " + d.assistProvokingPlayer() + ", controller " + d.assistController()
                + ", timers " + d.alertTicks() + "/" + d.engagedTicks() + "/" + d.recoveryTicks();
    }

    private static String nonBaselineSources(DoctrineResolver.Resolved res) {
        String s = res.sources().entrySet().stream().filter(e -> !e.getValue().equals("baseline"))
                .map(e -> e.getKey().key + "<-" + e.getValue()).collect(Collectors.joining(", "));
        return s.isEmpty() ? "all baseline" : s;
    }

    private static int doctrineGet(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty()) {
            return 0;
        }
        VillageRecord r = or.get();
        DoctrineResolver.Resolved res = GarrisonUpdater.resolveDoctrine(r);
        send(src, "== Doctrine of " + r.name + " (" + r.culture + " / " + r.type + ", tier " + r.tier + ", village radius " + r.villageRadius
                + (r.loneBuilding ? ", lone building" : "") + ")");
        send(src, "defenseRadius = " + res.doctrine().defenseRadius() + "  [village radius " + r.villageRadius + " + offset, clamped "
                + Doctrine.MIN_DEFENSE_RADIUS + ".." + Doctrine.MAX_DEFENSE_RADIUS + "]");
        res.doctrine().asMap().forEach((f, v) -> send(src, f.key + " = " + DoctrineField.format(v) + "  [" + res.sourceOf(f) + "]"));
        send(src, "override: " + (r.doctrineOverride.isEmpty() ? "none" : r.doctrineOverride.toString()));
        return 1;
    }

    private static boolean mayChange(CommandSourceStack src, VillageRecord r) {
        if (src.hasPermission(2)) {
            return true;
        }
        ServerPlayer p = src.getPlayer();
        if (p != null && r.controllerPlayerId != null && r.controllerPlayerId.equals(p.getUUID())) {
            return true;
        }
        src.sendFailure(Component.literal("Only an operator or the controller of this player-controlled village may change its doctrine."));
        return false;
    }

    private static int doctrineSet(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        DoctrineField f = DoctrineField.byKey(StringArgumentType.getString(ctx, "field"));
        if (f == null) {
            src.sendFailure(Component.literal("Unknown doctrine field. Fields: "
                    + Arrays.stream(DoctrineField.values()).map(x -> x.key).collect(Collectors.joining(", "))));
            return 0;
        }
        Object value;
        try {
            value = f.parse(StringArgumentType.getString(ctx, "value"));
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty() || !mayChange(src, or.get())) {
            return 0;
        }
        VillageRecord r = or.get();
        r.doctrineOverride = r.doctrineOverride.with(f, value);
        return applied(src, r, "set " + f.key + " = " + DoctrineField.format(value));
    }

    private static int doctrineReset(CommandContext<CommandSourceStack> ctx, String fieldKey) {
        CommandSourceStack src = ctx.getSource();
        DoctrineField f = fieldKey == null ? null : DoctrineField.byKey(fieldKey);
        if (fieldKey != null && f == null) {
            src.sendFailure(Component.literal("Unknown doctrine field '" + fieldKey + "'"));
            return 0;
        }
        Optional<VillageRecord> or = record(src);
        if (or.isEmpty() || !mayChange(src, or.get())) {
            return 0;
        }
        VillageRecord r = or.get();
        r.doctrineOverride = f == null ? dev.hywmill.military.doctrine.DoctrinePatch.EMPTY : r.doctrineOverride.without(f);
        return applied(src, r, f == null ? "reset all overrides" : "reset " + f.key);
    }

    private static int applied(CommandSourceStack src, VillageRecord r, String what) {
        ServerLevel overworld = src.getServer().overworld();
        GarrisonLedger ledger = GarrisonLedger.get(overworld);
        ledger.setDirty();
        GarrisonUpdater.refreshOne(overworld, HywMillRuntime.require(), Services.settlements(), ledger, r.villageId, overworld.getGameTime());
        Doctrine d = GarrisonUpdater.resolveDoctrine(r).doctrine();
        HmLog.info("Doctrine override of village '{}' changed by {}: {} -> effective {}", r.name, src.getTextName(), what, summary(d));
        send(src, "Doctrine of " + r.name + ": " + what + ". Effective: " + summary(d));
        return 1;
    }

    private static int alerts(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        HywMillRuntime rt = HywMillRuntime.require();
        GarrisonLedger ledger = GarrisonLedger.get(src.getServer().overworld());
        long now = src.getServer().overworld().getGameTime();
        int n = 0;
        for (VillageDefenseState st : rt.defense().all()) {
            VillageRecord r = ledger.get(stVillage(st));
            send(src, (r != null ? r.name : "?") + ": " + st.state() + " for " + (now - st.stateSince()) + " ticks, threats "
                    + rt.threats().threats(stVillage(st)).size() + ", committed " + st.assignments().size() + ", reserve " + st.reserve().size());
            n++;
        }
        send(src, "Villages with defense state: " + n);
        return n;
    }

    private static UUID stVillage(VillageDefenseState st) {
        return st.village();
    }

    private static int perf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        HywMillRuntime rt = HywMillRuntime.require();
        send(src, "hywmill perf (per server since start or last reset):");
        rt.perf().report().forEach((k, v) -> send(src, " " + k + ": " + v));
        send(src, " villages known: " + (rt.cachedVillages == null ? 0 : rt.cachedVillages.size())
                + ", with defense state: " + rt.defense().all().size());
        int live = 0;
        int rosters = 0;
        for (VillageRecord r : GarrisonLedger.get(src.getServer().overworld()).all()) {
            if (r.hywRoster != null) {
                rosters++;
                live += r.hywRoster.live();
            }
        }
        send(src, " garrison: " + rosters + " roster(s), " + live + " live slot(s) (no server-wide cap), duplicates refused "
                + rt.garrison().counter(dev.hywmill.garrison.service.GarrisonService.C_DUPLICATES) + ", adopted "
                + rt.garrison().counter(dev.hywmill.garrison.service.GarrisonService.C_ADOPTED) + ", orphans "
                + rt.garrison().counter(dev.hywmill.garrison.service.GarrisonService.C_ORPHANS));
        return 1;
    }

    /** Per-building resident slots behind the capacity figure (M2-2 cross-check). */
    private static int devCapacity(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!HywMillCommands.devEnabled(src)) {
            return 0;
        }
        Optional<SettlementSource.SettlementRef> ref = nearest(src);
        if (ref.isEmpty()) {
            return 0;
        }
        Optional<SettlementSnapshot> s = Services.settlements().snapshot(src.getServer().overworld(), ref.get().id());
        if (s.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ProfileCalculator.BuildingSlots b : s.get().buildingSlots()) {
            int n = ProfileCalculator.capacity(List.of(b), RoleTables.current());
            total += n;
            if (!b.slots().isEmpty()) {
                send(src, "slots " + b.planSetId() + " " + b.variant() + " " + b.level() + " "
                        + b.slots().stream().map(f -> f.typeId() + ":" + RoleClassifier.villager(f, RoleTables.current()))
                        .collect(Collectors.joining(",")) + " capacity=" + n);
            }
        }
        send(src, "capacity total=" + total);
        return total;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String shortIds(java.util.Collection<UUID> ids) {
        return ids.stream().map(MilitaryCommands::shortId).sorted().collect(Collectors.joining(",", "[", "]"));
    }
}

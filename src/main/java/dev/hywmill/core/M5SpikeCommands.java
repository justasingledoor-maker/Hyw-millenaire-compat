package dev.hywmill.core;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.hywmill.config.HywMillConfig;
import dev.hywmill.military.DiplomacyPolicy;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * M5-0 spike tools ({@code /hywmill dev m5 ...}; devCommands=true, op 2). Not used by any
 * production path:
 * <ul>
 *   <li>{@code standin add|remove|heal}: a damageable stand-in player (a NeoForge FakePlayer added
 *       to the level) so relation targeting and M2 threat handling can be observed on a headless
 *       server without a real client;</li>
 *   <li>{@code policy allow|clear|show}: a test {@link DiplomacyPolicy} held by this server run's
 *       runtime (never persisted) that permits HOSTILE for listed pairs, standing in for M5's
 *       PoliticalPolicy;</li>
 *   <li>the HYW and Millénaire subtrees contributed by the integrations (foreign types stay there).</li>
 * </ul>
 */
final class M5SpikeCommands {
    private M5SpikeCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        LiteralArgumentBuilder<CommandSourceStack> m5 = Commands.literal("m5").requires(s -> HywMillConfig.DEV_COMMANDS.get());
        m5.then(Commands.literal("standin")
                .then(Commands.literal("add").then(Commands.argument("id", UuidArgument.uuid())
                        .then(Commands.argument("pos", Vec3Argument.vec3()).executes(M5SpikeCommands::standinAdd))))
                .then(Commands.literal("remove").then(Commands.argument("id", UuidArgument.uuid()).executes(M5SpikeCommands::standinRemove)))
                .then(Commands.literal("heal").then(Commands.argument("id", UuidArgument.uuid()).executes(M5SpikeCommands::standinHeal)))
                .then(Commands.literal("mode").then(Commands.argument("id", UuidArgument.uuid())
                        .then(Commands.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(M5SpikeCommands::standinMode)))));
        m5.then(Commands.literal("threat").then(Commands.argument("entity", net.minecraft.commands.arguments.EntityArgument.entity())
                .then(Commands.argument("village", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                        .executes(M5SpikeCommands::threat))));
        m5.then(Commands.literal("policy")
                .then(Commands.literal("allow").then(Commands.argument("faction", UuidArgument.uuid())
                        .then(Commands.argument("other", UuidArgument.uuid()).executes(M5SpikeCommands::policyAllow))))
                .then(Commands.literal("clear").executes(M5SpikeCommands::policyClear))
                .then(Commands.literal("show").executes(M5SpikeCommands::policyShow)));
        m5.then(Commands.literal("follow").then(Commands.argument("unit", net.minecraft.commands.arguments.EntityArgument.entity())
                .then(Commands.argument("goal", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                        .then(Commands.argument("maxHop", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
                                .executes(M5SpikeCommands::follow)))));
        m5.then(Commands.literal("ticking").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                .executes(ctx -> {
                    net.minecraft.core.BlockPos p = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos");
                    ServerLevel l = ctx.getSource().getServer().overworld();
                    int top = l.isPositionEntityTicking(p) ? l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) : -999;
                    boolean water = top > -999 && !l.getFluidState(new net.minecraft.core.BlockPos(p.getX(), top - 1, p.getZ())).isEmpty();
                    HywMillCommands.send(ctx.getSource(), "m5 ticking " + p.getX() + " " + p.getZ() + " " + l.isPositionEntityTicking(p) + " top=" + top + " water=" + water);
                    return 1;
                })));
        for (Supplier<LiteralArgumentBuilder<CommandSourceStack>> sub : Services.spikeCommands()) {
            m5.then(sub.get());
        }
        return m5;
    }

    /** A FakePlayer that takes damage and counts its own invulnerability frames down (FakePlayer's tick is empty). */
    static final class StandIn extends FakePlayer {
        StandIn(ServerLevel level, GameProfile profile) {
            super(level, profile);
            // ServerPlayer's 60-tick spawn invulnerability is only counted down by its regular tick, which
            // FakePlayer disables; clear it so the stand-in can be hurt like a player who has been online a while.
            try {
                java.lang.reflect.Field f = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
                f.setAccessible(true);
                f.setInt(this, 0);
            } catch (ReflectiveOperationException | RuntimeException e) {
                HmLog.warn("m5 standin: could not clear spawn invulnerability: {}", e.toString());
            }
        }

        @Override
        public boolean isInvulnerableTo(DamageSource source) {
            return false;
        }

        @Override
        public void tick() {
            if (invulnerableTime > 0) {
                invulnerableTime--;
            }
            if (hurtTime > 0) {
                hurtTime--;
            }
        }
    }

    private static int standinAdd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID id = UuidArgument.getUuid(ctx, "id");
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        if (level.getPlayerByUUID(id) != null) {
            HywMillCommands.send(src, "m5 standin exists " + id);
            return 0;
        }
        StandIn p = new StandIn(level, new GameProfile(id, "hwStandIn" + id.toString().substring(0, 4)));
        p.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
        level.addNewPlayer(p);
        HywMillCommands.send(src, "m5 standin added " + id + " at " + p.blockPosition().toShortString() + " health=" + p.getHealth()
                + " creative=" + p.isCreative() + " players=" + level.players().size());
        return 1;
    }

    private static int standinRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        UUID id = UuidArgument.getUuid(ctx, "id");
        if (level.getPlayerByUUID(id) instanceof StandIn p) {
            level.removePlayerImmediately(p, Entity.RemovalReason.DISCARDED);
            HywMillCommands.send(ctx.getSource(), "m5 standin removed " + id);
            return 1;
        }
        HywMillCommands.send(ctx.getSource(), "m5 standin missing " + id);
        return 0;
    }

    private static int standinHeal(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        UUID id = UuidArgument.getUuid(ctx, "id");
        if (level.getPlayerByUUID(id) instanceof ServerPlayer p) {
            float before = p.getHealth();
            p.setHealth(p.getMaxHealth());
            HywMillCommands.send(ctx.getSource(), "m5 standin health " + id + " was=" + before + " now=" + p.getHealth());
            return 1;
        }
        HywMillCommands.send(ctx.getSource(), "m5 standin missing " + id);
        return 0;
    }

    private static int standinMode(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        UUID id = UuidArgument.getUuid(ctx, "id");
        String mode = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "mode");
        if (level.getPlayerByUUID(id) instanceof StandIn p) {
            p.setGameMode(net.minecraft.world.level.GameType.byName(mode, net.minecraft.world.level.GameType.SURVIVAL));
            HywMillCommands.send(ctx.getSource(), "m5 standin mode " + id + " " + p.gameMode.getGameModeForPlayer() + " creative=" + p.isCreative());
            return 1;
        }
        return 0;
    }

    /**
     * Spike G: feeds one M2 scan for the village nearest {@code village} whose threat list is exactly
     * this entity (reason HYW_ENEMY, standing in for M5's additive OUTLAWED_PLAYER / ENEMY_COMBATANT).
     * The regular scan replaces it at the village's next scan slot, so the harness repeats it.
     */
    private static int threat(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        Entity e = net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "entity");
        net.minecraft.core.BlockPos pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "village");
        dev.hywmill.settlement.SettlementSource source = Services.settlements();
        if (source == null || !(e instanceof net.minecraft.world.entity.LivingEntity le)) {
            return 0;
        }
        ServerLevel level = src.getServer().overworld();
        java.util.Optional<dev.hywmill.settlement.SettlementSource.SettlementRef> ref = source.nearest(level, pos, 128);
        if (ref.isEmpty()) {
            return 0;
        }
        HywMillRuntime rt = HywMillRuntime.require();
        rt.defense().onScan(level, ref.get().id(), java.util.List.of(new dev.hywmill.military.ThreatTracker.Threat(le,
                java.util.EnumSet.of(dev.hywmill.military.ThreatTracker.Reason.HYW_ENEMY))), level.getGameTime());
        HywMillCommands.send(src, "m5 threat " + e.getUUID().toString().substring(0, 8) + " village=" + ref.get().name()
                + " alert=" + rt.defense().state(ref.get().id()));
        return 1;
    }

    /** Test policy: permits permanent HOSTILE only for the listed (faction, other) pairs, either order. */
    private record SpikePolicy(Set<String> pairs) implements DiplomacyPolicy {
        @Override
        public boolean permitsPermanentHostility(UUID villageFaction, UUID other) {
            return pairs.contains(villageFaction + ">" + other) || pairs.contains(other + ">" + villageFaction);
        }
    }

    private static int policyAllow(CommandContext<CommandSourceStack> ctx) {
        HywMillRuntime rt = HywMillRuntime.require();
        UUID f = UuidArgument.getUuid(ctx, "faction");
        UUID o = UuidArgument.getUuid(ctx, "other");
        SpikePolicy p = rt.diplomacy() instanceof SpikePolicy sp ? sp : new SpikePolicy(ConcurrentHashMap.newKeySet());
        p.pairs().add(f + ">" + o);
        rt.setDiplomacyForSpike(p);
        HywMillCommands.send(ctx.getSource(), "m5 policy allows " + p.pairs());
        return 1;
    }

    private static int policyClear(CommandContext<CommandSourceStack> ctx) {
        HywMillRuntime.require().setDiplomacyForSpike(null);
        HywMillCommands.send(ctx.getSource(), "m5 policy cleared (ALWAYS_REVERT)");
        return 1;
    }

    private static int policyShow(CommandContext<CommandSourceStack> ctx) {
        DiplomacyPolicy p = HywMillRuntime.require().diplomacy();
        HywMillCommands.send(ctx.getSource(), "m5 policy " + (p instanceof SpikePolicy sp ? "allows " + sp.pairs() : "ALWAYS_REVERT"));
        return 1;
    }

    /**
     * Spike H: one escort step with M4's movement (a hop of at most maxHop towards goal, on standable
     * ground in an entity-ticking chunk, set as the unit's HYW home). "hold" when no such spot exists.
     * Never loads a chunk: only already entity-ticking positions are considered.
     */
    private static int follow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        Entity unit = net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "unit");
        net.minecraft.core.BlockPos goal = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "goal");
        int maxHop = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "maxHop");
        dev.hywmill.garrison.spi.UnitProvider units = Services.units();
        if (units == null) {
            return 0;
        }
        ServerLevel level = (ServerLevel) unit.level();
        boolean goalTicking = level.isPositionEntityTicking(goal);
        net.minecraft.core.BlockPos hop = dev.hywmill.garrison.service.EscortSpikeProbe.hop(level, unit, goal, maxHop);
        if (hop != null) {
            units.setHome(unit, hop);
        }
        net.minecraft.core.BlockPos home = units.home(unit);
        HywMillCommands.send(src, "m5 follow " + unit.getUUID().toString().substring(0, 8) + " pos=" + unit.blockPosition().toShortString()
                + " goal=" + goal.toShortString() + " goalTicking=" + goalTicking + " hop=" + (hop == null ? "hold" : hop.toShortString())
                + " home=" + (home == null ? "none" : home.toShortString()) + " forced=" + level.getForcedChunks().size());
        return 1;
    }

    static Component text(String s) {
        return Component.literal(s);
    }
}

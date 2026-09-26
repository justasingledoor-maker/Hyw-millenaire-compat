package dev.hywmill.core;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.settlement.SettlementSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * M4-0 spike tools (dev-only, devCommands=true, op 2): observe Millénaire raid state, run the
 * Wand of Negation's own deletion, compute Millénaire's raid landing point, move a unit's HYW home,
 * and overlay an item on a unit (equipment compatibility). No foreign-mod types here.
 */
final class M4SpikeCommands {
    private M4SpikeCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> raidState() {
        return Commands.literal("raidstate").executes(M4SpikeCommands::raidStateAll);
    }

    static LiteralArgumentBuilder<CommandSourceStack> raidPoint() {
        return Commands.literal("raidpoint").then(Commands.argument("attacker", BlockPosArgument.blockPos())
                .executes(M4SpikeCommands::raidPointCmd));
    }

    static LiteralArgumentBuilder<CommandSourceStack> negate() {
        return Commands.literal("negate").executes(M4SpikeCommands::negateCmd);
    }

    static LiteralArgumentBuilder<CommandSourceStack> home() {
        return Commands.literal("spike-home").then(Commands.argument("unit", EntityArgument.entity())
                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(M4SpikeCommands::homeCmd)));
    }

    static LiteralArgumentBuilder<CommandSourceStack> equip() {
        return Commands.literal("equip").then(Commands.argument("unit", EntityArgument.entity())
                .then(Commands.argument("slot", StringArgumentType.word())
                        .then(Commands.argument("item", StringArgumentType.greedyString()).executes(M4SpikeCommands::equipCmd))));
    }

    /** M4 validation tool ({@code /hywmill admin equipcheck}, op 3): checks the equipment profiles against every garrison unit type (log + chat summary). */
    static LiteralArgumentBuilder<CommandSourceStack> equipCheck() {
        return Commands.literal("equipcheck").executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            dev.hywmill.garrison.spi.EquipmentProvider p = Services.equipment("hyw_profiles");
            if (p == null) {
                src.sendFailure(Component.literal("Equipment provider 'hyw_profiles' is not available (HYW not loaded?)."));
                return 0;
            }
            java.util.List<String> report = p.validate(dev.hywmill.garrison.tables.GarrisonTables.current().units().values());
            for (String l : report) {
                HmLog.info("equipcheck {}", l);
            }
            int shown = 0;
            for (String l : report) {
                if (shown++ < 40 || l.startsWith("equipcheck:")) {
                    src.sendSuccess(() -> Component.literal(l), false);
                }
            }
            return report.size();
        });
    }

    static String id8(UUID u) {
        return u == null ? "none" : u.toString().substring(0, 8);
    }

    private static int raidStateAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        SettlementSource source = Services.settlements();
        if (!HywMillCommands.devEnabled(src) || source == null) {
            return 0;
        }
        ServerLevel ow = src.getServer().overworld();
        int n = 0;
        for (SettlementSource.SettlementRef r : source.list(ow)) {
            Optional<SettlementSource.RaidInfo> ri = source.raidInfo(ow, r.id());
            if (ri.isEmpty()) {
                continue;
            }
            SettlementSource.RaidInfo i = ri.get();
            HywMillCommands.send(src, "raidstate " + r.name() + " " + id8(r.id()) + " target=" + id8(i.target()) + " planning=" + i.planningStart()
                    + " start=" + i.raidStart() + " underAttack=" + i.underAttack() + " performed=" + i.performed() + " suffered=" + i.suffered()
                    + " t=" + ow.getGameTime());
            n++;
        }
        return n;
    }

    private static int raidPointCmd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!HywMillCommands.devEnabled(src)) {
            return 0;
        }
        Optional<SettlementSource.SettlementRef> ref = HywMillCommands.nearest(src);
        if (ref.isEmpty()) {
            return 0;
        }
        BlockPos attacker = BlockPosArgument.getBlockPos(ctx, "attacker");
        Optional<BlockPos> p = Services.settlements().raidLandingPoint(src.getServer().overworld(), ref.get().id(), attacker);
        HywMillCommands.send(src, "raidpoint target=" + ref.get().name() + " from " + attacker.toShortString() + " -> "
                + p.map(BlockPos::toShortString).orElse("none"));
        return p.isPresent() ? 1 : 0;
    }

    private static int negateCmd(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!HywMillCommands.devEnabled(src)) {
            return 0;
        }
        Optional<SettlementSource.SettlementRef> ref = HywMillCommands.nearest(src);
        if (ref.isEmpty()) {
            return 0;
        }
        ServerLevel ow = src.getServer().overworld();
        net.neoforged.neoforge.common.util.FakePlayer fp = net.neoforged.neoforge.common.util.FakePlayerFactory.get(ow,
                new com.mojang.authlib.GameProfile(UUID.fromString("00000000-0000-4000-8000-0000000e6a7e"), "hwNegation"));
        fp.moveTo(src.getPosition().x, src.getPosition().y, src.getPosition().z);
        boolean ok = Services.settlements().devNegate(ow, ref.get().id(), fp);
        HmLog.info("dev negate {} ({}): {}", ref.get().name(), ref.get().id(), ok);
        HywMillCommands.send(src, "negate " + ref.get().name() + " " + ref.get().id() + ": " + ok);
        return ok ? 1 : 0;
    }

    private static int homeCmd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        UnitProvider units = Services.units();
        if (!HywMillCommands.devEnabled(src) || units == null) {
            return 0;
        }
        Entity unit = EntityArgument.getEntity(ctx, "unit");
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        units.setHome(unit, pos);
        HywMillCommands.send(src, "spike-home " + unit.getUUID() + " -> " + pos.toShortString() + " home=" + units.home(unit));
        return 1;
    }

    private static int equipCmd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!HywMillCommands.devEnabled(src)) {
            return 0;
        }
        Entity e = EntityArgument.getEntity(ctx, "unit");
        if (!(e instanceof LivingEntity unit)) {
            return 0;
        }
        EquipmentSlot slot;
        try {
            slot = EquipmentSlot.byName(StringArgumentType.getString(ctx, "slot"));
        } catch (IllegalArgumentException ex) {
            src.sendFailure(Component.literal("slot: mainhand/offhand/head/chest/legs/feet"));
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "item").trim();
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Optional<Item> item = rl == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(rl);
        if (item.isEmpty()) {
            HywMillCommands.send(src, "equip " + e.getUUID() + " " + slot.getName() + " " + id + ": UNREGISTERED");
            return 0;
        }
        unit.setItemSlot(slot, new ItemStack(item.get()));
        HywMillCommands.send(src, "equip " + e.getUUID() + " " + slot.getName() + " " + id + ": set, now "
                + BuiltInRegistries.ITEM.getKey(unit.getItemBySlot(slot).getItem()));
        return 1;
    }

    /** Slot summary for spike-info. */
    static String slots(LivingEntity e) {
        StringBuilder sb = new StringBuilder();
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = e.getItemBySlot(s);
            sb.append(s.getName()).append('=').append(st.isEmpty() ? "-" : BuiltInRegistries.ITEM.getKey(st.getItem())).append(',');
        }
        return sb.toString();
    }
}

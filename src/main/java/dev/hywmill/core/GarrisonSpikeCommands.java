package dev.hywmill.core;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.hywmill.faction.FactionIds;
import dev.hywmill.garrison.spi.EquipmentProvider;
import dev.hywmill.garrison.spi.SpawnRequest;
import dev.hywmill.garrison.spi.SpawnResult;
import dev.hywmill.garrison.spi.UnitProvider;
import dev.hywmill.garrison.tables.UnitClass;
import dev.hywmill.garrison.tables.UnitSpec;
import dev.hywmill.garrison.tag.GarrisonAttachments;
import dev.hywmill.garrison.tag.GarrisonTag;
import dev.hywmill.military.MilitaryTier;
import dev.hywmill.settlement.SettlementSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * M3-0 spike tools (dev-only): spawn a single village-owned HYW unit through the production spawn
 * sequence without any roster, engage it on a target, and print its garrison-relevant state.
 */
final class GarrisonSpikeCommands {
    private GarrisonSpikeCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> spawnNode() {
        return Commands.literal("spike-spawn")
                .then(Commands.argument("unit", StringArgumentType.word())
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 3))
                                .executes(ctx -> spawn(ctx, null, 1))
                                .then(Commands.argument("roster", UuidArgument.uuid())
                                        .then(Commands.argument("generation", IntegerArgumentType.integer(1))
                                                .executes(ctx -> spawn(ctx, UuidArgument.getUuid(ctx, "roster"),
                                                        IntegerArgumentType.getInteger(ctx, "generation")))))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> engageNode() {
        return Commands.literal("spike-engage")
                .then(Commands.argument("unit", EntityArgument.entity())
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(GarrisonSpikeCommands::engage)));
    }

    static LiteralArgumentBuilder<CommandSourceStack> infoNode() {
        return Commands.literal("spike-info")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(GarrisonSpikeCommands::info));
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, UUID rosterId, int generation) {
        CommandSourceStack src = ctx.getSource();
        if (!HywMillCommands.devEnabled(src)) {
            return 0;
        }
        UnitProvider units = Services.units();
        EquipmentProvider equipment = Services.equipment("hyw");
        if (units == null || equipment == null) {
            src.sendFailure(Component.literal("HYW unit provider is not available."));
            return 0;
        }
        Optional<SettlementSource.SettlementRef> ref = HywMillCommands.nearest(src);
        if (ref.isEmpty()) {
            return 0;
        }
        String type = "hundred_years_war:" + StringArgumentType.getString(ctx, "unit");
        if (!units.isValidUnitType(type)) {
            src.sendFailure(Component.literal("Not a registered HYW unit type: " + type));
            return 0;
        }
        UUID roster = rosterId != null ? rosterId : UUID.randomUUID();
        GarrisonTag tag = new GarrisonTag(ref.get().id(), roster, generation);
        // roster-first, like production: the slot exists (bound to the deterministic UUID) before the entity
        dev.hywmill.settlement.GarrisonLedger ledger = dev.hywmill.settlement.GarrisonLedger.get(src.getServer().overworld());
        dev.hywmill.settlement.VillageRecord rec = ledger.get(ref.get().id());
        if (rec == null) {
            src.sendFailure(Component.literal("village has no ledger record yet"));
            return 0;
        }
        dev.hywmill.garrison.service.GarrisonService.roster(rec, src.getServer().overworld().getGameTime())
                .devBind(roster, type, type, IntegerArgumentType.getInteger(ctx, "level"), generation, src.getServer().overworld().getGameTime());
        ledger.setDirty();
        UnitSpec spec = new UnitSpec(type, type, UnitClass.LINE, 0, MilitaryTier.NONE, true);
        BlockPos home = BlockPos.containing(src.getPosition());
        SpawnResult r = units.spawn(src.getLevel(), new SpawnRequest(spec, FactionIds.forVillage(ref.get().id()), tag.expectedEntityUuid(),
                src.getPosition(), home, IntegerArgumentType.getInteger(ctx, "level"), false, tag, equipment));
        if (!r.ok()) {
            src.sendFailure(Component.literal("spike spawn failed: " + r.failure()));
            return 0;
        }
        HywMillCommands.send(src, "spike spawned " + r.entity().getUUID() + " roster=" + roster + " gen=" + generation
                + " applied=" + r.appliedLevel() + " " + units.describe(r.entity()));
        return 1;
    }

    private static int engage(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        UnitProvider units = Services.units();
        if (!HywMillCommands.devEnabled(src) || units == null) {
            return 0;
        }
        Entity unit = EntityArgument.getEntity(ctx, "unit");
        Entity target = EntityArgument.getEntity(ctx, "target");
        if (!(target instanceof LivingEntity living) || !units.isUnit(unit)) {
            src.sendFailure(Component.literal("need an HYW unit and a living target"));
            return 0;
        }
        units.engage(unit, living);
        HywMillCommands.send(src, "spike engage " + unit.getUUID() + " -> " + target.getUUID()
                + " tempHostile=" + units.isTemporarilyHostile(unit, living));
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        UnitProvider units = Services.units();
        if (!HywMillCommands.devEnabled(src) || units == null) {
            return 0;
        }
        for (Entity e : EntityArgument.getEntities(ctx, "targets")) {
            GarrisonTag tag = GarrisonAttachments.get(e);
            LivingEntity t = units.target(e);
            HywMillCommands.send(src, "spike info " + e.getUUID() + " dim=" + e.level().dimension().location()
                    + " pos=" + e.blockPosition().toShortString() + " " + units.describe(e)
                    + " tag=" + (tag == null ? "none" : tag.villageId() + "/" + tag.rosterId() + "/" + tag.generation())
                    + " target=" + (t == null ? "none" : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(t.getType()) + "[" + t.getUUID().toString().substring(0, 8) + "]")
                    + (t == null ? "" : " tempHostile=" + units.isTemporarilyHostile(e, t)));
        }
        return 1;
    }
}

package dev.hywmill.integration.hyw;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity;
import ydmsama.hundred_years_war.main.entity.utils.AttackStrategy;
import ydmsama.hundred_years_war.main.entity.utils.TemporaryHostileTargetManager;
import ydmsama.hundred_years_war.main.utils.RelationSystem;
import ydmsama.hundred_years_war.main.utils.ServerRelationHelper;
import ydmsama.hundred_years_war.main.utils.TeamRelationData;

import java.util.UUID;

/**
 * M5-0 spike subtree ({@code /hywmill dev m5 hyw ...}; dev only). Every call is a public HYW
 * 0.7.1r-fix1 method (checked with javap): RelationSystem.getRelation/setRelation/createTeam/
 * joinTeam/getPlayerTeamUUID, ServerRelationHelper.getRelationUUID/isEnemyRelation/
 * isRelationProtected/shouldCancelFriendlyDamage/shouldIgnoreFriendlyCollision,
 * BaseCombatEntity.isValidTarget/getAttackStrategy/setAttackStrategy/getHywTarget/getFollowTarget/
 * getOwnerUUID, TemporaryHostileTargetManager.markHostile/isHostile.
 */
public final class HywM5Spike {
    private HywM5Spike() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("hyw")
                .then(Commands.literal("ident").then(Commands.argument("e", EntityArgument.entity()).executes(HywM5Spike::ident)))
                .then(Commands.literal("team")
                        .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("owner", UuidArgument.uuid()).executes(HywM5Spike::teamCreate))))
                        .then(Commands.literal("join").then(Commands.argument("team", UuidArgument.uuid())
                                .then(Commands.argument("member", UuidArgument.uuid()).executes(HywM5Spike::teamJoin)))))
                .then(Commands.literal("rel").then(Commands.argument("a", UuidArgument.uuid())
                        .then(Commands.argument("b", UuidArgument.uuid()).executes(HywM5Spike::rel))))
                .then(Commands.literal("relset").then(Commands.argument("a", UuidArgument.uuid())
                        .then(Commands.argument("b", UuidArgument.uuid())
                                .then(Commands.argument("type", StringArgumentType.word()).executes(HywM5Spike::relSet)))))
                .then(Commands.literal("valid").then(Commands.argument("unit", EntityArgument.entity())
                        .then(Commands.argument("target", EntityArgument.entity()).executes(HywM5Spike::valid))))
                .then(Commands.literal("strategy").then(Commands.argument("unit", EntityArgument.entity())
                        .executes(ctx -> strategy(ctx, null))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .executes(ctx -> strategy(ctx, StringArgumentType.getString(ctx, "type"))))))
                .then(Commands.literal("mark").then(Commands.argument("unit", EntityArgument.entity())
                        .then(Commands.argument("target", EntityArgument.entity()).executes(ctx -> mark(ctx, false))
                                .then(Commands.literal("engage").executes(ctx -> mark(ctx, true))))))
                .then(Commands.literal("hit").then(Commands.argument("attacker", EntityArgument.entity())
                        .then(Commands.argument("victim", EntityArgument.entity())
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0.1f, 100f))
                                        .then(Commands.argument("kind", StringArgumentType.word()).executes(HywM5Spike::hit))))))
                .then(Commands.literal("hp").then(Commands.argument("e", EntityArgument.entity()).executes(HywM5Spike::hp)));
    }

    private static void send(CommandSourceStack src, String s) {
        src.sendSuccess(() -> Component.literal(s), false);
    }

    private static String id8(Object o) {
        if (o == null) {
            return "null";
        }
        String s = o instanceof Entity e ? e.getUUID().toString() : o.toString();
        return s.length() >= 8 ? s.substring(0, 8) : s;
    }

    private static int ident(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity e = EntityArgument.getEntity(ctx, "e");
        UUID rel = ServerRelationHelper.getRelationUUID(e);
        UUID owner = e instanceof BaseCombatEntity b ? b.getOwnerUUID() : null;
        UUID team = owner != null ? RelationSystem.getPlayerTeamUUID(owner) : rel != null ? RelationSystem.getPlayerTeamUUID(rel) : null;
        String extra = e instanceof BaseCombatEntity b
                ? " strategy=" + b.getAttackStrategy() + " follow=" + id8(b.getFollowTarget()) + " home=" + (b.getHomePosition() == null ? "none" : b.getHomePosition().toShortString())
                : "";
        send(ctx.getSource(), "m5 ident " + e.getUUID() + " rel=" + rel + " owner=" + owner + " team=" + team
                + " describe=" + ServerRelationHelper.describeRelationIdentity(e) + extra);
        return 1;
    }

    private static int teamCreate(CommandContext<CommandSourceStack> ctx) {
        UUID owner = UuidArgument.getUuid(ctx, "owner");
        TeamRelationData t = RelationSystem.createTeam(StringArgumentType.getString(ctx, "name"), owner);
        send(ctx.getSource(), "m5 team created " + (t == null ? "null" : t.getUUID()) + " owner=" + owner
                + " playerTeam=" + RelationSystem.getPlayerTeamUUID(owner));
        return t == null ? 0 : 1;
    }

    private static int teamJoin(CommandContext<CommandSourceStack> ctx) {
        UUID team = UuidArgument.getUuid(ctx, "team");
        UUID member = UuidArgument.getUuid(ctx, "member");
        boolean ok = RelationSystem.joinTeam(team, member, TeamRelationData.MemberType.MEMBER);
        send(ctx.getSource(), "m5 team join " + ok + " member=" + member + " playerTeam=" + RelationSystem.getPlayerTeamUUID(member));
        return ok ? 1 : 0;
    }

    private static int rel(CommandContext<CommandSourceStack> ctx) {
        UUID a = UuidArgument.getUuid(ctx, "a");
        UUID b = UuidArgument.getUuid(ctx, "b");
        send(ctx.getSource(), "m5 rel " + id8(a) + "->" + id8(b) + "=" + RelationSystem.getRelation(a, b)
                + " " + id8(b) + "->" + id8(a) + "=" + RelationSystem.getRelation(b, a));
        return 1;
    }

    private static int relSet(CommandContext<CommandSourceStack> ctx) {
        UUID a = UuidArgument.getUuid(ctx, "a");
        UUID b = UuidArgument.getUuid(ctx, "b");
        RelationSystem.RelationType t = RelationSystem.RelationType.valueOf(StringArgumentType.getString(ctx, "type"));
        RelationSystem.setRelation(a, b, t);
        send(ctx.getSource(), "m5 relset " + id8(a) + "->" + id8(b) + " " + t + " => " + RelationSystem.getRelation(a, b)
                + " / reverse " + RelationSystem.getRelation(b, a));
        return 1;
    }

    private static int valid(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity u = EntityArgument.getEntity(ctx, "unit");
        Entity t = EntityArgument.getEntity(ctx, "target");
        if (!(u instanceof BaseCombatEntity b) || !(t instanceof LivingEntity lt)) {
            send(ctx.getSource(), "m5 valid n/a");
            return 0;
        }
        send(ctx.getSource(), "m5 valid " + id8(u) + "->" + id8(t) + " valid=" + b.isValidTarget(lt)
                + " enemy=" + ServerRelationHelper.isEnemyRelation(u, t)
                + " protected=" + ServerRelationHelper.isRelationProtected(u, t)
                + " cancelDamage=" + ServerRelationHelper.shouldCancelFriendlyDamage(u, t)
                + " ignoreCollision=" + ServerRelationHelper.shouldIgnoreFriendlyCollision(u, t)
                + " temp=" + TemporaryHostileTargetManager.isHostile(b, lt)
                + " strategy=" + b.getAttackStrategy() + " target=" + id8(b.getHywTarget() != null ? b.getHywTarget() : b.getTarget()));
        return 1;
    }

    private static int strategy(CommandContext<CommandSourceStack> ctx, String type) throws CommandSyntaxException {
        Entity u = EntityArgument.getEntity(ctx, "unit");
        if (!(u instanceof BaseCombatEntity b)) {
            return 0;
        }
        if (type != null) {
            b.setAttackStrategy(AttackStrategy.valueOf(type));
        }
        send(ctx.getSource(), "m5 strategy " + id8(u) + " " + b.getAttackStrategy());
        return 1;
    }

    private static int mark(CommandContext<CommandSourceStack> ctx, boolean engage) throws CommandSyntaxException {
        Entity u = EntityArgument.getEntity(ctx, "unit");
        Entity t = EntityArgument.getEntity(ctx, "target");
        if (!(u instanceof BaseCombatEntity b) || !(t instanceof LivingEntity lt)) {
            return 0;
        }
        TemporaryHostileTargetManager.markHostile(b, lt);
        if (engage) {
            b.setTarget(lt);
        }
        send(ctx.getSource(), "m5 mark " + id8(u) + "->" + id8(t) + " temp=" + TemporaryHostileTargetManager.isHostile(b, lt)
                + " valid=" + b.isValidTarget(lt) + " engage=" + engage);
        return 1;
    }

    /** One hit from attacker on victim: melee (mob attack source), a real arrow shot at it, or a small explosion caused by the attacker. */
    private static int hit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity a = EntityArgument.getEntity(ctx, "attacker");
        Entity v = EntityArgument.getEntity(ctx, "victim");
        float amount = FloatArgumentType.getFloat(ctx, "amount");
        String kind = StringArgumentType.getString(ctx, "kind");
        if (!(a instanceof LivingEntity la) || !(v instanceof LivingEntity lv)) {
            return 0;
        }
        ServerLevel level = (ServerLevel) v.level();
        float before = lv.getHealth();
        boolean applied;
        switch (kind) {
            case "melee" -> applied = lv.hurt(level.damageSources().mobAttack(la), amount);
            case "arrow" -> {
                Arrow arrow = new Arrow(level, la, new ItemStack(Items.ARROW), null);
                Vec3 to = lv.getEyePosition().subtract(0, 0.4, 0);
                Vec3 dir = la.position().subtract(lv.position()).multiply(1, 0, 1);
                dir = dir.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : dir.normalize();
                Vec3 from = to.add(dir.scale(2.5));
                arrow.moveTo(from.x, from.y, from.z);
                arrow.setBaseDamage(amount / 2.0);
                Vec3 d = to.subtract(from);
                arrow.shoot(d.x, d.y, d.z, 1.6f, 0f);
                applied = level.addFreshEntity(arrow);
            }
            case "explosion" -> {
                level.explode(la, lv.getX(), lv.getY() + 0.2, lv.getZ(), amount, Level.ExplosionInteraction.NONE);
                applied = true;
            }
            default -> {
                send(ctx.getSource(), "m5 hit unknown kind " + kind);
                return 0;
            }
        }
        send(ctx.getSource(), "m5 hit " + kind + " " + id8(a) + "->" + id8(v) + " applied=" + applied + " before=" + before
                + " now=" + lv.getHealth() + " cancelDamage=" + ServerRelationHelper.shouldCancelFriendlyDamage(a, v));
        return 1;
    }

    private static int hp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity e = EntityArgument.getEntity(ctx, "e");
        send(ctx.getSource(), "m5 hp " + e.getUUID() + " " + (e instanceof LivingEntity l ? l.getHealth() + "/" + l.getMaxHealth() : "n/a")
                + " alive=" + e.isAlive());
        return 1;
    }
}

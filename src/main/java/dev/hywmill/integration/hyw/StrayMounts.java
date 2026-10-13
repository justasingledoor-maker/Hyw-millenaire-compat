package dev.hywmill.integration.hyw;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import ydmsama.hundred_years_war.main.entity.entities.BaseCombatEntity;
import ydmsama.hundred_years_war.main.entity.entities.HywCamelEntity;
import ydmsama.hundred_years_war.main.entity.entities.HywHorseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 1.10: soldiers' horses left behind. An HYW rider's horse (or camel) stays in the world, saddled and armoured, when its rider
 * dies, is stowed for a march or is dropped as a duplicate; they pile up where soldiers fall and are raised again, and slow
 * the game. A soldier's mount with no one on it and no living rider is marked; one still so {@link #GRACE} ticks later is
 * removed (its gear with it, nothing dropped). A rider who comes back to find his horse gone gets a new one (HYW). Never
 * touched: a horse a player owns, rides, leads or has named.
 */
final class StrayMounts {
    private StrayMounts() {}

    static final String MARK = "hywmill_riderless";
    /** How long a soldier's mount may stand riderless before it is removed (ticks). */
    static final long GRACE = 600;

    static int reap(ServerLevel level, long now) {
        List<Entity> gone = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            BaseCombatEntity rider;
            UUID owner;
            if (e instanceof HywHorseEntity h && h.isNpcHorse()) {
                rider = h.getRiderEntity();
                owner = h.getOwnerUUID();
            } else if (e instanceof HywCamelEntity c && c.isNpcCamel()) {
                rider = c.getRiderEntity();
                owner = c.getOwnerUUID();
            } else {
                continue;
            }
            boolean stray = e.isAlive() && !e.isVehicle() && !(e instanceof Mob m && m.isLeashed()) && !e.hasCustomName()
                    && (rider == null || !rider.isAlive() || (rider.getVehicle() != null && rider.getVehicle() != e))
                    && !player(level, owner);
            var data = e.getPersistentData();
            if (!stray) {
                data.remove(MARK);
            } else if (!data.contains(MARK)) {
                data.putLong(MARK, now);
            } else if (now - data.getLong(MARK) >= GRACE) {
                gone.add(e);
            }
        }
        gone.forEach(Entity::discard);
        return gone.size();
    }

    /** Whether {@code id} is a player's (one who has been on this server). */
    private static boolean player(ServerLevel level, UUID id) {
        if (id == null) {
            return false;
        }
        var cache = level.getServer().getProfileCache();
        return level.getServer().getPlayerList().getPlayer(id) != null || (cache != null && cache.get(id).isPresent());
    }
}

package dev.villageroads.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Loads the chunks a road job needs (by tickets: the server loads, or generates, them in the background) and lets them go
 * once the job has not asked for them a while. One per job.
 */
final class ChunkGate {
    static final TicketType<ChunkPos> TICKET = TicketType.create("villageroads", Comparator.comparingLong(ChunkPos::toLong));
    /** A chunk not asked for this many ticks is let go. */
    static final int IDLE = 100;

    private final ServerLevel level;
    private final Map<Long, Long> held = new HashMap<>();

    ChunkGate(ServerLevel level) {
        this.level = level;
    }

    /** Asks for a chunk; it, if loaded already, or null. */
    LevelChunk ask(int cx, int cz, long now) {
        ChunkPos pos = new ChunkPos(cx, cz);
        if (held.put(pos.toLong(), now) == null) {
            level.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
        }
        return level.getChunkSource().getChunkNow(cx, cz);
    }

    /** Lets go a chunk at once. */
    void release(int cx, int cz) {
        ChunkPos pos = new ChunkPos(cx, cz);
        if (held.remove(pos.toLong()) != null) {
            level.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
        }
    }

    /** Lets go the chunks not asked for lately. */
    void sweep(long now) {
        for (Iterator<Map.Entry<Long, Long>> it = held.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> e = it.next();
            if (now - e.getValue() > IDLE) {
                ChunkPos pos = new ChunkPos(e.getKey());
                level.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
                it.remove();
            }
        }
    }

    void releaseAll() {
        for (long k : held.keySet()) {
            ChunkPos pos = new ChunkPos(k);
            level.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
        }
        held.clear();
    }

    int held() {
        return held.size();
    }
}

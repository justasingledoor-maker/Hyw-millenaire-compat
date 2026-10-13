package dev.villageroads.world;

import dev.villageroads.RoadsConfig;
import dev.villageroads.integration.millenaire.MillVillages;
import dev.villageroads.plan.Network;
import dev.villageroads.plan.RoadPlanner;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The roads being made on a server: the village network (looked over now and then; one village road built at a time, in the
 * background) and the roads players lay with the staff (each player's last can be taken up again). One per running server.
 */
public final class RoadManager {
    static final Logger LOG = LoggerFactory.getLogger("villageroads");
    /** A village link that failed is tried again after this long (ticks: three days). */
    static final long RETRY = 72000;
    /** A player hears how his road comes on this often (ticks). */
    static final int TELL = 20;

    private final MinecraftServer server;
    private final ExecutorService planner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "village-roads-planner");
        t.setDaemon(true);
        return t;
    });
    private final List<RoadJob> jobs = new ArrayList<>();
    private final Deque<RoadJob.Villages> queue = new ArrayDeque<>();
    private final Map<UUID, RoadJob> last = new HashMap<>();
    private final List<Undo> undos = new ArrayList<>();
    private int ids;
    private long nextScan = 200;

    public RoadManager(MinecraftServer server) {
        this.server = server;
    }

    public void tick() {
        ServerLevel ow = server.overworld();
        long now = ow.getGameTime();
        if (now >= nextScan) {
            nextScan = now + RoadsConfig.SCAN_TICKS.get();
            scan(ow, false);
        }
        if (jobs.stream().noneMatch(j -> j.owner == null) && !queue.isEmpty()) {
            RoadJob.Villages v = queue.poll();
            jobs.add(new RoadJob(++ids, null, ow, v, autoSpec(), survey()));
            LOG.info("Road {}: surveying the way from {} to {}", ids, v.a().name(), v.b().name());
        }
        int budget = RoadsConfig.COLUMNS_PER_TICK.get(), active = Math.max(1, jobs.size());
        for (Iterator<RoadJob> it = jobs.iterator(); it.hasNext(); ) {
            RoadJob j = it.next();
            j.tick(j.level.getGameTime(), Math.max(1, budget / active), planner);
            if (j.owner != null && (j.finished() || now % TELL == 0)) {
                ServerPlayer p = server.getPlayerList().getPlayer(j.owner);
                if (p != null) {
                    p.displayClientMessage(Component.literal("Road: " + j.progress()), true);
                }
            }
            if (j.finished()) {
                it.remove();
                finish(j, now);
            }
        }
        for (Iterator<Undo> it = undos.iterator(); it.hasNext(); ) {
            Undo u = it.next();
            if (u.tick(u.level.getGameTime(), budget * 8)) {
                it.remove();
                LOG.info("Roads: a road is taken up ({} blocks put back)", u.count);
                ServerPlayer p = server.getPlayerList().getPlayer(u.owner);
                if (p != null) {
                    p.displayClientMessage(Component.literal("The road is taken up (" + u.count + " blocks put back)."), false);
                }
            }
        }
    }

    private void finish(RoadJob j, long now) {
        String what = j.ends instanceof RoadJob.Villages v ? "the road from " + v.a().name() + " to " + v.b().name() : "your road";
        if (j.ends instanceof RoadJob.Villages v) {
            String key = Network.key(v.a().id(), v.b().id());
            if (j.phase == RoadJob.Phase.DONE) {
                j.data.built.add(key);
                j.data.failed.remove(key);
                if (j.way() != null) {
                    j.data.way(key, j.way());
                }
                if (RoadsConfig.ANNOUNCE.get()) {
                    server.getPlayerList().broadcastSystemMessage(Component.literal("A road now links " + v.a().name() + " and " + v.b().name()
                            + " (" + j.note + ").").withStyle(ChatFormatting.GRAY), false);
                }
            } else {
                j.data.failed.put(key, now + RETRY);
            }
            j.data.setDirty();
        }
        if (j.ends instanceof RoadJob.Points && j.phase == RoadJob.Phase.DONE && j.way() != null) {
            j.wayKey = "staff:" + now + ":" + j.id;
            j.data.way(j.wayKey, j.way()); // a player's road is a road like any other for travellers
        }
        LOG.info("Road {}: {} {}: {}", j.id, what, j.phase == RoadJob.Phase.DONE ? "built" : "failed", j.note);
        if (j.phase == RoadJob.Phase.DONE) {
            LOG.info("Road {}: {}", j.id, j.where());
        }
        if (j.owner != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(j.owner);
            if (j.phase == RoadJob.Phase.DONE) {
                last.put(j.owner, j);
                if (p != null) {
                    p.displayClientMessage(Component.literal("The road is built: " + j.note + ". The staff's screen can take it up again.")
                            .withStyle(ChatFormatting.GREEN), false);
                }
            } else if (p != null) {
                p.displayClientMessage(Component.literal("No road: " + j.note + ".").withStyle(ChatFormatting.RED), false);
            }
        }
    }

    /** Looks the villages over for links not yet built (or failed long enough ago); queues them, nearest first. */
    public int scan(ServerLevel ow, boolean force) {
        if (!RoadsConfig.AUTO_ROADS.get() && !force || !MillVillages.available()) {
            return 0;
        }
        List<VillageInfo> vs = new ArrayList<>();
        for (VillageInfo v : MillVillages.list(ow)) {
            if (!v.lone() || RoadsConfig.LONE_BUILDINGS.get()) {
                vs.add(v);
            }
        }
        Map<String, VillageInfo> byId = new HashMap<>();
        List<Network.Place> places = new ArrayList<>();
        for (VillageInfo v : vs) {
            byId.put(v.id(), v);
            places.add(new Network.Place(v.id(), v.center().getX(), v.center().getZ()));
        }
        RoadsData data = RoadsData.get(ow);
        long now = ow.getGameTime();
        int added = 0;
        for (Network.Link l : Network.links(places, RoadsConfig.AUTO_REACH.get())) {
            String key = Network.key(l.a().id(), l.b().id());
            Long retry = data.failed.get(key);
            if (data.built.contains(key) || retry != null && retry > now && !force || queued(key)) {
                continue;
            }
            queue.add(villages(byId.get(l.a().id()), byId.get(l.b().id()), vs));
            added++;
        }
        if (added > 0) {
            LOG.info("Roads: {} village link(s) to build", added);
        }
        return added;
    }

    /** The two villages, and the others near the line between them (the road keeps out of them). */
    static RoadJob.Villages villages(VillageInfo a, VillageInfo b, List<VillageInfo> all) {
        List<VillageInfo> others = new ArrayList<>();
        int reach = RoadsConfig.CORRIDOR.get() + 128;
        for (VillageInfo o : all) {
            if (o != a && o != b && near(o.center(), a.center(), b.center(), reach)) {
                others.add(o);
            }
        }
        return new RoadJob.Villages(a, b, others);
    }

    static boolean near(BlockPos p, BlockPos a, BlockPos b, int reach) {
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ(), l2 = dx * dx + dz * dz;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / l2));
        return Math.hypot(p.getX() - (a.getX() + t * dx), p.getZ() - (a.getZ() + t * dz)) <= reach;
    }

    private boolean queued(String key) {
        for (RoadJob.Villages v : queue) {
            if (Network.key(v.a().id(), v.b().id()).equals(key)) {
                return true;
            }
        }
        for (RoadJob j : jobs) {
            if (j.ends instanceof RoadJob.Villages v && Network.key(v.a().id(), v.b().id()).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Queues a road between two villages at once, ahead of the others. */
    public void link(VillageInfo a, VillageInfo b, List<VillageInfo> all) {
        queue.addFirst(villages(a, b, all));
    }

    /** A player's road or bridge between two points (the staff). False if he has one being made already. */
    public boolean lay(ServerPlayer player, BlockPos a, BlockPos b, RoadJob.Spec spec) {
        return lay(player.getUUID(), player.serverLevel(), a, b, spec);
    }

    /** A road between two points for {@code owner} (a player, or {@link #CONSOLE}), kept so it can be taken up again. */
    public boolean lay(UUID owner, ServerLevel level, BlockPos a, BlockPos b, RoadJob.Spec spec) {
        if (jobs.stream().anyMatch(j -> owner.equals(j.owner))) {
            return false;
        }
        int corridor = Math.min(RoadsConfig.CORRIDOR.get(), 48 + (int) Math.sqrt(a.distSqr(b)) / 8);
        jobs.add(new RoadJob(++ids, owner, level, new RoadJob.Points(a, b), spec, new RoadJob.Survey(corridor, settings())));
        LOG.info("Road {}: a {} from {} to {} for {}", ids, spec.mode() == RoadJob.Mode.BRIDGE ? "bridge" : "road", a.toShortString(),
                b.toShortString(), nameOf(owner));
        return true;
    }

    /** Who lays roads from the server console (operators' commands). */
    public static final UUID CONSOLE = new UUID(0, 0);

    /** Takes up a player's last road: everything it changed is put back as it was. */
    public String undo(ServerPlayer player) {
        return undo(player.getUUID());
    }

    public String undo(UUID owner) {
        for (RoadJob j : jobs) {
            if (owner.equals(j.owner)) {
                j.fail("cancelled");
                last.put(owner, j);
            }
        }
        RoadJob j = last.remove(owner);
        if (j == null || j.undo == null || j.undo.isEmpty()) {
            return "You have no road to take up.";
        }
        undos.add(new Undo(owner, j.level, j.undo, j.footprint(), j.data));
        if (j.wayKey != null) {
            j.data.way(j.wayKey, null);
        }
        LOG.info("Road {}: taken up ({} blocks put back)", j.id, j.undo.size());
        return "Taking up your road (" + j.undo.size() + " blocks)...";
    }

    public void cancelAll() {
        for (RoadJob j : jobs) {
            j.fail("cancelled");
            j.releaseAll();
        }
        jobs.clear();
        queue.clear();
    }

    public void stop() {
        cancelAll();
        for (Undo u : undos) {
            u.gate.releaseAll();
        }
        planner.shutdownNow();
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        for (RoadJob j : jobs) {
            String what = j.ends instanceof RoadJob.Villages v ? v.a().name() + " -> " + v.b().name() : "staff road of " + nameOf(j.owner);
            out.add("#" + j.id + " " + what + ": " + j.progress());
        }
        out.add(queue.size() + " village link(s) waiting");
        RoadsData d = RoadsData.get(server.overworld());
        out.add(d.built.size() + " village road(s) built, " + d.failed.size() + " failed (tried again later), " + d.cells.size() + " road columns known");
        return out;
    }

    private String nameOf(UUID id) {
        if (CONSOLE.equals(id)) {
            return "the console";
        }
        ServerPlayer p = id == null ? null : server.getPlayerList().getPlayer(id);
        return p != null ? p.getGameProfile().getName() : String.valueOf(id);
    }

    static RoadJob.Spec autoSpec() {
        int w = RoadsConfig.AUTO_WIDTH.get();
        return new RoadJob.Spec(RoadJob.Mode.ROAD, RoadsConfig.AUTO_STYLE.get(), RoadsConfig.AUTO_BRIDGE.get(), w % 2 == 0 ? w + 1 : w);
    }

    static RoadJob.Survey survey() {
        return new RoadJob.Survey(RoadsConfig.CORRIDOR.get(), settings());
    }

    static RoadPlanner.Settings settings() {
        RoadPlanner.Settings d = RoadPlanner.Settings.DEFAULT;
        return new RoadPlanner.Settings(RoadsConfig.MAX_SPAN.get(), RoadsConfig.BRIDGE_COST.get(), d.bridgeFixed(), d.maxStep(), d.budget());
    }

    /** Putting back what a road changed, last first, chunk by chunk. */
    static final class Undo {
        final UUID owner;
        final ServerLevel level;
        final List<Map.Entry<BlockPos, BlockState>> entries;
        final ChunkGate gate;
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet footprint;
        final RoadsData data;
        int cursor, count;

        Undo(UUID owner, ServerLevel level, Map<BlockPos, BlockState> undo, it.unimi.dsi.fastutil.longs.LongOpenHashSet footprint, RoadsData data) {
            this.owner = owner;
            this.level = level;
            this.entries = new ArrayList<>(undo.entrySet());
            java.util.Collections.reverse(this.entries);
            this.gate = new ChunkGate(level);
            this.footprint = footprint;
            this.data = data;
        }

        /** True when done. */
        boolean tick(long now, int budget) {
            gate.sweep(now);
            int n = 0;
            while (cursor < entries.size() && n < budget) {
                Map.Entry<BlockPos, BlockState> e = entries.get(cursor);
                ChunkPos c = new ChunkPos(e.getKey());
                if (gate.ask(c.x, c.z, now) == null) {
                    return false;
                }
                level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
                cursor++;
                n++;
                count++;
            }
            if (cursor < entries.size()) {
                return false;
            }
            footprint.forEach(k -> data.cells.remove(k));
            data.setDirty();
            gate.releaseAll();
            return true;
        }
    }
}

package dev.hywmill.integration.journeymap;

import dev.hywmill.HywMill;
import dev.hywmill.client.MapMarkers;
import dev.hywmill.core.HmLog;
import dev.hywmill.net.MapPayloads;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.model.MapImage;
import journeymap.api.v2.client.model.TextProperties;
import journeymap.api.v2.common.JourneyMapPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * JourneyMap (1.8, optional): HywMill's map markers on the minimap and the full map: villages (what each is, whom it
 * answers to, in its colours), hosts on the march, sieges, field battles, armies of relief and the player's orders, each
 * with its clock counting down live. Loaded by JourneyMap only (its plugin annotation); nothing else refers to this class.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public final class HywMillJourneyMap implements IClientPlugin {
    private IClientAPI api;
    private final Map<String, MarkerOverlay> shown = new HashMap<>();
    private final Map<String, MapPayloads.Marker> was = new HashMap<>();
    private int seen = -1, ticks;

    @Override
    public String getModId() {
        return HywMill.MODID;
    }

    @Override
    public void initialize(IClientAPI api) {
        this.api = api;
        NeoForge.EVENT_BUS.addListener(this::tick);
        HmLog.info("JourneyMap: HywMill map markers on");
    }

    private void tick(ClientTickEvent.Post event) {
        if (++ticks % 20 != 0 || api == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!shown.isEmpty()) {
                api.removeAll(HywMill.MODID);
                shown.clear();
                was.clear();
            }
            return;
        }
        try {
            sync(mc.level.getGameTime());
        } catch (Exception ex) {
            HmLog.warn("JourneyMap markers failed: {}", ex.toString());
        }
    }

    /** Shows new markers, moves and relabels the ones that changed (and every clock), removes the ones gone. */
    private void sync(long gameTime) throws Exception {
        Set<String> live = new HashSet<>();
        boolean fresh = seen != MapMarkers.version();
        seen = MapMarkers.version();
        for (MapPayloads.Marker m : MapMarkers.markers()) {
            live.add(m.id());
            MarkerOverlay o = shown.get(m.id());
            MapPayloads.Marker before = was.get(m.id());
            if (o == null || before == null || before.kind() != m.kind() || before.colour() != m.colour()) {
                if (o != null) {
                    api.remove(o);
                }
                o = overlay(m);
                shown.put(m.id(), o);
                was.put(m.id(), m);
                label(o, m, gameTime);
                api.show(o);
                continue;
            }
            if (m.dueAt() < 0 && !fresh) {
                continue; // no clock and nothing new
            }
            if (before.x() != m.x() || before.z() != m.z()) {
                o.setPoint(new BlockPos(m.x(), 64, m.z()));
            }
            was.put(m.id(), m);
            label(o, m, gameTime);
            o.flagForRerender();
        }
        for (String id : new HashSet<>(shown.keySet())) {
            if (!live.contains(id)) {
                api.remove(shown.remove(id));
                was.remove(id);
            }
        }
    }

    private static void label(MarkerOverlay o, MapPayloads.Marker m, long gameTime) {
        String clock = MapMarkers.clock(m, gameTime);
        o.setLabel(clock.isEmpty() ? m.label() : m.label() + " " + clock);
        o.setTitle(clock.isEmpty() ? m.detail() : m.detail() + " " + clock);
    }

    private static MarkerOverlay overlay(MapPayloads.Marker m) {
        String icon = switch (m.kind()) {
            case VILLAGE -> "village";
            case CAPITAL -> "capital";
            case PROVINCE -> "province";
            case VASSAL -> "vassal";
            case HOST -> "host";
            case SIEGE -> "siege";
            case BATTLE -> "battle";
            case ORDER -> "order";
            case RELIEF -> "relief";
            case COLUMN -> "column";
        };
        boolean village = m.kind().ordinal() <= MapPayloads.Kind.VASSAL.ordinal();
        int size = village ? 14 : 18;
        MapImage image = new MapImage(ResourceLocation.fromNamespaceAndPath(HywMill.MODID, "textures/map/" + icon + ".png"), 32, 32)
                .setColor(m.colour() & 0xFFFFFF).setDisplayWidth(size).setDisplayHeight(size).centerAnchors();
        MarkerOverlay o = new MarkerOverlay(HywMill.MODID, new BlockPos(m.x(), 64, m.z()), image);
        o.setDimension(Level.OVERWORLD);
        o.setOverlayGroupName(village ? "HywMill villages" : "HywMill wars");
        o.setDisplayOrder(village ? 100 : 200);
        o.setTextProperties(new TextProperties().setColor(m.colour() & 0xFFFFFF).setScale(village ? 1f : 1.2f).setBackgroundOpacity(0.4f)
                .setOffsetY(size / 2 + 2));
        if (village) {
            o.setMinZoom(0); // villages from afar; events always
        }
        return o;
    }
}

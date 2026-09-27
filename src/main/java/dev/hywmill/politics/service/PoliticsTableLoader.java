package dev.hywmill.politics.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hywmill.core.HmLog;
import dev.hywmill.politics.PoliticsData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads {@code data/<namespace>/hywmill_politics/*.json} on every datapack (re)load, in
 * resource-location order, into an immutable {@link PoliticsData} snapshot (the same reload pattern
 * as the M3/M4 tables; political <em>state</em> lives in the ledger, never here).
 */
public final class PoliticsTableLoader extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "hywmill_politics";
    private static volatile PoliticsData current = PoliticsData.SHIPPED_FALLBACK;

    public PoliticsTableLoader() {
        super(new Gson(), DIRECTORY);
    }

    public static PoliticsData current() {
        return current;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        List<JsonObject> ordered = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : new TreeMap<>(files).entrySet()) {
            if (e.getValue().isJsonObject()) {
                ordered.add(e.getValue().getAsJsonObject());
            } else {
                problems.add(e.getKey() + ": not a JSON object");
            }
        }
        PoliticsData d = PoliticsData.fromJson(ordered, problems);
        current = d;
        HmLog.info("Politics tables loaded from {} file(s): {} culture patch(es)", ordered.size(), d.cultures().size());
        for (String p : problems) {
            HmLog.warn("Politics data entry ignored: {}", p);
        }
    }
}

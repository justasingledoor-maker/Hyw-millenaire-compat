package dev.hywmill.recruit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hywmill.core.HmLog;
import dev.hywmill.garrison.tables.GarrisonTables;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<namespace>/hywmill_squads/*.json} (post-M5 culture squads) on every datapack (re)load, in resource-location order. */
public final class SquadTableLoader extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "hywmill_squads";

    public SquadTableLoader() {
        super(new Gson(), DIRECTORY);
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
        Squads s = Squads.fromJson(ordered, GarrisonTables.current().units(), problems);
        Squads.set(s);
        HmLog.info("Squads loaded from {} file(s): {} culture(s), {} squads in all, discount {}", ordered.size(), s.cultures().size(),
                s.cultures().values().stream().mapToInt(List::size).sum(), s.squadDiscount());
        for (String p : problems) {
            HmLog.warn("Squad data entry ignored: {}", p);
        }
    }
}

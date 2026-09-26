package dev.hywmill.garrison.duty;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hywmill.core.HmLog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<namespace>/hywmill_duties/*.json} on every datapack (re)load, in resource-location order. */
public final class DutyTableLoader extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "hywmill_duties";

    public DutyTableLoader() {
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
        DutyTables t = DutyTables.fromJson(ordered, problems);
        DutyTables.set(t);
        HmLog.info("Duty tables loaded from {} file(s): {} culture(s); raids {}", ordered.size(), t.cultures().size(),
                t.defaults().raid().enabled() ? "on" : "off");
        for (String p : problems) {
            HmLog.warn("Duty data entry ignored: {}", p);
        }
    }
}

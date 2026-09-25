package dev.hywmill.military.doctrine;

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

/** Loads {@code data/<namespace>/hywmill_doctrine/*.json} on every datapack (re)load. */
public final class DoctrineLoader extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "hywmill_doctrine";

    public DoctrineLoader() {
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
        DoctrineDefaults d = DoctrineDefaults.fromJson(ordered, problems);
        DoctrineTables.set(d);
        HmLog.info("Doctrine defaults loaded from {} file(s): {} culture(s), {} village type(s), lone building={}",
                ordered.size(), d.cultures().size(), d.villageTypes().size(), !d.loneBuilding().isEmpty());
        for (String p : problems) {
            HmLog.warn("Doctrine entry ignored: {}", p);
        }
    }
}

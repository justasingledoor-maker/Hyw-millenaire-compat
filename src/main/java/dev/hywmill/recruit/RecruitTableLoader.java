package dev.hywmill.recruit;

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

/** Loads {@code data/<namespace>/hywmill_recruitment/*.json} on every datapack (re)load, in resource-location order. */
public final class RecruitTableLoader extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "hywmill_recruitment";

    public RecruitTableLoader() {
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
        RecruitTables t = RecruitTables.fromJson(ordered, problems);
        RecruitTables.set(t);
        HmLog.info("Recruitment tables loaded from {} file(s): {} mercenary type(s), up to {} per purchase, radius {}-{}", ordered.size(),
                t.mercs().size(), t.maxPerPurchase(), t.minRadius(), t.maxRadius());
        for (String p : problems) {
            HmLog.warn("Recruitment data entry ignored: {}", p);
        }
    }
}

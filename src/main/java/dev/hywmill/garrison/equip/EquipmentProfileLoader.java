package dev.hywmill.garrison.equip;

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

/** Loads {@code data/<namespace>/hywmill_equipment/*.json} on every datapack (re)load, in resource-location order. */
public final class EquipmentProfileLoader extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "hywmill_equipment";

    public EquipmentProfileLoader() {
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
        EquipmentProfiles p = EquipmentProfiles.fromJson(ordered, problems);
        EquipmentProfiles.set(p);
        HmLog.info("Equipment profiles loaded from {} file(s): {} culture section(s) (validate with /hywmill dev equipcheck)", ordered.size(),
                p.raw().size());
        for (String s : problems) {
            HmLog.warn("Equipment profile entry ignored: {}", s);
        }
    }
}

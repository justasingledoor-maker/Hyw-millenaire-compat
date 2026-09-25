package dev.hywmill.classify;

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

/**
 * Loads {@code data/<namespace>/hywmill_roles/*.json} from datapacks on every (re)load. Files are
 * merged in resource-location order, so a pack can extend the shipped tables with a new file, or
 * replace one by shipping the same path ({@code data/hywmill/hywmill_roles/norman.json}).
 */
public final class RoleTableLoader extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "hywmill_roles";

    public RoleTableLoader() {
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
        RoleTable table = RoleTable.merge(ordered, problems);
        RoleTables.set(table);
        HmLog.info("Role tables loaded from {} file(s): {} villager type(s), {} building plan set(s)",
                ordered.size(), table.villagers().size(), table.buildings().size());
        for (String p : problems) {
            HmLog.warn("Role table entry ignored: {}", p);
        }
    }
}

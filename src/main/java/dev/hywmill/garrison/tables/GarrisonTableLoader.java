package dev.hywmill.garrison.tables;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hywmill.core.HmLog;
import dev.hywmill.core.Services;
import dev.hywmill.garrison.Recruitment;
import dev.hywmill.garrison.spi.UnitProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads {@code data/<namespace>/hywmill_garrison/*.json} on every datapack (re)load, in
 * resource-location order. Entity types are validated against the live registry through the
 * unit provider and the M3 unit set.
 */
public final class GarrisonTableLoader extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "hywmill_garrison";

    public GarrisonTableLoader() {
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
        UnitProvider units = Services.units();
        GarrisonTables t = GarrisonTables.fromJson(ordered,
                id -> units != null && Recruitment.allowedType(id) && units.isValidUnitType(id), problems);
        GarrisonTables.set(t);
        HmLog.info("Garrison tables loaded from {} file(s): {} unit type(s) ({} enabled), {} culture(s); tier caps {}",
                ordered.size(), t.units().size(), t.units().values().stream().filter(UnitSpec::enabled).count(), t.cultures().size(),
                GarrisonTablesDescribe.caps(t.defaults()));
        for (String p : problems) {
            HmLog.warn("Garrison data entry ignored: {}", p);
        }
    }
}

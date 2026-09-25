package dev.hywmill.military.doctrine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A partial doctrine: only the fields a layer (baseline, culture, village type, lone building,
 * per-village override) actually sets. Immutable.
 */
public final class DoctrinePatch {
    public static final DoctrinePatch EMPTY = new DoctrinePatch(new EnumMap<>(DoctrineField.class));

    private final Map<DoctrineField, Object> values;

    public DoctrinePatch(Map<DoctrineField, Object> values) {
        EnumMap<DoctrineField, Object> copy = new EnumMap<>(DoctrineField.class);
        copy.putAll(values);
        this.values = Collections.unmodifiableMap(copy);
    }

    public Map<DoctrineField, Object> values() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public DoctrinePatch with(DoctrineField field, Object value) {
        EnumMap<DoctrineField, Object> m = new EnumMap<>(DoctrineField.class);
        m.putAll(values);
        m.put(field, value);
        return new DoctrinePatch(m);
    }

    public DoctrinePatch without(DoctrineField field) {
        EnumMap<DoctrineField, Object> m = new EnumMap<>(DoctrineField.class);
        m.putAll(values);
        m.remove(field);
        return new DoctrinePatch(m);
    }

    /** Field-wise merge: {@code over} wins. */
    public DoctrinePatch mergedWith(DoctrinePatch over) {
        EnumMap<DoctrineField, Object> m = new EnumMap<>(DoctrineField.class);
        m.putAll(values);
        m.putAll(over.values);
        return new DoctrinePatch(m);
    }

    /** Parses a JSON object of {@code key: value}; unknown keys and invalid values are reported, not applied. */
    public static DoctrinePatch fromJson(JsonObject o, String where, List<String> problems) {
        EnumMap<DoctrineField, Object> m = new EnumMap<>(DoctrineField.class);
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            DoctrineField f = DoctrineField.byKey(e.getKey());
            if (f == null) {
                problems.add(where + ": unknown doctrine field '" + e.getKey() + "'");
                continue;
            }
            if (!e.getValue().isJsonPrimitive()) {
                problems.add(where + "." + e.getKey() + ": expected a plain value");
                continue;
            }
            try {
                m.put(f, f.parse(e.getValue().getAsString()));
            } catch (IllegalArgumentException ex) {
                problems.add(where + ": " + ex.getMessage());
            }
        }
        return new DoctrinePatch(m);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        values.forEach((k, v) -> sb.append(sb.length() > 1 ? ", " : "").append(k.key).append('=').append(DoctrineField.format(v)));
        return sb.append('}').toString();
    }
}

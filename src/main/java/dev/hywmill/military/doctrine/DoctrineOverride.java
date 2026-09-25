package dev.hywmill.military.doctrine;

import net.minecraft.nbt.CompoundTag;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Per-village doctrine override, persisted in the garrison ledger. Stored as field key → string
 * value so it survives enum/value additions; unknown or invalid entries are dropped with a report.
 */
public final class DoctrineOverride {
    private DoctrineOverride() {}

    public static CompoundTag save(DoctrinePatch p) {
        CompoundTag t = new CompoundTag();
        p.values().forEach((f, v) -> t.putString(f.key, DoctrineField.format(v)));
        return t;
    }

    public static DoctrinePatch load(CompoundTag t, List<String> problems) {
        Map<DoctrineField, Object> m = new EnumMap<>(DoctrineField.class);
        for (String k : t.getAllKeys()) {
            DoctrineField f = DoctrineField.byKey(k);
            if (f == null) {
                problems.add("unknown override field '" + k + "'");
                continue;
            }
            try {
                m.put(f, f.parse(t.getString(k)));
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        return new DoctrinePatch(m);
    }
}

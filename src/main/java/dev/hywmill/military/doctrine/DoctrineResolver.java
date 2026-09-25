package dev.hywmill.military.doctrine;

import dev.hywmill.military.MilitaryTier;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Deterministic doctrine resolution. Layers, each overriding the previous field by field:
 * <ol>
 *   <li>baseline</li>
 *   <li>culture ({@code millenaire:norman})</li>
 *   <li>lone building (only for Millénaire lone buildings)</li>
 *   <li>village type ({@code millenaire:norman/militaire}, or a {@code *} glob)</li>
 *   <li>military tier modifier (relative: commit, reserve, radius; or a forced reserve)</li>
 *   <li>per-village override (persisted; absolute)</li>
 * </ol>
 * Then {@code defenseRadius = clamp(villageRadius + radiusOffset + tierRadius, 40, 160)}.
 * Every field reports which layer set it. Player-controlled villages use the same chain;
 * their controller is handled by {@code assistController}, never by the faction identity.
 */
public final class DoctrineResolver {
    private DoctrineResolver() {}

    public record Context(String culture, String villageType, int villageRadius, boolean loneBuilding, MilitaryTier tier) {}

    public record Resolved(Doctrine doctrine, Map<DoctrineField, String> sources) {
        public String sourceOf(DoctrineField f) {
            return sources.getOrDefault(f, "baseline");
        }
    }

    public static Resolved resolve(DoctrineDefaults d, Context ctx, @Nullable DoctrinePatch override) {
        Map<DoctrineField, Object> v = new EnumMap<>(DoctrineField.class);
        Map<DoctrineField, String> src = new EnumMap<>(DoctrineField.class);
        apply(v, src, d.baseline(), "baseline");
        DoctrinePatch culture = d.cultures().get(ctx.culture());
        if (culture != null) {
            apply(v, src, culture, "culture " + ctx.culture());
        }
        if (ctx.loneBuilding()) {
            apply(v, src, d.loneBuilding(), "lone building");
        }
        String typeKey = d.villageTypeKey(ctx.villageType());
        if (typeKey != null) {
            apply(v, src, d.villageTypes().get(typeKey), "village type " + typeKey);
        }
        int commit = (Integer) v.get(DoctrineField.COMMIT_PER_THREAT);
        int reserve = (Integer) v.get(DoctrineField.RESERVE);
        int tierRadius = 0;
        TierModifier tm = d.tier(ctx.tier());
        if (!tm.isIdentity()) {
            String tag = "tier " + ctx.tier();
            if (tm.commitDelta() != 0) {
                commit += tm.commitDelta();
                src.put(DoctrineField.COMMIT_PER_THREAT, src.get(DoctrineField.COMMIT_PER_THREAT) + " + " + tag);
            }
            if (tm.reserveSet() != null) {
                reserve = tm.reserveSet();
                src.put(DoctrineField.RESERVE, tag);
            } else if (tm.reserveDelta() != 0) {
                reserve += tm.reserveDelta();
                src.put(DoctrineField.RESERVE, src.get(DoctrineField.RESERVE) + " + " + tag);
            }
            if (tm.radiusDelta() != 0) {
                tierRadius = tm.radiusDelta();
                src.put(DoctrineField.RADIUS_OFFSET, src.get(DoctrineField.RADIUS_OFFSET) + " + " + tag);
            }
        }
        if (override != null && !override.isEmpty()) {
            for (Map.Entry<DoctrineField, Object> e : override.values().entrySet()) {
                v.put(e.getKey(), e.getValue());
                src.put(e.getKey(), "override");
            }
            if (override.values().containsKey(DoctrineField.COMMIT_PER_THREAT)) {
                commit = (Integer) override.values().get(DoctrineField.COMMIT_PER_THREAT);
            }
            if (override.values().containsKey(DoctrineField.RESERVE)) {
                reserve = (Integer) override.values().get(DoctrineField.RESERVE);
            }
            if (override.values().containsKey(DoctrineField.RADIUS_OFFSET)) {
                tierRadius = 0; // an explicit offset is absolute
            }
        }
        int radius = clamp(ctx.villageRadius() + (Integer) v.get(DoctrineField.RADIUS_OFFSET) + tierRadius,
                Doctrine.MIN_DEFENSE_RADIUS, Doctrine.MAX_DEFENSE_RADIUS);
        Doctrine doctrine = Doctrine.of(v, radius, Math.max(1, commit), Math.max(0, reserve));
        return new Resolved(doctrine, Collections.unmodifiableMap(src));
    }

    private static void apply(Map<DoctrineField, Object> v, Map<DoctrineField, String> src, DoctrinePatch p, String source) {
        for (Map.Entry<DoctrineField, Object> e : p.values().entrySet()) {
            v.put(e.getKey(), e.getValue());
            src.put(e.getKey(), source);
        }
    }

    static int clamp(int x, int lo, int hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}

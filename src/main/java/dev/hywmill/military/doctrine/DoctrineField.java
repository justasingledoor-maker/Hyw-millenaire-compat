package dev.hywmill.military.doctrine;

import java.util.Locale;

/**
 * Every doctrine parameter, its JSON/command key and value type. Parsing and validation live
 * here so JSON defaults, persisted overrides and commands accept exactly the same values.
 */
public enum DoctrineField {
    RADIUS_OFFSET("radiusOffset", Kind.INT, -64, 160),
    PROACTIVE("proactive", Kind.BOOL, 0, 0),
    COMMIT_PER_THREAT("commitPerThreat", Kind.INT, 1, 32),
    RESERVE("reserve", Kind.INT, 0, 32),
    MILITIA_POLICY("militiaPolicy", Kind.ENUM, 0, 0),
    SHELTER_RADIUS("shelterRadius", Kind.INT, -1, 256),
    ASSIST_PLAYERS("assistPlayers", Kind.ENUM, 0, 0),
    ASSIST_MIN_REPUTATION("assistMinReputation", Kind.INT, -100_000, 100_000),
    ASSIST_PROVOKING_PLAYER("assistProvokingPlayer", Kind.BOOL, 0, 0),
    ASSIST_CONTROLLER("assistController", Kind.ENUM, 0, 0),
    ALERT_TICKS("alertTicks", Kind.INT, 1, 72_000),
    ENGAGED_TICKS("engagedTicks", Kind.INT, 1, 72_000),
    RECOVERY_TICKS("recoveryTicks", Kind.INT, 1, 72_000);

    enum Kind { INT, BOOL, ENUM }

    public final String key;
    final Kind kind;
    final int min;
    final int max;

    DoctrineField(String key, Kind kind, int min, int max) {
        this.key = key;
        this.kind = kind;
        this.min = min;
        this.max = max;
    }

    public static DoctrineField byKey(String key) {
        for (DoctrineField f : values()) {
            if (f.key.equalsIgnoreCase(key)) {
                return f;
            }
        }
        return null;
    }

    /** Parses and validates a value for this field; throws IllegalArgumentException with a readable message. */
    public Object parse(String raw) {
        String s = raw.trim();
        switch (kind) {
            case INT -> {
                int v;
                try {
                    v = Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(key + " expects an integer, got '" + raw + "'");
                }
                if (v < min || v > max) {
                    throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ", got " + v);
                }
                return v;
            }
            case BOOL -> {
                if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
                    return Boolean.parseBoolean(s);
                }
                throw new IllegalArgumentException(key + " expects true or false, got '" + raw + "'");
            }
            default -> {
                Class<? extends Enum<?>> type = enumType();
                for (Enum<?> c : type.getEnumConstants()) {
                    if (c.name().equalsIgnoreCase(s)) {
                        return c;
                    }
                }
                throw new IllegalArgumentException(key + " expects one of " + java.util.Arrays.toString(type.getEnumConstants()) + ", got '" + raw + "'");
            }
        }
    }

    public Class<? extends Enum<?>> enumType() {
        return switch (this) {
            case MILITIA_POLICY -> MilitiaPolicy.class;
            case ASSIST_PLAYERS -> AssistMode.class;
            case ASSIST_CONTROLLER -> ControllerAssist.class;
            default -> null;
        };
    }

    /** Value to its stable string form (JSON, NBT, commands). */
    public static String format(Object v) {
        return v instanceof Enum<?> e ? e.name() : String.valueOf(v).toLowerCase(Locale.ROOT);
    }
}

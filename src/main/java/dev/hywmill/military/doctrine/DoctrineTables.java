package dev.hywmill.military.doctrine;

/** The doctrine defaults currently loaded from datapacks (replaced wholesale on every reload). */
public final class DoctrineTables {
    private static volatile DoctrineDefaults current = DoctrineDefaults.BUILTIN;

    private DoctrineTables() {}

    public static DoctrineDefaults current() {
        return current;
    }

    public static void set(DoctrineDefaults d) {
        current = d;
    }
}

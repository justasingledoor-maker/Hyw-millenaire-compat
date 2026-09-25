package dev.hywmill.military.defense;

/**
 * The defense radius is horizontal (a cylinder around the village center): Millénaire villages
 * span hills and valleys, and an attacker on a slope above the houses is just as close.
 * The vertical extent of the scan comes from the village's building bounds.
 */
public final class DefenseArea {
    private DefenseArea() {}

    public static boolean inside(double cx, double cz, int radius, double x, double z) {
        double dx = x - cx, dz = z - cz;
        return dx * dx + dz * dz <= (double) radius * radius;
    }
}

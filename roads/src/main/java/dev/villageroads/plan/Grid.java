package dev.villageroads.plan;

/**
 * What a road surveyor saw of the land, one cell per block column over a rectangle: what the column's top is (land, water,
 * a building...) and its height. Cells outside the surveyed corridor stay {@link #UNKNOWN} (no road there). Pure.
 */
public final class Grid {
    /** What a column is, by its top (plants and trees set aside). */
    public static final byte UNKNOWN = 0, LAND = 1, WATER = 2, LAVA = 3, STRUCTURE = 4, ROAD = 5;
    /**
     * Flags: a tree stands on it; within reach of a building (no road there); inside an end village; inside another village;
     * next to water; two blocks from water (a road's edge there would fill the water's rim).
     */
    public static final byte TREE = 1, NEAR_STRUCTURE = 2, INTERIOR = 4, FOREIGN = 8, SHORE = 16, NEAR_WATER = 32;

    public final int x0, z0, w, h;
    private final short[] y;
    private final byte[] kind, flags;

    public Grid(int x0, int z0, int w, int h) {
        this.x0 = x0;
        this.z0 = z0;
        this.w = w;
        this.h = h;
        this.y = new short[w * h];
        this.kind = new byte[w * h];
        this.flags = new byte[w * h];
    }

    public boolean in(int x, int z) {
        return x >= x0 && z >= z0 && x < x0 + w && z < z0 + h;
    }

    public int idx(int x, int z) {
        return (z - z0) * w + (x - x0);
    }

    public int x(int i) {
        return x0 + i % w;
    }

    public int z(int i) {
        return z0 + i / w;
    }

    public int size() {
        return w * h;
    }

    /** A column: its kind and its height (the ground's top block; for water, the water's top block). */
    public void set(int x, int z, byte k, int height, boolean tree) {
        int i = idx(x, z);
        kind[i] = k;
        y[i] = (short) height;
        if (tree) {
            flags[i] |= TREE;
        }
    }

    public byte kind(int i) {
        return kind[i];
    }

    public int y(int i) {
        return y[i];
    }

    public boolean has(int i, byte flag) {
        return (flags[i] & flag) != 0;
    }

    public boolean surveyed(int x, int z) {
        return in(x, z) && kind[idx(x, z)] != UNKNOWN;
    }

    /** A road may be laid on it: land or an old road, clear of buildings (an old road may run between them). */
    public boolean passable(int i) {
        byte k = kind[i];
        return k == ROAD || k == LAND && (flags[i] & (NEAR_STRUCTURE | FOREIGN)) == 0;
    }

    /** Marks the cells within {@code r} of a village centre: one the road links ({@code end}), or another it must keep out of. */
    public void village(int cx, int cz, int r, boolean end) {
        byte f = end ? INTERIOR : FOREIGN;
        for (int z = Math.max(z0, cz - r); z < Math.min(z0 + h, cz + r + 1); z++) {
            for (int x = Math.max(x0, cx - r); x < Math.min(x0 + w, cx + r + 1); x++) {
                long dx = x - cx, dz = z - cz;
                if (dx * dx + dz * dz <= (long) r * r) {
                    flags[idx(x, z)] |= f;
                }
            }
        }
    }

    /**
     * Once surveyed: the cells within {@code clearance} of a building are closed to the road (it snakes round them), and land
     * next to water is shore (a bridge may start there).
     */
    public void finish(int clearance) {
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] != STRUCTURE) {
                continue;
            }
            int cx = x(i), cz = z(i);
            for (int z = Math.max(z0, cz - clearance); z < Math.min(z0 + h, cz + clearance + 1); z++) {
                for (int x = Math.max(x0, cx - clearance); x < Math.min(x0 + w, cx + clearance + 1); x++) {
                    flags[idx(x, z)] |= NEAR_STRUCTURE;
                }
            }
        }
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] != LAND && kind[i] != ROAD) {
                continue;
            }
            int cx = x(i), cz = z(i), near = 3;
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    if (in(cx + dx, cz + dz) && kind[idx(cx + dx, cz + dz)] == WATER) {
                        near = Math.min(near, Math.max(Math.abs(dx), Math.abs(dz)));
                    }
                }
            }
            flags[i] |= near == 1 ? SHORE : near == 2 ? NEAR_WATER : 0;
        }
    }
}

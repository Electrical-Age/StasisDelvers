package org.erisia.stasis.towergen;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which parts of some block cells a mesh covers, at {@link #N}^3 sub-cells per block: the game turns it into the outlines
 * of the poles hidden under a generated tower (Baughn: a hidden pole's outline showed where nothing was drawn). Mesh
 * coordinates are the tower's: x and z from the core's centre, y from its bottom face, in blocks; a cell (cx, cy, cz)
 * spans x cx-0.5..cx+0.5, y cy..cy+1, z cz-0.5..cz+0.5. Triangles are sampled at a quarter of a sub-cell, so a surface
 * marks the sub-cells it passes through; the thin members of a tower leave no hollow inside at this size.
 */
public final class CellCover {
    public static final int N = 8;

    private CellCover() {
    }

    /** A cell, relative to the core block. */
    public record Cell(int x, int y, int z) {
    }

    /** The cells of the tower's poles: the mast above the core and each boom (dir 0 east, 1 south, 2 west, 3 north). */
    public static List<Cell> poleCells(Layout layout, int coreHeight) {
        List<Cell> out = new java.util.ArrayList<>();
        for (int k = 1; k <= layout.H() - coreHeight; k++) out.add(new Cell(0, coreHeight - 1 + k, 0));
        for (Layout.Boom b : layout.booms()) {
            int dx = b.dir() == 0 ? 1 : b.dir() == 2 ? -1 : 0, dz = b.dir() == 1 ? 1 : b.dir() == 3 ? -1 : 0;
            for (int i = 1; i <= b.length(); i++) out.add(new Cell(dx * i, b.y() - 1, dz * i));
        }
        return out;
    }

    /** The covered sub-cells of each cell (bit (x * N + y) * N + z), in the cells' order; empty where nothing is drawn. */
    public static Map<Cell, BitSet> cover(Mesh m, List<Cell> cells) {
        Map<Cell, BitSet> out = new LinkedHashMap<>();
        for (Cell c : cells) out.put(c, new BitSet(N * N * N));
        if (cells.isEmpty()) return out;
        double lx = Double.MAX_VALUE, ly = lx, lz = lx, hx = -lx, hy = hx, hz = hx;
        for (Cell c : cells) {
            lx = Math.min(lx, c.x() - 0.5); hx = Math.max(hx, c.x() + 0.5);
            ly = Math.min(ly, c.y()); hy = Math.max(hy, c.y() + 1);
            lz = Math.min(lz, c.z() - 0.5); hz = Math.max(hz, c.z() + 0.5);
        }
        double step = 0.25 / N;
        for (int t = 0; t < m.tris; t++) {
            int o = 9 * t;
            double ax = m.pos[o], ay = m.pos[o + 1], az = m.pos[o + 2];
            double bx = m.pos[o + 3], by = m.pos[o + 4], bz = m.pos[o + 5];
            double cx = m.pos[o + 6], cy = m.pos[o + 7], cz = m.pos[o + 8];
            if (Math.max(ax, Math.max(bx, cx)) < lx || Math.min(ax, Math.min(bx, cx)) > hx
                || Math.max(ay, Math.max(by, cy)) < ly || Math.min(ay, Math.min(by, cy)) > hy
                || Math.max(az, Math.max(bz, cz)) < lz || Math.min(az, Math.min(bz, cz)) > hz) continue;
            double e = Math.max(len(bx - ax, by - ay, bz - az), Math.max(len(cx - ax, cy - ay, cz - az), len(cx - bx, cy - by, cz - bz)));
            int n = Math.max(1, (int) Math.ceil(e / step));
            for (int i = 0; i <= n; i++)
                for (int j = 0; i + j <= n; j++) {
                    double u = i / (double) n, v = j / (double) n;
                    mark(out, ax + (bx - ax) * u + (cx - ax) * v, ay + (by - ay) * u + (cy - ay) * v, az + (bz - az) * u + (cz - az) * v);
                }
        }
        return out;
    }

    private static double len(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static void mark(Map<Cell, BitSet> out, double x, double y, double z) {
        int bx = (int) Math.floor(x + 0.5), by = (int) Math.floor(y), bz = (int) Math.floor(z + 0.5);
        BitSet s = out.get(new Cell(bx, by, bz));
        if (s == null) return;
        int sx = clamp((int) ((x + 0.5 - bx) * N)), sy = clamp((int) ((y - by) * N)), sz = clamp((int) ((z + 0.5 - bz) * N));
        s.set((sx * N + sy) * N + sz);
    }

    private static int clamp(int i) {
        return Math.max(0, Math.min(N - 1, i));
    }
}

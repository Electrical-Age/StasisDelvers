package org.erisia.stasis.compat.bluemap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Outlines of the filled cells of a grid, as polygons with holes (pure: no Minecraft or BlueMap state). The BlueMap
 * overlay draws stopped land with these (λ-Nero): one shape per connected area instead of a square per chunk, since the
 * spread map covers about a million chunks.
 * <p>
 * Grid coordinates: cell (i, j) covers [i, i+1] x [j, j+1]. Every boundary edge is walked with its filled cell on the
 * left, so (with j as the second axis) outer rings have positive signed area and holes negative. Where two filled cells
 * touch only at a corner, the walk turns left there, so they come out as separate rings that share a corner point; no ring
 * crosses itself. Collinear points are dropped.
 */
public final class GridOutline {
    private GridOutline() {
    }

    /** A ring of grid points: x[k], z[k], closed implicitly (the last point joins the first). */
    public record Ring(int[] x, int[] z) {
        public int size() {
            return x.length;
        }

        /** Twice the signed area (positive for outer rings). */
        public long area2() {
            long a = 0;
            for (int k = 0, n = x.length; k < n; k++) {
                int l = (k + 1) % n;
                a += (long) x[k] * z[l] - (long) x[l] * z[k];
            }
            return a;
        }

        /** Is the point (px, pz) inside (even-odd rule)? The point must not lie on the ring. */
        public boolean contains(double px, double pz) {
            boolean in = false;
            for (int k = 0, n = x.length, l = n - 1; k < n; l = k++)
                if ((z[k] > pz) != (z[l] > pz) && px < (double) (x[l] - x[k]) * (pz - z[k]) / (z[l] - z[k]) + x[k]) in = !in;
            return in;
        }
    }

    /** An outer ring and the holes in it (islands inside a hole are polygons of their own). */
    public record Polygon(Ring outer, List<Ring> holes) {
    }

    // directions: 0 = +x, 1 = +z, 2 = -x, 3 = -z (left of d is d + 1)
    private static final int[] DX = {1, 0, -1, 0}, DZ = {0, 1, 0, -1};

    /**
     * The polygons of the filled cells of a w x h grid, filled[i + j * w]. A hole is given to the smallest outer ring
     * that holds it.
     */
    public static List<Polygon> trace(boolean[] filled, int w, int h) {
        List<Ring> rings = rings(filled, w, h);
        List<Ring> outers = new ArrayList<>();
        List<Ring> holes = new ArrayList<>();
        for (Ring r : rings) (r.area2() > 0 ? outers : holes).add(r);
        // bounding boxes of the outers, to skip most point-in-polygon tests
        int[][] box = new int[outers.size()][];
        for (int o = 0; o < outers.size(); o++) box[o] = bounds(outers.get(o));
        List<List<Ring>> owned = new ArrayList<>();
        for (int o = 0; o < outers.size(); o++) owned.add(new ArrayList<>());
        for (Ring hole : holes) {
            // a point inside the filled cell left of the hole's first edge: inside its owner, on no ring
            int d = dir(hole.x[0], hole.z[0], hole.x[1], hole.z[1]);
            double px = hole.x[0] + 0.5 * DX[d] + 0.5 * DX[(d + 1) & 3], pz = hole.z[0] + 0.5 * DZ[d] + 0.5 * DZ[(d + 1) & 3];
            int best = -1;
            long bestArea = Long.MAX_VALUE;
            for (int o = 0; o < outers.size(); o++) {
                int[] b = box[o];
                if (px < b[0] || px > b[2] || pz < b[1] || pz > b[3]) continue;
                long a = outers.get(o).area2();
                if (a < bestArea && outers.get(o).contains(px, pz)) {
                    best = o;
                    bestArea = a;
                }
            }
            if (best >= 0) owned.get(best).add(hole);   // (always found: a hole lies in filled cells' outline)
        }
        List<Polygon> out = new ArrayList<>(outers.size());
        for (int o = 0; o < outers.size(); o++) out.add(new Polygon(outers.get(o), owned.get(o)));
        return out;
    }

    private static int[] bounds(Ring r) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int k = 0; k < r.size(); k++) {
            x0 = Math.min(x0, r.x[k]);
            z0 = Math.min(z0, r.z[k]);
            x1 = Math.max(x1, r.x[k]);
            z1 = Math.max(z1, r.z[k]);
        }
        return new int[]{x0, z0, x1, z1};
    }

    private static int dir(int x0, int z0, int x1, int z1) {
        return x1 > x0 ? 0 : z1 > z0 ? 1 : x1 < x0 ? 2 : 3;
    }

    /** Every boundary ring, unsorted. */
    static List<Ring> rings(boolean[] filled, int w, int h) {
        // bit d of edges[v]: a boundary edge leaves vertex v in direction d, with its filled cell on the left
        int vw = w + 1;
        byte[] edges = new byte[vw * (h + 1)];
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                if (!filled[i + j * w]) continue;
                if (j == 0 || !filled[i + (j - 1) * w]) edges[i + j * vw] |= 1;              // bottom edge, +x
                if (i == w - 1 || !filled[i + 1 + j * w]) edges[i + 1 + j * vw] |= 2;        // right edge, +z
                if (j == h - 1 || !filled[i + (j + 1) * w]) edges[i + 1 + (j + 1) * vw] |= 4; // top edge, -x
                if (i == 0 || !filled[i - 1 + j * w]) edges[i + (j + 1) * vw] |= 8;          // left edge, -z
            }
        byte[] used = new byte[edges.length];
        List<Ring> rings = new ArrayList<>();
        int[] xs = new int[64], zs = new int[64], ds = new int[64];
        for (int v = 0; v < edges.length; v++) {
            int free;
            while ((free = edges[v] & ~used[v]) != 0) {
                int d = Integer.numberOfTrailingZeros(free);
                int vx = v % vw, vz = v / vw, n = 0;
                // Each incoming edge has one successor (left first, so a corner-touch keeps hugging the same cell: the
                // pairing is one to one), hence the walk is a cycle and ends back on its first edge.
                while ((used[vx + vz * vw] & (1 << d)) == 0) {
                    used[vx + vz * vw] |= (byte) (1 << d);
                    if (n == xs.length) {
                        xs = Arrays.copyOf(xs, n * 2);
                        zs = Arrays.copyOf(zs, n * 2);
                        ds = Arrays.copyOf(ds, n * 2);
                    }
                    xs[n] = vx;
                    zs[n] = vz;
                    ds[n] = d;
                    n++;
                    vx += DX[d];
                    vz += DZ[d];
                    int bits = edges[vx + vz * vw], left = (d + 1) & 3, right = (d + 3) & 3;
                    d = (bits & (1 << left)) != 0 ? left : (bits & (1 << d)) != 0 ? d : right;
                }
                // keep the corners: points where the direction changes
                int m = 0;
                int[] rx = new int[n], rz = new int[n];
                for (int k = 0; k < n; k++)
                    if (ds[k] != ds[(k + n - 1) % n]) {
                        rx[m] = xs[k];
                        rz[m] = zs[k];
                        m++;
                    }
                rings.add(new Ring(Arrays.copyOf(rx, m), Arrays.copyOf(rz, m)));
            }
        }
        return rings;
    }
}

package org.erisia.stasis;

import it.unimi.dsi.fastutil.longs.LongComparators;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;

import java.util.ArrayList;
import java.util.List;

/**
 * The spreading mode's map (Baughn 2026-10-19; notes/timestop/spread-DESIGN.md): for each 32x32-block cell of the
 * overworld within a radius of world spawn, its RANK: the order in which the stillness reaches it (0 first, N-1 last).
 * At progress P (Spread: floor(coverage * N)) a cell is stilled iff its rank is below P; a cell's "temporal stability"
 * is rank / N, the coverage at which it stills (the stability compass points at stability >= 0.9).
 * <p>
 * Shape v2, "rising water" (Baughn 2026-10-19: v1's stilled regions were too scattered; players need coherent running
 * regions for bases; the guarantee of a way to spawn is dropped after 50 %, there being EA teleporters and cheap suits).
 * Built once per world (a pure function of the deep seed, the spawn pinned at build time, the radius and the split),
 * then saved:
 * <ol>
 * <li>S per cell, a smooth "stability terrain": broad fbm hills and plateaus (features of 1-2 km, so stilled and running
 * land come in coherent regions), lowered round the static deeps' rifts (the stillness starts there) and raised a
 * little round spawn ({@link #stability}).</li>
 * <li>The top (1 - split) of the cells by S take ranks split*N..N-1 in S order: water rising over the terrain, the
 * islands shrinking to their high points, which still last.</li>
 * <li>The other cells take ranks 0..split*N-1 in the order of a 4-connected priority flood from the spawn cell over ALL
 * cells (the frontier cell with the highest S first; v1's growth), keeping only the low cells' order, the later grown
 * the lower. Each low cell was reached from a neighbour taken before it: a high cell (running until the split) or a
 * low cell of higher rank. So until P reaches the split every running low cell is 4-connected to spawn through running
 * cells (high ground is wholly running until then anyway); after it, nothing is promised.</li>
 * </ol>
 * Within a cell the continuous rank is bilinear between the cell centres' ranks ({@link #rank1024}). Cells outside the
 * radius are -1: stilled from the start (Baughn: decision 3).
 * <p>
 * Why 32-block cells, not chunk-sized 16 (Argon: keep a minimum corridor width): a corridor of running cells is at least
 * one cell wide while open, so 32 blocks rather than 16; a quarter of the cells (an 8 km radius is ~196k cells: an
 * 0.8 MB save, ~0.2 s to build, 4x smaller client tiles). With the grid aligned to multiples of 32 and centres at
 * 32i + 16, every chunk lies in one bilinear patch per axis, so a chunk's least and greatest rank are exactly those at
 * its four corner columns ({@link #chunkMin1024}).
 * <p>
 * CLIENT MIRROR: the client answers through the same {@link #rank1024(SpreadRanks, int, int)} (integer arithmetic, so
 * both sides agree on every column: stopped-water collision) from a {@link SpreadTiles} (the rank tiles the server
 * sends), and stasis.fsh computes {@link #stilledSplit}, the same test in 32-bit integers.
 */
public final class SpreadMap implements SpreadRanks {
    /** Cell size in blocks; cell (i, k) covers x 32i..32i+31, z 32k..32k+31 (global, independent of spawn). */
    public static final int CELL = 32;
    static final int HALF = CELL / 2;
    /** The bilinear weights sum to this: rank1024 is the continuous rank times CELL². */
    public static final long SCALE = (long) CELL * CELL;

    /** Bumped when the builder's output changes (a saved map of another version is kept: it's the world's map). */
    public static final int ALGORITHM = 2;

    // S's shape (Baughn 2026-10-19, "rising water"): tuned by SpreadMapTest.coherenceReport. Units: S is about 0..1.
    /** The hills: 2-octave fbm on a lattice of HILL_SCALE blocks (features of 1-2 km), its domain warped by WARP blocks. */
    static final float HILL_SCALE = 1400, WARP = 450, WARP_SCALE = 2200;
    /** Detail on the hills' slopes, so the shores are ragged rather than smooth curves. */
    static final float DETAIL = 0.06f, DETAIL_SCALE = 260;
    /** The dip round a rift: RIFT_DIP at its edge, falling off quadratically to nothing RIFT_REACH blocks out. */
    static final float RIFT_DIP = 0.35f, RIFT_REACH = 200;
    /** The raise round spawn: SPAWN_RAISE at spawn, a Gaussian of SPAWN_SIGMA blocks. */
    static final float SPAWN_RAISE = 0.25f, SPAWN_SIGMA = 800;
    /** The default share of the ranks ordered by the flood from spawn (config spreadConnectedUntil). */
    public static final double CONNECTED_UNTIL = 0.5;

    /** Global cell index of the map's first cell, cells per side, the cell count N (inside the disc). */
    public final int cellX0, cellZ0, n, count;
    public final int spawnX, spawnZ, radius;
    final int[] ranks;

    SpreadMap(int spawnX, int spawnZ, int radius, int[] ranks) {
        this.spawnX = spawnX;
        this.spawnZ = spawnZ;
        this.radius = radius;
        int half = halfCells(radius);
        this.n = 2 * half + 1;
        if (ranks.length != n * n) throw new IllegalArgumentException("spread map: " + ranks.length + " ranks for " + n + "x" + n);
        this.cellX0 = Math.floorDiv(spawnX, CELL) - half;
        this.cellZ0 = Math.floorDiv(spawnZ, CELL) - half;
        this.ranks = ranks;
        int c = 0;
        for (int r : ranks) if (r >= 0) c++;
        this.count = c;
    }

    static int halfCells(int radius) {
        return (radius + CELL - 1) / CELL;
    }

    @Override
    public int rank(int cellX, int cellZ) {
        int i = cellX - cellX0, k = cellZ - cellZ0;
        if (i < 0 || k < 0 || i >= n || k >= n) return -1;
        return ranks[i + k * n];
    }

    /**
     * THE lookup (both sides: the client reads its SpreadTiles through it): the continuous rank at block column (x, z), times SCALE,
     * bilinear between the four nearest cell centres (block 32i + 16). The column is stilled at progress P iff this is
     * below P * SCALE. Exact integer arithmetic.
     */
    public static long rank1024(SpreadRanks s, int x, int z) {
        int fx = x - HALF, fz = z - HALF;
        int i = Math.floorDiv(fx, CELL), k = Math.floorDiv(fz, CELL);
        long a = fx - i * CELL, b = fz - k * CELL;
        return s.rank(i, k) * (CELL - a) * (CELL - b) + s.rank(i + 1, k) * a * (CELL - b)
            + s.rank(i, k + 1) * (CELL - a) * b + s.rank(i + 1, k + 1) * a * b;
    }

    public long rank1024(int x, int z) {
        return rank1024(this, x, z);
    }

    /** Is column (x, z) stilled at progress p? */
    public static boolean stilled(SpreadRanks s, int x, int z, int p) {
        return rank1024(s, x, z) < (long) p * SCALE;
    }

    /**
     * stilled() the way stasis.fsh computes it, for parity (SpreadTilesTest): GLSL 330 has only 32-bit ints, and
     * rank x SCALE overflows them once a map has over ~2M cells (a 30 km radius). The column is stilled iff
     * sum w (r - p) < 0 (the four bilinear weights sum to SCALE); each r - p is split into its high and low 16 bits, so
     * no product or sum leaves int range: A = sum w hi, B = sum w lo (0 <= B < 2^26), and the sum is 65536 (A + B >> 16)
     * plus 0..65535, negative iff A + (B >> 16) < 0. r00 is cell (i, k), r10 (i + 1, k), r01 (i, k + 1), r11 (i + 1, k + 1);
     * a, b the column's offsets 0..31 from cell (i, k)'s centre (as in rank1024). Ranks >= -1 (MISSING too), p >= 0.
     */
    public static boolean stilledSplit(int r00, int r10, int r01, int r11, int a, int b, int p) {
        int w00 = (CELL - a) * (CELL - b), w10 = a * (CELL - b), w01 = (CELL - a) * b, w11 = a * b;
        int d00 = r00 - p, d10 = r10 - p, d01 = r01 - p, d11 = r11 - p;
        int hi = w00 * (d00 >> 16) + w10 * (d10 >> 16) + w01 * (d01 >> 16) + w11 * (d11 >> 16);
        int lo = w00 * (d00 & 0xFFFF) + w10 * (d10 & 0xFFFF) + w01 * (d01 & 0xFFFF) + w11 * (d11 & 0xFFFF);
        return hi + (lo >> 16) < 0;
    }

    /** The least rank1024 over chunk (cx, cz)'s columns: at a corner, as the chunk lies in one bilinear patch. */
    public static long chunkMin1024(SpreadRanks s, int cx, int cz) {
        int x0 = cx << 4, z0 = cz << 4;
        return Math.min(Math.min(rank1024(s, x0, z0), rank1024(s, x0 + 15, z0)), Math.min(rank1024(s, x0, z0 + 15), rank1024(s, x0 + 15, z0 + 15)));
    }

    public static long chunkMax1024(SpreadRanks s, int cx, int cz) {
        int x0 = cx << 4, z0 = cz << 4;
        return Math.max(Math.max(rank1024(s, x0, z0), rank1024(s, x0 + 15, z0)), Math.max(rank1024(s, x0, z0 + 15), rank1024(s, x0 + 15, z0 + 15)));
    }

    /** S per cell of an n x n grid starting at global cell (cellX0, cellZ0); NaN outside the disc. */
    static float[] stability(int seed, int spawnX, int spawnZ, int radius, int cellX0, int cellZ0, int n) {
        // the static deeps' rifts, projected to x/z and bucketed by their 512-block grid cell
        int b0x = Math.floorDiv(cellX0 * CELL, StaticDeeps.CX) - 2, b1x = Math.floorDiv((cellX0 + n) * CELL, StaticDeeps.CX) + 2;
        int b0z = Math.floorDiv(cellZ0 * CELL, StaticDeeps.CZ) - 2, b1z = Math.floorDiv((cellZ0 + n) * CELL, StaticDeeps.CZ) + 2;
        int bw = b1x - b0x + 1, bh = b1z - b0z + 1;
        @SuppressWarnings("unchecked") List<float[]>[] buckets = new List[bw * bh];
        for (int i = b0x; i <= b1x; i++)
            for (int k = b0z; k <= b1z; k++) {
                List<float[]> l = new ArrayList<>(3);
                for (int j = -1; j <= 1; j++) {
                    StaticDeeps.Deep d = StaticDeeps.cell(i, j, k, seed);
                    if (d != null) l.add(new float[]{d.x(), d.z(), d.radius() * StaticDeeps.RIFT});
                }
                buckets[(i - b0x) + (k - b0z) * bw] = l;
            }
        float[] a = new float[n * n];
        long r2 = (long) radius * radius;
        float sig2 = 2 * SPAWN_SIGMA * SPAWN_SIGMA;
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++) {
                int px = (cellX0 + i) * CELL + HALF, pz = (cellZ0 + k) * CELL + HALF;
                long dx = px - spawnX, dz = pz - spawnZ;
                if (dx * dx + dz * dz > r2) {
                    a[i + k * n] = Float.NaN;
                    continue;
                }
                // the hills, on a warped domain so they aren't lined up with the noise lattice
                float wx = px + WARP * 2 * (Noise.fbm32(px / WARP_SCALE, 0, pz / WARP_SCALE, seed ^ 0x5E1) - 0.5f);
                float wz = pz + WARP * 2 * (Noise.fbm32(px / WARP_SCALE, 7.3f, pz / WARP_SCALE, seed ^ 0x5E2) - 0.5f);
                float hill = Noise.fbm32(wx / HILL_SCALE, 2.9f, wz / HILL_SCALE, seed ^ 0x5E4);
                float detail = DETAIL * 2 * (Noise.value32(px / DETAIL_SCALE, 3.1f, pz / DETAIL_SCALE, seed ^ 0x5E3) - 0.5f);
                // distance to the nearest rift's edge (within reach)
                int bx = Math.floorDiv(px, StaticDeeps.CX) - b0x, bz = Math.floorDiv(pz, StaticDeeps.CZ) - b0z;
                float near = RIFT_REACH;
                for (int u = Math.max(0, bx - 1); u <= Math.min(bw - 1, bx + 1); u++)
                    for (int v = Math.max(0, bz - 1); v <= Math.min(bh - 1, bz + 1); v++)
                        for (float[] d : buckets[u + v * bw]) {
                            float ex = px - d[0], ez = pz - d[1];
                            float dist = (float) Math.sqrt(ex * ex + ez * ez) - d[2];
                            if (dist < near) near = dist;
                        }
                float f = 1 - Math.max(0, near) / RIFT_REACH;
                float dip = RIFT_DIP * f * f;
                float raise = SPAWN_RAISE * (float) Math.exp(-(dx * dx + dz * dz) / sig2);
                a[i + k * n] = hill + detail - dip + raise;
            }
        return a;
    }

    /** Builds the map with the default split (pure: the same arguments give the same ranks). */
    public static SpreadMap build(int seed, int spawnX, int spawnZ, int radius) {
        return build(seed, spawnX, spawnZ, radius, CONNECTED_UNTIL);
    }

    /**
     * Builds the map: ranks below floor(connectedUntil * N) by the flood from spawn, the rest by S (the class javadoc).
     * Pure: the same arguments give the same ranks.
     */
    public static SpreadMap build(int seed, int spawnX, int spawnZ, int radius, double connectedUntil) {
        int half = halfCells(radius), n = 2 * half + 1;
        int cellX0 = Math.floorDiv(spawnX, CELL) - half, cellZ0 = Math.floorDiv(spawnZ, CELL) - half;
        float[] a = stability(seed, spawnX, spawnZ, radius, cellX0, cellZ0, n);
        int total = 0;
        for (float v : a) if (!Float.isNaN(v)) total++;
        int split = (int) Math.floor(Math.max(0, Math.min(1, connectedUntil)) * total);
        int[] ranks = new int[n * n];
        java.util.Arrays.fill(ranks, -1);
        // the high cells: the total - split greatest keys (S, then the lower index), in ascending order
        long[] keys = new long[total];
        int t = 0;
        for (int c = 0; c < a.length; c++) if (!Float.isNaN(a[c])) keys[t++] = key(a[c], c);
        java.util.Arrays.sort(keys);
        boolean[] high = new boolean[n * n];
        for (int j = split; j < total; j++) {
            int c = Integer.MAX_VALUE - (int) keys[j];
            ranks[c] = j;
            high[c] = true;
        }
        // the low cells: the flood from the spawn cell over all cells (max-heap of the same unique keys: deterministic)
        boolean[] queued = new boolean[n * n];
        LongHeapPriorityQueue heap = new LongHeapPriorityQueue(LongComparators.OPPOSITE_COMPARATOR);
        int start = (Math.floorDiv(spawnX, CELL) - cellX0) + (Math.floorDiv(spawnZ, CELL) - cellZ0) * n;
        queued[start] = true;
        heap.enqueue(key(a[start], start));
        int step = 0, low = 0;
        while (!heap.isEmpty()) {
            int c = Integer.MAX_VALUE - (int) heap.dequeueLong();
            step++;
            if (!high[c]) ranks[c] = split - 1 - low++;
            int i = c % n, k = c / n;
            if (i > 0) push(heap, a, queued, c - 1);
            if (i < n - 1) push(heap, a, queued, c + 1);
            if (k > 0) push(heap, a, queued, c - n);
            if (k < n - 1) push(heap, a, queued, c + n);
        }
        if (step != total || low != split) throw new IllegalStateException("spread map: flooded " + step + " of " + total + " cells, " + low + " of " + split + " low");
        return new SpreadMap(spawnX, spawnZ, radius, ranks);
    }

    private static void push(LongHeapPriorityQueue heap, float[] a, boolean[] queued, int c) {
        if (queued[c] || Float.isNaN(a[c])) return;
        queued[c] = true;
        heap.enqueue(key(a[c], c));
    }

    /**
     * A unique sort key: S exactly (its bits made to sort as signed ints: negative floats' magnitude bits flipped; + 0f
     * turns -0 into 0), then the lower index.
     */
    private static long key(float s, int c) {
        int b = Float.floatToIntBits(s + 0f);
        return (long) (b >= 0 ? b : b ^ 0x7FFFFFFF) << 32 | (Integer.MAX_VALUE - c);
    }

    /**
     * The stability compass's target: the centre of the nearest cell (by distance from block (x, z) to its centre) whose
     * rank is at least minRank, searched ring by ring out from (x, z)'s cell; null if there is none. A cell centre's
     * rank1024 is exactly its rank, so the target's stability is at least minRank / N.
     */
    public static int[] nearestAtLeast(SpreadRanks s, int x, int z, int minRank, int maxRings) {
        int ci = Math.floorDiv(x, CELL), ck = Math.floorDiv(z, CELL);
        long best = Long.MAX_VALUE;
        int[] out = null;
        for (int ring = 0; ring <= maxRings; ring++) {
            // a cell of this ring is at least ring * CELL - HALF blocks away along one axis (the block may be up to half a
            // cell off its own cell's centre): stop once that is beyond the best found
            long minD = Math.max(0, (long) ring * CELL - HALF);
            if (out != null && minD * minD > best) break;
            int side = Math.max(1, 8 * ring);
            for (int j = 0; j < side; j++) {
                // walk the ring's perimeter: four sides of 2 * ring cells each (ring 0: the cell itself)
                int di, dk;
                if (ring == 0) {
                    di = 0;
                    dk = 0;
                } else {
                    int sd = j / (2 * ring), o = j % (2 * ring) - ring;
                    di = sd == 0 ? o : sd == 1 ? ring : sd == 2 ? -o : -ring;
                    dk = sd == 0 ? -ring : sd == 1 ? o : sd == 2 ? ring : -o;
                }
                if (s.rank(ci + di, ck + dk) < minRank) continue;
                long px = (long) (ci + di) * CELL + HALF - x, pz = (long) (ck + dk) * CELL + HALF - z;
                long d = px * px + pz * pz;
                if (d < best) {
                    best = d;
                    out = new int[]{(ci + di) * CELL + HALF, (ck + dk) * CELL + HALF};
                }
            }
        }
        return out;
    }
}

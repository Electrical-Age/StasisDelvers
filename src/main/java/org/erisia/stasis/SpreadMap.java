package org.erisia.stasis;

import it.unimi.dsi.fastutil.longs.LongComparators;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;

import java.util.ArrayList;
import java.util.List;

/**
 * The spreading mode's map (Baughn 2026-10-19; notes/timestop/spread-DESIGN.md): for each 32x32-block cell of the
 * overworld within a radius of world spawn, its RANK: the order in which the stillness reaches it (0 first, N-1 last:
 * the spawn cell). At progress P (Spread: floor(coverage * N)) a cell is stilled iff its rank is below P, and the cells
 * still running are always one 4-connected region containing spawn: nobody is ever shut into a running pocket.
 * <p>
 * Built once per world (a pure function of the deep seed, the spawn pinned at build time and the radius), then saved:
 * <ol>
 * <li>A0 per cell: the distance to the nearest static deep's rift (where the stillness starts), measured from a point
 * displaced by fbm noise so the fronts come in lobes, not circles; plus a gentle slope up towards spawn and a little
 * noise of its own.</li>
 * <li>Ranks by region growing from the 2x2 cells round spawn (Argon's review: 4-connected, which makes the bilinear set
 * provably connected too): repeatedly take the frontier cell with the highest A0 (the one that should run longest).
 * Step k gets rank N-1-k, so the running set {rank >= P} is always the first N-P cells taken: connected, with spawn.
 * A basin that would be cut off stills from its far side towards its exit instead.</li>
 * </ol>
 * Within a cell the continuous rank is bilinear between the cell centres' ranks ({@link #rank1024}); bilinear has no
 * interior maxima, so no new pockets (SpreadMapTest checks the block-sampled set is connected). Cells outside the
 * radius are -1: stilled from the start (Baughn: decision 3).
 * <p>
 * Why 32-block cells, not chunk-sized 16 (Argon: keep a minimum corridor width): a corridor of running cells is at least
 * one cell wide while open, so 32 blocks rather than 16; a quarter of the cells (an 8 km radius is ~196k cells: an
 * 0.8 MB save, ~0.2 s to build, 4x smaller client tiles); and the lobes come from noise at hundreds of blocks anyway.
 * With the grid aligned to multiples of 32 and centres at 32i + 16, every chunk lies in one bilinear patch per axis, so
 * a chunk's least and greatest rank are exactly those at its four corner columns ({@link #chunkMin1024}).
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
    public static final int ALGORITHM = 1;

    // A0's shape (blocks): the distance to the nearest rift (capped), warped by fbm, plus a slope up towards spawn
    static final float DIST_CAP = 1100;
    static final float WARP = 160, WARP_SCALE = 700;
    static final float SLOPE = 0.1f;
    static final float WOBBLE = 60, WOBBLE_SCALE = 300;
    /**
     * A cone around spawn, steeper than the distance term (1 per block, a bit more with the warp): within it A0 falls
     * away from spawn, so the last land to still is a round patch around spawn, not a 32-block corridor that happens
     * to pass a spawn near a rift (seen in SpreadMapTest's pinch report before the cone).
     */
    static final float DOME_RADIUS = 600, DOME_SLOPE = 2.5f;

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

    /** Raw A0 per cell of an n x n grid starting at global cell (cellX0, cellZ0); NaN outside the disc. */
    static float[] rawMap(int seed, int spawnX, int spawnZ, int radius, int cellX0, int cellZ0, int n) {
        // the static deeps' rifts, projected to x/z and bucketed by their 512-block grid cell
        int b0x = Math.floorDiv(cellX0 * CELL, StaticDeeps.CX) - 3, b1x = Math.floorDiv((cellX0 + n) * CELL, StaticDeeps.CX) + 3;
        int b0z = Math.floorDiv(cellZ0 * CELL, StaticDeeps.CZ) - 3, b1z = Math.floorDiv((cellZ0 + n) * CELL, StaticDeeps.CZ) + 3;
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
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++) {
                int px = (cellX0 + i) * CELL + HALF, pz = (cellZ0 + k) * CELL + HALF;
                long dx = px - spawnX, dz = pz - spawnZ;
                if (dx * dx + dz * dz > r2) {
                    a[i + k * n] = Float.NaN;
                    continue;
                }
                // measured from a displaced point: the fronts bulge into lobes
                float qx = px + WARP * 2 * (Noise.fbm32(px / WARP_SCALE, 0, pz / WARP_SCALE, seed ^ 0x5E1) - 0.5f);
                float qz = pz + WARP * 2 * (Noise.fbm32(px / WARP_SCALE, 7.3f, pz / WARP_SCALE, seed ^ 0x5E2) - 0.5f);
                int bx = Math.floorDiv((int) Math.floor(qx), StaticDeeps.CX) - b0x, bz = Math.floorDiv((int) Math.floor(qz), StaticDeeps.CZ) - b0z;
                float best = DIST_CAP;
                for (int u = Math.max(0, bx - 2); u <= Math.min(bw - 1, bx + 2); u++)
                    for (int v = Math.max(0, bz - 2); v <= Math.min(bh - 1, bz + 2); v++)
                        for (float[] d : buckets[u + v * bw]) {
                            float ex = qx - d[0], ez = qz - d[1];
                            float dist = (float) Math.sqrt(ex * ex + ez * ez) - d[2];
                            if (dist < best) best = dist;
                        }
                float toSpawn = (float) Math.sqrt((double) (dx * dx + dz * dz));
                float wobble = WOBBLE * 2 * (Noise.fbm32(px / WOBBLE_SCALE, 3.1f, pz / WOBBLE_SCALE, seed ^ 0x5E3) - 0.5f);
                float dome = DOME_SLOPE * Math.max(0, DOME_RADIUS - toSpawn);
                a[i + k * n] = Math.max(0, best) + SLOPE * (radius - toSpawn) + wobble + dome;
            }
        return a;
    }

    /** Builds the map (pure: the same seed, spawn and radius give the same ranks). */
    public static SpreadMap build(int seed, int spawnX, int spawnZ, int radius) {
        int half = halfCells(radius), n = 2 * half + 1;
        int cellX0 = Math.floorDiv(spawnX, CELL) - half, cellZ0 = Math.floorDiv(spawnZ, CELL) - half;
        float[] a = rawMap(seed, spawnX, spawnZ, radius, cellX0, cellZ0, n);
        int total = 0;
        for (float v : a) if (!Float.isNaN(v)) total++;
        int[] ranks = new int[n * n];
        java.util.Arrays.fill(ranks, -1);
        boolean[] queued = new boolean[n * n];
        // max-heap of (A0, then the lower index on ties): unique keys, so the order is deterministic. A0 is shifted
        // positive, where a float's bits sort as its value.
        LongHeapPriorityQueue heap = new LongHeapPriorityQueue(LongComparators.OPPOSITE_COMPARATOR);
        // the four cell centres round the spawn block go first (N-1..N-4): the spawn column's bilinear rank is theirs,
        // so it runs until the last few cells, wherever in its cell spawn lies. They are raised by BOOST, and only the
        // highest is queued at first, so they still come in a 4-connected order (two diagonal ones first would leave
        // a saddle).
        int si = Math.floorDiv(spawnX - HALF, CELL) - cellX0, sk = Math.floorDiv(spawnZ - HALF, CELL) - cellZ0;
        int start = -1;
        for (int di = 0; di <= 1; di++)
            for (int dk = 0; dk <= 1; dk++) {
                int c = si + di + (sk + dk) * n;
                a[c] += BOOST;
                if (start < 0 || a[c] > a[start]) start = c;
            }
        queued[start] = true;
        heap.enqueue(key(a[start], start));
        int step = 0;
        while (!heap.isEmpty()) {
            int c = Integer.MAX_VALUE - (int) heap.dequeueLong();
            ranks[c] = total - 1 - step++;
            int i = c % n, k = c / n;
            if (i > 0) push(heap, a, queued, c - 1);
            if (i < n - 1) push(heap, a, queued, c + 1);
            if (k > 0) push(heap, a, queued, c - n);
            if (k < n - 1) push(heap, a, queued, c + n);
        }
        if (step != total) throw new IllegalStateException("spread map: flooded " + step + " of " + total + " cells");
        return new SpreadMap(spawnX, spawnZ, radius, ranks);
    }

    private static void push(LongHeapPriorityQueue heap, float[] a, boolean[] queued, int c) {
        if (queued[c] || Float.isNaN(a[c])) return;
        queued[c] = true;
        heap.enqueue(key(a[c], c));
    }

    /** Lifts the spawn patch above any A0 (A0 stays well under 10^4 blocks). */
    private static final float BOOST = 50_000f;

    private static long key(float a0, int c) {
        return (long) Float.floatToIntBits(a0 + 100_000f) << 32 | (Integer.MAX_VALUE - c);
    }
}

package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The End's rift as a plan (spreading mode step 5; Baughn: the End island explodes into a gigantic rift of the
 * "floating islands surrounding static" kind, with end stone instead of ores). Pure: a function of the End's deep
 * seed and its pillars (EndSpikeFeature's list), so the server can rebuild it after a restart and tests can check it.
 * <ul>
 * <li>The main island's columns within {@link #ISLAND_RADIUS} are cut into parts: one sector per pillar (the columns
 * outside the core nearest that pillar's direction), lifted when that pillar's crystal breaks (Baughn: the crystals
 * "are stabilizing the problem"), and the core (within about {@link #CORE_RADIUS}: the pillars, the fountain and the
 * fight), which goes at the dragon's death. Boundaries are noisy, so the island crumbles in jagged bites.</li>
 * <li>Floating end-stone islands (lumpy, flat-topped, tapering below; some with obsidian from the pillars and a dead
 * chorus flower) in a shell {@link #SHELL_MIN}..{@link #SHELL_MAX} round the rift's centre (StaticDeeps.endRift),
 * outside it and every other static deep, at most {@link #BLOCK_BUDGET} blocks in all. Each island belongs to a part:
 * the pillar sector it lies over, or the core's if it would overlap a column still standing when that sector goes.</li>
 * </ul>
 */
public final class EndRiftPlan {
    /** Columns nearer the centre than this (give or take {@link #CORE_WOBBLE}) are the core: the pillars stand at 42 (up
     *  to 5 wide), the fight is there; it stays until the dragon dies (Baughn). */
    public static final int CORE_RADIUS = 60, CORE_WOBBLE = 6;
    /** The main island's columns (it ends at about 100; the End's wall, EndBarrier, stands at 140). */
    public static final int ISLAND_RADIUS = 150;
    /** The main island and its pillars lie below this (an island over a standing column above it can't collide). */
    public static final int ISLAND_TOP = 110;
    /** The islands' shell round the rift's centre (the rift's outer zone ends by about 1.3 x 40 + 8). */
    public static final int SHELL_MIN = 62, SHELL_MAX = 160;
    /** All the islands' blocks together. */
    public static final int BLOCK_BUDGET = 60_000;
    static final int MAX_ISLANDS = 90, MIN_Y = 4, MAX_Y = 240;

    public enum Kind { END_STONE, OBSIDIAN, CHORUS }

    /** An island: centre, radii (east-west, up, north-south), noise salt, what it carries, the part it belongs to. */
    public record Island(int x, int y, int z, float rx, float ry, float rz, int salt, boolean obsidian, boolean chorus, int part) {
    }

    public final int seed;
    /** Parts: 0..spikes-1 the pillar sectors, {@link #core} the core (the last). */
    public final int parts, core;
    public final List<Island> islands;
    private final double[] spikeAngles;
    /** Per part: the main island's columns (packed x, z as BlockPos at y 0), chunk by chunk from the outside in. */
    private final long[][] columns;
    /** Per part: the islands' blocks (BlockPos longs), chunk by chunk, bottom up, and what each is. */
    private final long[][] blocks;
    private final Kind[][] kinds;
    private final int total;
    /** Every island block, for the lifts to leave alone. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet all = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    private EndRiftPlan(int seed, List<int[]> spikes) {
        this.seed = seed;
        this.core = spikes.size();
        this.parts = core + 1;
        spikeAngles = new double[spikes.size()];
        for (int i = 0; i < spikes.size(); i++) spikeAngles[i] = Math.atan2(spikes.get(i)[1], spikes.get(i)[0]);
        // the main island's columns
        List<List<long[]>> cols = new ArrayList<>();
        for (int p = 0; p < parts; p++) cols.add(new ArrayList<>());
        for (int x = -ISLAND_RADIUS; x <= ISLAND_RADIUS; x++)
            for (int z = -ISLAND_RADIUS; z <= ISLAND_RADIUS; z++) {
                int p = partOf(x, z);
                if (p >= 0) cols.get(p).add(new long[]{BlockPos.asLong(x, 0, z), x, z});
            }
        columns = new long[parts][];
        for (int p = 0; p < parts; p++) {
            List<long[]> l = cols.get(p);
            // chunk by chunk, the farthest chunk first (outer rings go first: Baughn's players stand on the rest), then
            // within a chunk the farthest column first
            l.sort(Comparator.<long[]>comparingDouble(c -> -chunkDist((int) c[1] >> 4, (int) c[2] >> 4))
                .thenComparingLong(c -> ChunkPos.pack((int) c[1] >> 4, (int) c[2] >> 4))
                .thenComparingDouble(c -> -(c[1] * c[1] + c[2] * c[2])));
            columns[p] = l.stream().mapToLong(c -> c[0]).toArray();
        }
        // the islands
        List<Island> isl = new ArrayList<>();
        List<List<long[]>> bl = new ArrayList<>();
        for (int p = 0; p < parts; p++) bl.add(new ArrayList<>());
        Set<Long> taken = new HashSet<>();
        List<StaticDeeps.Deep> deeps = deeps(seed);
        int n = 0;
        for (int i = 0; i < 600 && isl.size() < MAX_ISLANDS && n < BLOCK_BUDGET - 200; i++) {
            Island is = candidate(i, seed);
            if (is == null) continue;
            List<long[]> bs = shape(is);
            List<long[]> kept = new ArrayList<>();
            for (long[] b : bs) {
                int x = (int) b[0], y = (int) b[1], z = (int) b[2];
                if (taken.contains(BlockPos.asLong(x, y, z)) || !inShell(x, y, z) || inDeep(deeps, x, y, z)) continue;
                kept.add(b);
            }
            if (kept.size() < 12 || n + kept.size() > BLOCK_BUDGET) continue;
            // its part: the sector it lies over, unless it would meet a column standing when that sector goes
            int part = sectorOf(is.x(), is.z());
            for (long[] b : kept) {
                int x = (int) b[0], y = (int) b[1], z = (int) b[2];
                if (y > ISLAND_TOP) continue;
                int cp = partOf(x, z);
                if (cp >= 0 && cp != part) {
                    part = core;
                    break;
                }
            }
            is = new Island(is.x(), is.y(), is.z(), is.rx(), is.ry(), is.rz(), is.salt(), is.obsidian(), is.chorus(), part);
            isl.add(is);
            for (long[] b : kept) {
                taken.add(BlockPos.asLong((int) b[0], (int) b[1], (int) b[2]));
                bl.get(part).add(b);
            }
            n += kept.size();
        }
        islands = List.copyOf(isl);
        total = n;
        blocks = new long[parts][];
        kinds = new Kind[parts][];
        for (int p = 0; p < parts; p++) {
            List<long[]> l = bl.get(p);
            l.sort(Comparator.<long[]>comparingLong(b -> ChunkPos.pack((int) b[0] >> 4, (int) b[2] >> 4)).thenComparingLong(b -> b[1])
                .thenComparingLong(b -> b[0]).thenComparingLong(b -> b[2]));
            blocks[p] = l.stream().mapToLong(b -> BlockPos.asLong((int) b[0], (int) b[1], (int) b[2])).toArray();
            for (long b : blocks[p]) all.add(b);
            kinds[p] = l.stream().map(b -> Kind.values()[(int) b[3]]).toArray(Kind[]::new);
        }
    }

    private static final java.util.Map<String, EndRiftPlan> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** The plan for this deep seed and these pillar centres ({x, z} each), cached. */
    public static EndRiftPlan of(int seed, List<int[]> spikes) {
        StringBuilder k = new StringBuilder().append(seed);
        for (int[] s : spikes) k.append(',').append(s[0]).append(':').append(s[1]);
        if (CACHE.size() > 4) CACHE.clear();
        return CACHE.computeIfAbsent(k.toString(), x -> new EndRiftPlan(seed, spikes));
    }

    /** Uncached (tests: determinism). */
    static EndRiftPlan build(int seed, List<int[]> spikes) {
        return new EndRiftPlan(seed, spikes);
    }

    /** Vanilla's ten pillars' centres (EndSpikeFeature: radius 42, evenly round), for tests. */
    public static List<int[]> vanillaSpikes() {
        List<int[]> l = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double a = 2 * (-Math.PI + Math.PI / 10 * i);
            l.add(new int[]{(int) Math.floor(42 * Math.cos(a)), (int) Math.floor(42 * Math.sin(a))});
        }
        return l;
    }

    private static double chunkDist(int cx, int cz) {
        double x = cx * 16 + 8, z = cz * 16 + 8;
        return Math.sqrt(x * x + z * z);
    }

    /** The part main-island column (x, z) belongs to: a pillar's sector, the core, or -1 (beyond the island). */
    public int partOf(int x, int z) {
        double r = Math.sqrt((double) x * x + (double) z * z);
        if (r > ISLAND_RADIUS) return -1;
        float w = Noise.value32(x / 20f, 0, z / 20f, seed ^ 0xE01) - 0.5f;
        if (r + 2 * CORE_WOBBLE * w < CORE_RADIUS) return core;
        return sectorOf(x, z);
    }

    /** The pillar sector of (x, z): the pillar nearest in direction, the edges wobbled by noise. */
    int sectorOf(int x, int z) {
        if (spikeAngles.length == 0) return core;
        double a = Math.atan2(z, x) + 0.45 * (Noise.value32(x / 28f, 3, z / 28f, seed ^ 0xE02) - 0.5f);
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < spikeAngles.length; i++) {
            double d = Math.abs(Math.IEEEremainder(a - spikeAngles[i], 2 * Math.PI));
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** Island candidate i: a place in the shell and a size, or null. */
    private static Island candidate(int i, int seed) {
        float u1 = Noise.hash32(i, 1, 0, seed ^ 0x1E1), u2 = Noise.hash32(i, 2, 0, seed ^ 0x1E1), u3 = Noise.hash32(i, 3, 0, seed ^ 0x1E1),
            u4 = Noise.hash32(i, 4, 0, seed ^ 0x1E1), u5 = Noise.hash32(i, 5, 0, seed ^ 0x1E1), u6 = Noise.hash32(i, 6, 0, seed ^ 0x1E1);
        // a few big ones first, then mostly small; nearer the core the bigger
        float rx = i < 6 ? 10 + 5 * u4 : 3 + 8 * (float) Math.pow(u4, 1.6);
        float rz = rx * (0.7f + 0.6f * u5), ry = 2 + rx * 0.3f;
        float reach = Math.max(rx, rz) * 1.35f + 1, reachDown = ry * 1.6f * 1.35f + 1;
        float lo = SHELL_MIN + reach, hi = SHELL_MAX - reach;
        if (hi <= lo) return null;
        double dist = lo + (hi - lo) * Math.pow(u1, 0.8);
        double th = 2 * Math.PI * u2, cph = (2 * u3 - 1) * 0.75;   // (fewer straight above and below: a flattened shell)
        double sph = Math.sqrt(1 - cph * cph);
        int x = (int) Math.round(dist * sph * Math.cos(th)), z = (int) Math.round(dist * sph * Math.sin(th));
        int y = (int) Math.round(StaticDeeps.END_RIFT_Y + dist * cph);
        if (y - reachDown < MIN_Y || y + ry + 2 > MAX_Y) return null;
        int salt = Noise.hashInt(i, 7, 0, seed ^ 0x1E2) & 0xFFFFFF;
        return new Island(x, y, z, rx, ry, rz, salt, u6 < 0.35f, u6 > 0.6f, -1);
    }

    /** Island is's blocks: {x, y, z, kind}. Flat-topped, tapering below, lumpy. */
    static List<long[]> shape(Island is) {
        List<long[]> out = new ArrayList<>();
        int ex = (int) Math.ceil(is.rx() * 1.35f), ez = (int) Math.ceil(is.rz() * 1.35f);
        int up = (int) Math.ceil(is.ry() * 0.6f * 1.35f), down = (int) Math.ceil(is.ry() * 1.6f * 1.35f);
        Set<Long> in = new HashSet<>();
        for (int dy = -down; dy <= up; dy++)
            for (int dx = -ex; dx <= ex; dx++)
                for (int dz = -ez; dz <= ez; dz++)
                    if (inside(is, dx, dy, dz)) in.add(BlockPos.asLong(is.x() + dx, is.y() + dy, is.z() + dz));
        for (long p : in) {
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            boolean top = !in.contains(BlockPos.asLong(x, y + 1, z));
            Kind k = Kind.END_STONE;
            if (is.obsidian() && Noise.hash32(x, y, z, is.salt() ^ 0x0B5) < 0.07f) k = Kind.OBSIDIAN;
            out.add(new long[]{x, y, z, k.ordinal()});
            if (top && is.chorus() && Noise.hash32(x, y, z, is.salt() ^ 0xC40) < 0.04f) out.add(new long[]{x, y + 1, z, Kind.CHORUS.ordinal()});
        }
        return out;
    }

    private static boolean inside(Island is, int dx, int dy, int dz) {
        int x = is.x() + dx, y = is.y() + dy, z = is.z() + dz;
        float lump = 1 + 0.3f * (Noise.value32(x / 4f, y / 4f, z / 4f, is.salt()) - 0.5f) * 2;
        float nx = dx / is.rx(), nz = dz / is.rz();
        float h2 = nx * nx + nz * nz;
        if (dy >= 0) {
            float ny = dy / (is.ry() * 0.6f);
            return h2 + ny * ny <= lump * lump;
        }
        float t = -dy / (is.ry() * 1.6f);   // 0 at the waist, 1 at the tip
        if (t > lump) return false;
        float allowed = (1 - t / lump);
        return h2 <= lump * lump * allowed * Math.sqrt(allowed);
    }

    static boolean inShell(int x, int y, int z) {
        double dx = x + 0.5 - 0.5, dy = y + 0.5 - (StaticDeeps.END_RIFT_Y + 0.5), dz = z + 0.5 - 0.5;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return d >= SHELL_MIN && d <= SHELL_MAX && y >= MIN_Y && y <= MAX_Y;
    }

    /** The End rift and the static deeps of the End's own grid that reach the shell (pure: StaticDeeps.cell). */
    static List<StaticDeeps.Deep> deeps(int seed) {
        List<StaticDeeps.Deep> l = new ArrayList<>();
        l.add(StaticDeeps.endRift(seed));
        for (int i = -1; i <= 0; i++)
            for (int j = -1; j <= 1; j++)
                for (int k = -1; k <= 0; k++) {
                    StaticDeeps.Deep d = StaticDeeps.cell(i, j, k, seed);
                    if (d != null) l.add(d);
                }
        return l;
    }

    /** Within some static deep (its zone, a little margin)? Island blocks there would be swallowed by the stillness. */
    static boolean inDeep(List<StaticDeeps.Deep> deeps, int x, int y, int z) {
        for (StaticDeeps.Deep d : deeps) {
            float dx = x - d.x(), dy = y - d.y(), dz = z - d.z();
            if (dx * dx + dy * dy + dz * dz > (d.radius() * 1.5f + 30) * (d.radius() * 1.5f + 30)) continue;
            if (StaticDeeps.shape(d, x + 0.5f, y + 0.5f, z + 0.5f) <= 1.05f) return true;
        }
        return false;
    }

    // ---- reading the plan

    /** Part p's main-island columns (BlockPos longs at y 0), chunk by chunk from the outside in. */
    public long[] columns(int p) {
        return columns[p];
    }

    /** Part p's island blocks (BlockPos longs), chunk by chunk, bottom up. */
    public long[] blocks(int p) {
        return blocks[p];
    }

    public Kind kind(int p, int i) {
        return kinds[p][i];
    }

    /**
     * Is this (a BlockPos long) one of the islands' blocks? A lift leaves it alone: an island placed already (over a
     * column lifted later, or the core's placed while a sector still lifts) stays, and island end stone where the old
     * island stood simply stays put.
     */
    public boolean isIsland(long pos) {
        return all.contains(pos);
    }

    /** All the islands' blocks. */
    public int totalBlocks() {
        return total;
    }

    /**
     * Where shard i of n lifted from part p flies to: one of p's island blocks, spread over them (deterministic); if p
     * has none, the core's, or any part's (a flying shard always has a target). -1 only if the plan has no islands.
     */
    public long target(int p, int i, int n) {
        for (int q : new int[]{p, core}) {
            long[] b = blocks[q];
            if (b.length > 0) return b[(int) ((long) (i % Math.max(1, n)) * b.length / Math.max(1, n)) % b.length];
        }
        for (long[] b : blocks) if (b.length > 0) return b[i % b.length];
        return -1;
    }

    /** Index of target(p, i, n) in blocks of the part it came from, and that part, for placing its real block. */
    public int[] targetIndex(int p, int i, int n) {
        for (int q : new int[]{p, core}) {
            long[] b = blocks[q];
            if (b.length > 0) return new int[]{q, (int) ((long) (i % Math.max(1, n)) * b.length / Math.max(1, n)) % b.length};
        }
        for (int q = 0; q < parts; q++) if (blocks[q].length > 0) return new int[]{q, i % blocks[q].length};
        return null;
    }

    @Override
    public String toString() {
        return "EndRiftPlan[seed " + seed + ", " + parts + " parts, " + islands.size() + " islands, " + total + " blocks, columns "
            + Arrays.stream(columns).mapToInt(c -> c.length).sum() + "]";
    }
}

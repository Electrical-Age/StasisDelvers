package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.erisia.stasis.towergen.Layout;
import org.erisia.stasis.towergen.Library;
import org.erisia.stasis.towergen.RefusedException;
import org.erisia.stasis.towergen.TowerGen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The generated tower of one core (P3d, notes/timestop/P3D-PLAN.md). The server reads the survey poles into a layout,
 * builds it with towergen off-thread once the poles have been left alone for a moment, and on success turns the
 * poles into the tower's ladder (mast up to the ladder top) or hides them (the rest: the generated tower is drawn in
 * their place). Clients get the layout and status through the block entity's sync and build the mesh themselves.
 * <p>
 * Tower coordinates: x = z = 0 on the core's centre, y = 0 on the ground (the core's bottom face; the core is
 * TowerBlock.HEIGHT = 2 high). Mast pole k (1-based above the core) spans y k+1..k+2, and H = 2 + the number of mast
 * poles; a boom on mast pole k is at y = k + 2 (the level you walk on).
 */
public final class TowerStructure {
    public enum Status { NONE, WAITING, BUILDING, OK, INVALID, REFUSED, FAILED }

    /** Ticks without pole changes before a build starts. */
    static final int DEBOUNCE = 40;
    public static final double Q = 1.0;

    private static final ExecutorService BUILD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stasis-towergen");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** One generator per build thread (TowerGen is not thread-safe). */
    private static TowerGen gen;
    /** GameTests: build on the server thread (the test server runs ticks far faster than a build takes). */
    public static volatile boolean syncBuild;

    public Layout layout;          // as scanned (null: no mast)
    public Status status = Status.NONE;
    public String message = "";
    public int ladderTop = -1;     // tower y of the ladder top (bottom ring of the transition bay), -1 none
    public int seed;
    private int quietTicks;
    private boolean dirty = true;
    private CompletableFuture<Outcome> pending;
    private Layout pendingLayout;
    /** Poles we changed from SURVEY at the last good build (to reset them when the tower changes). */
    private long[] parted = new long[0];
    /** The pole rules the parts were set by: 2 since the ladder reaches the drawn pole's socket. */
    static final int PARTS_VERSION = 2;
    private int partsVersion = PARTS_VERSION;

    record Outcome(Status status, String message, int ladderTop) {
    }

    /** Server tick. Returns true if the synced state changed (the block entity then sends an update). */
    boolean tick(ServerLevel level, BlockPos core, int every) {
        boolean changed = false;
        if (dirty || every % 20 == 0) {
            dirty = false;
            Layout l = scan(level, core, StasisConfig.towerMaxSegments);
            if (!java.util.Objects.equals(l, layout)) {
                layout = l;
                quietTicks = 0;
                status = l == null ? Status.NONE : Status.WAITING;
                message = "";
                if (l == null) changed |= resetParts(level, List.of());
                changed = true;
            }
        }
        // a tower built under older pole rules: build it again in the background and set its poles (it stays OK)
        if (status == Status.OK && partsVersion < PARTS_VERSION && layout != null && pending == null) {
            pendingLayout = layout;
            int s = seed;
            Layout pl = pendingLayout;
            pending = syncBuild ? CompletableFuture.completedFuture(build(pl, s)) : CompletableFuture.supplyAsync(() -> build(pl, s), BUILD);
        }
        if (status == Status.WAITING && ++quietTicks >= DEBOUNCE && pending == null) {
            List<String> v = layout.violations();
            if (!v.isEmpty()) {
                apply(level, core, new Outcome(Status.INVALID, String.join("; ", v), -1));
                return true;
            }
            status = Status.BUILDING;
            pendingLayout = layout;
            int s = seed;
            Layout pl = pendingLayout;
            pending = syncBuild ? CompletableFuture.completedFuture(build(pl, s)) : CompletableFuture.supplyAsync(() -> build(pl, s), BUILD);
            changed = true;
        }
        if (pending != null && pending.isDone()) {
            Outcome o = pending.join();
            Layout built = pendingLayout;
            pending = null;
            pendingLayout = null;
            if (built.equals(layout) && status == Status.BUILDING) {
                apply(level, core, o);
                changed = true;
            } else if (built.equals(layout) && status == Status.OK && partsVersion < PARTS_VERSION) {
                // (only the poles: a rebuild that no longer succeeds leaves the standing tower as it was)
                if (o.status() == Status.OK) apply(level, core, o);
                partsVersion = PARTS_VERSION;
                changed = true;
            }
        }
        return changed;
    }

    static synchronized Outcome build(Layout layout, int seed) {
        try {
            if (gen == null) gen = new TowerGen(Library.shared());
            gen.seed = seed;
            TowerGen.Result r = gen.build(layout, Q);
            if (r.refusal != null) return new Outcome(Status.REFUSED, r.refusal.message(), -1);
            return new Outcome(Status.OK, "", ladderTop(r));
        } catch (RefusedException e) {
            return new Outcome(Status.REFUSED, e.refusal.message(), -1);
        } catch (RuntimeException e) {
            StasisDelvers.LOG.warn("Tower build failed for {}", layout, e);
            return new Outcome(Status.FAILED, String.valueOf(e), -1);
        }
    }

    /**
     * The ladder well's top: the bottom ring of the transition bay (where the legs meet), or higher, up to the socket
     * where the drawn pole starts. Between the two nothing is drawn in the mast column, so poles there are a ladder, not
     * hidden (Baughn: a small tower's mast pole just above the core was invisible; its transition bay starts at the
     * ground).
     */
    static int ladderTop(TowerGen.Result r) {
        int[] ys = r.spine.ys;
        double[] s = r.spine.s;
        int top = -1;
        for (int i = 0; i + 1 < ys.length && top < 0; i++) if (s[i] > 0 && s[i + 1] <= 0) top = ys[i];
        // a lattice up to the top (capped): the well runs all the way
        if (top < 0) top = s.length > 0 && s[s.length - 1] > 0 ? ys[ys.length - 1] : -1;
        return Math.max(top, socket(r.tower));
    }

    /** The tower y where the drawn pole starts (its lowest node, rounded up), 0 if it stands on the ground or has none. */
    static int socket(org.erisia.stasis.towergen.Stitch.Tower t) {
        if (t == null || t.poleChain == null) return 0;
        double low = Double.MAX_VALUE;
        for (int n : t.poleChain) if (n != t.ghost) low = Math.min(low, t.P[3 * n + 1]);
        return low == Double.MAX_VALUE ? 0 : (int) Math.ceil(low - 1e-6);
    }

    private void apply(Level level, BlockPos core, Outcome o) {
        status = o.status();
        message = o.message();
        ladderTop = o.ladderTop();
        partsVersion = PARTS_VERSION;
        List<long[]> parts = new ArrayList<>();      // pos, part
        if (status == Status.OK && layout != null) {
            for (int k = 1; k <= layout.H() - TowerBlock.HEIGHT; k++)
                parts.add(new long[]{pole(core, k).asLong(), (k + TowerBlock.HEIGHT <= ladderTop ? TowerPoleBlock.Part.LADDER : TowerPoleBlock.Part.HIDDEN).ordinal()});
            for (Layout.Boom b : layout.booms()) {
                Direction d = dir(b.dir());
                BlockPos root = pole(core, b.y() - TowerBlock.HEIGHT);
                for (int i = 1; i <= b.length(); i++) parts.add(new long[]{root.relative(d, i).asLong(), TowerPoleBlock.Part.HIDDEN.ordinal()});
            }
        }
        resetParts(level, parts);
    }

    /** Sets the given poles' parts and resets the poles of the previous build that aren't in the list. */
    private boolean resetParts(Level level, List<long[]> parts) {
        java.util.Set<Long> now = new java.util.HashSet<>();
        for (long[] p : parts) {
            now.add(p[0]);
            setPart(level, BlockPos.of(p[0]), TowerPoleBlock.Part.values()[(int) p[1]]);
        }
        for (long p : parted) if (!now.contains(p)) setPart(level, BlockPos.of(p), TowerPoleBlock.Part.SURVEY);
        parted = now.stream().mapToLong(Long::longValue).toArray();
        return true;
    }

    private static void setPart(Level level, BlockPos p, TowerPoleBlock.Part part) {
        BlockState s = level.getBlockState(p);
        if (s.is(StasisRegistry.TOWER_SEGMENT.get()) && s.getValue(TowerPoleBlock.PART) != part)
            level.setBlock(p, s.setValue(TowerPoleBlock.PART, part), Block.UPDATE_CLIENTS);
    }

    /** Forget the tower (the core fell apart); it is read again if the core forms anew. */
    void reset() {
        layout = null;
        status = Status.NONE;
        message = "";
        ladderTop = -1;
        dirty = true;
    }

    /** Before the core goes: every pole back to a survey pole. */
    void release(Level level) {
        resetParts(level, List.of());
    }

    static Direction dir(int d) {
        return switch (d) {
            case 0 -> Direction.EAST;
            case 1 -> Direction.SOUTH;
            case 2 -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    /** The layout of the poles on this core: the vertical run above it, and horizontal runs off each mast pole. */
    public static Layout scan(Level level, BlockPos core, int maxH) {
        int H = 0;
        while (H < maxH && TowerPoleBlock.isMast(level.getBlockState(pole(core, H + 1)))) H++;
        if (H == 0) return null;
        List<Layout.Boom> booms = new ArrayList<>();
        for (int k = 0; k <= H; k++) {      // k 0: the core's top block (booms may start there, Baughn)
            BlockPos m = pole(core, k);
            for (int d = 0; d < 4; d++) {
                Direction dir = dir(d);
                int n = 0;
                while (n < 128) {
                    BlockState s = level.getBlockState(m.relative(dir, n + 1));
                    if (!(s.is(StasisRegistry.TOWER_SEGMENT.get()) && s.getValue(TowerPoleBlock.AXIS) == dir.getAxis())) break;
                    n++;
                }
                if (n > 0) booms.add(new Layout.Boom(k + TowerBlock.HEIGHT, d, n));
            }
        }
        return new Layout(H + TowerBlock.HEIGHT, booms);
    }

    /**
     * Would a boom pole placed at pos (extending its boom in direction dir) break the tower's rules? The reason, or null.
     * Refused at placement (Baughn: an invalid pole made the tower flicker). Rules (Layout.violations): a boom at y needs
     * 2 <= y <= H - 2, and its length at most H - y.
     */
    public static String boomRefusal(Level level, BlockPos pos, Direction dir) {
        BlockPos p = pos.relative(dir.getOpposite());
        int len = 1;
        while (len < 200) {
            BlockState s = level.getBlockState(p);
            if (!(s.is(StasisRegistry.TOWER_SEGMENT.get()) && s.getValue(TowerPoleBlock.AXIS) == dir.getAxis())) break;
            p = p.relative(dir.getOpposite());
            len++;
        }
        int below = 0, above = 0;
        BlockPos q;
        if (TowerBlock.isCoreTop(level.getBlockState(p))) {
            below = -1;                                                        // a boom off the core's top block: k 0
            q = p;
        } else {
            if (!TowerPoleBlock.isMast(level.getBlockState(p))) return null;  // not a boom on a mast: no tower rule
            q = p.below();
            while (below < 200 && TowerPoleBlock.isMast(level.getBlockState(q))) { below++; q = q.below(); }
            if (!level.getBlockState(q).is(StasisRegistry.TOWER.get())) return null;   // no core under the mast
        }
        q = p.above();
        while (above < 200 && TowerPoleBlock.isMast(level.getBlockState(q))) { above++; q = q.above(); }
        int k = below + 1, H = TowerBlock.HEIGHT + below + 1 + above, y = k + TowerBlock.HEIGHT;
        if (y > H - 2) return "A boom here needs at least 2 mast poles above it: raise the mast first.";
        if (len > H - y) return "Too long: a boom can be at most as long as the mast above it (" + (H - y) + " here).";
        return null;
    }

    /** Mast pole k (1-based) of the core whose master (bottom centre) is at core. */
    public static BlockPos pole(BlockPos core, int k) {
        return core.above(TowerBlock.HEIGHT - 1 + k);
    }

    void save(ValueOutput out) {
        if (layout != null) out.putString("layout", layout.toString());
        out.putString("tstatus", status.name());
        out.putString("tmsg", message);
        out.putInt("ladder", ladderTop);
        out.putInt("seed", seed);
        out.putInt("partsV", partsVersion);
        int[] xyz = new int[3 * parted.length];
        for (int i = 0; i < parted.length; i++) {
            BlockPos p = BlockPos.of(parted[i]);
            xyz[3 * i] = p.getX(); xyz[3 * i + 1] = p.getY(); xyz[3 * i + 2] = p.getZ();
        }
        out.putIntArray("parted", xyz);
    }

    void load(ValueInput in) {
        String l = in.getStringOr("layout", "");
        try {
            layout = l.isEmpty() ? null : Layout.fromJson(org.erisia.stasis.towergen.Json.parse(l));
        } catch (RuntimeException e) {
            layout = null;
        }
        try {
            status = Status.valueOf(in.getStringOr("tstatus", "NONE"));
        } catch (IllegalArgumentException e) {
            status = Status.NONE;
        }
        // a build in flight is lost with the save: build again
        if (status == Status.BUILDING) status = Status.WAITING;
        message = in.getStringOr("tmsg", "");
        ladderTop = in.getIntOr("ladder", -1);
        seed = in.getIntOr("seed", 0);
        partsVersion = in.getIntOr("partsV", 1);
        int[] xyz = in.getIntArray("parted").orElse(new int[0]);
        parted = new long[xyz.length / 3];
        for (int i = 0; i < parted.length; i++) parted[i] = BlockPos.asLong(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]);
    }
}

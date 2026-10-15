package org.erisia.stasis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The strata around a static deep's rift (notes/rifts/STAGE1-PLAN.md): rock of the rift's own (riftstone), with
 * cavities where the strata weather and the rift crystals will live, and vents and roots that reach the surface.
 * Several shapes, so Baughn and λ-Nero can pick in game (a weighted mix per rift, config strataShapes).
 * <p>
 * Every shape is a pure function of the deep and a world position ({@link #at}): worldgen asks it about neighbours
 * in other chunks too, so chunk borders agree whatever order chunks generate in. Sizes scale with the deep's radius.
 */
public final class Strata {
    private Strata() {
    }

    /** What a position is: outside the strata, rock (riftstone), open cavity, open vent or root tube, a vein (bright
     * riftstone), or a spire (rock that also stands in the air above ground). */
    public static final int OUT = 0, BODY = 1, CAVITY = 2, VENT = 3, VEIN = 4, SPIRE = 5;

    public enum Shape {
        SADDLE, STAIRCASE, SPLASH, PODS;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Vertical and horizontal extent of a deep's strata (world coordinates). */
    public record Bounds(int minY, int maxY, double radius) {
    }

    /** The weighted shape list (config strataShapes, "name:weight"; set by StasisConfig). */
    static volatile Shape[] weightedShapes = Shape.values();
    static volatile float[] weights = {1, 1, 1, 1};

    public static void setWeights(List<? extends String> entries) {
        List<Shape> s = new ArrayList<>();
        List<Float> w = new ArrayList<>();
        for (String e : entries) {
            String[] kv = e.split(":");
            try {
                Shape sh = Shape.valueOf(kv[0].trim().toUpperCase(java.util.Locale.ROOT));
                float wt = kv.length > 1 ? Float.parseFloat(kv[1].trim()) : 1;
                if (wt > 0) {
                    s.add(sh);
                    w.add(wt);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (s.isEmpty()) return;
        float[] a = new float[w.size()];
        for (int i = 0; i < a.length; i++) a[i] = w.get(i);
        weightedShapes = s.toArray(new Shape[0]);
        weights = a;
    }

    /** The shape of a deep's strata: a hash of its id, picked by the configured weights. */
    public static Shape shapeOf(StaticDeeps.Deep d) {
        Shape[] s = weightedShapes;
        float[] w = weights;
        float total = 0;
        for (float x : w) total += x;
        float u = Noise.hash32(d.id(), 77, 0, 0x57A7A) * total;
        for (int i = 0; i < s.length; i++) {
            u -= w[i];
            if (u < 0) return s[i];
        }
        return s[s.length - 1];
    }

    /** How far roots and vents reach above the deep's centre (they open to the surface if it's lower). */
    static double ventTop(StaticDeeps.Deep d) {
        return d.y() + d.radius() + 80;
    }

    public static Bounds bounds(StaticDeeps.Deep d) {
        return bounds(shapeOf(d), d);
    }

    /** bounds() for a given shape (GameTests try every shape on one deep). */
    public static Bounds bounds(Shape shape, StaticDeeps.Deep d) {
        double R = d.radius(), cy = d.y();
        return switch (shape) {
            case SADDLE -> {
                // the sheet, and wherever the roots wander
                Roots r = roots(d);
                yield new Bounds((int) Math.floor(Math.min(cy - 1.6 * R, r.minY)) - 2, (int) Math.ceil(Math.max(ventTop(d), r.maxY)) + 2,
                    Math.max(2.2 * R * 1.08 + 2, r.maxR + 2));
            }
            case STAIRCASE -> new Bounds((int) Math.floor(cy - 1.5 * R - 20), (int) Math.ceil(ventTop(d)), 2.3 * R + 4);
            case SPLASH -> new Bounds((int) Math.floor(cy - 1.0 * R - 8), (int) Math.ceil(cy + 2.4 * R + 8), 1.7 * R + 14);
            case PODS -> new Bounds((int) Math.floor(cy - 1.4 * R - 4), (int) Math.ceil(ventTop(d)), 2.15 * R + 4);
        };
    }

    /** What the strata of deep d is at world block (x, y, z) (block centre). */
    public static int at(StaticDeeps.Deep d, int x, int y, int z) {
        return at(shapeOf(d), d, x, y, z);
    }

    /** at() for a given shape (GameTests). */
    public static int at(Shape shape, StaticDeeps.Deep d, int x, int y, int z) {
        double dx = x + 0.5 - d.x(), dz = z + 0.5 - d.z(), ly = y + 0.5 - d.y();
        double R = d.radius();
        // each deep turns its shape by its own angle
        double a = Noise.hash32(d.id(), 78, 0, 0x57A7A) * 2 * Math.PI, ca = Math.cos(a), sa = Math.sin(a);
        double lx = dx * ca + dz * sa, lz = -dx * sa + dz * ca;
        return switch (shape) {
            case SADDLE -> saddle(d, x, y, z, lx, ly, lz, R);
            case STAIRCASE -> staircase(d, x, y, z, lx, ly, lz, R);
            case SPLASH -> splash(d, x, y, z, lx, ly, lz, R);
            case PODS -> pods(d, x, y, z, lx, ly, lz, R);
        };
    }

    private static boolean noisy(StaticDeeps.Deep d, int x, int y, int z, float scale, float threshold, int salt) {
        return Noise.value32(x / scale, y / scale, z / scale, d.id() ^ salt) > threshold;
    }

    // ---- SADDLE (the "eye"): a hyperbolic-paraboloid sheet under the rift, radial veins on it, roots up and down
    // from its rim (about half the up roots reach the surface)

    private static int saddle(StaticDeeps.Deep d, int x, int y, int z, double lx, double ly, double lz, double R) {
        double rim = 2.2 * R, r = Math.hypot(lx, lz);
        int root = roots(d).at(x, y, z);
        if (root == VENT) return VENT;
        if (r < rim * 1.08) {
            double sheet = -0.8 * R + (lx * lx - lz * lz) / (7 * R), dd = ly - sheet;
            double T = (2.6 + 0.04 * R) * clamp(1.15 - (r / rim) * (r / rim), 0, 1);
            if (r < rim && Math.abs(dd) < T) {
                if (Math.abs(dd) < 0.45 * T && r > 0.3 * R && noisy(d, x, y, z, 9, 0.45f, 0x5AD1)) return CAVITY;
                return BODY;
            }
            // veins: 24 radial lines on the sheet's upper face, from near the pupil out across the rim
            if (dd >= T && dd < T + 1.6 && r > 0.4 * R) {
                double th = Math.atan2(lz, lx), step = 2 * Math.PI / 24;
                int i = (int) Math.floor(th / step + 0.5);
                double wob = 0.08 * Math.sin(r / 9 + i);
                double off = Math.abs(th - (i * step + wob));
                if (r * off < 1.3) return VEIN;
            }
        }
        return root;
    }

    // ---- STAIRCASE: the slab cut into slices along the fault axis, each shifted 6-12 blocks; vents up some faults

    private static int staircase(StaticDeeps.Deep d, int x, int y, int z, double lx, double ly, double lz, double R) {
        double r = Math.hypot(lx, lz);
        if (r > 2.3 * R) return OUT;
        double W = 0.45 * R;
        int k = (int) Math.floor(lz / W);
        double scale = clamp(R / 40, 0.5, 1.5);
        double shift = (6 + 6 * Noise.hash32(k, 1, d.id(), 0x57A1)) * scale * ((k & 1) == 0 ? 1 : -1);
        double plane = -0.6 * R + lx * Math.tan(Math.toRadians(18)) + shift, dd = ly - plane;
        double T = 4.0 + 0.04 * R;
        // a vent: a vertical shaft up a fault line from some slices, to well above the ground
        if (Noise.hash32(k, 7, d.id(), 0x57A1) < 0.5 && dd > -T && y + 0.5 < ventTop(d)) {
            double vx = 0.4 * R * (Noise.hash32(k, 9, d.id(), 0x57A1) * 2 - 1);
            double fz = lz - k * W;
            if (Math.hypot(lx - vx, fz - 1.5) < 1.7) return VENT;
            if (Math.hypot(lx - vx, fz - 1.5) < 2.8) return BODY;
        }
        if (Math.abs(dd) < T) {
            if (Math.abs(dd - 0.5) < 0.45 * T && noisy(d, x, y, z, 9, 0.42f, 0x57A2)) return CAVITY;
            return BODY;
        }
        return OUT;
    }

    // ---- SPLASH: a hollow saucer under the rift, its rim curling up into crown spires (some through the ground),
    // one rebound spire from the centre up to the rift's floor

    private static int splash(StaticDeeps.Deep d, int x, int y, int z, double lx, double ly, double lz, double R) {
        double r = Math.hypot(lx, lz), RB = 1.7 * R, yb0 = -0.95 * R;
        double T = 3.5 + 0.03 * R;
        if (r < RB) {
            double yb = yb0 + 0.22 * R * (r / RB) * (r / RB), dd = ly - yb;
            if (Math.abs(dd) < T) return Math.abs(dd) < 0.45 * T && r < RB - 3 ? CAVITY : BODY;
        }
        // the rebound spire
        double ytop = -0.35 * R / 1.4;
        if (ly >= yb0 && ly <= ytop) {
            double ts = (ly - yb0) / (ytop - yb0);
            if (r < (2.5 + 0.03 * R) * Math.pow(1 - ts, 0.7) + 0.6) return BODY;
        }
        // the crown: N spires around the rim, leaning out as they rise
        int N = 11;
        double th = Math.atan2(lz, lx), step = 2 * Math.PI / N;
        int i = Math.floorMod((int) Math.floor((th + 0.3) / step + 0.5), N);
        double ang = i * step - 0.3, yrim = yb0 + 0.22 * R;
        double top = yrim + (0.6 + 1.6 * Noise.hash32(i, 5, d.id(), 0x5B1)) * R;
        if (ly >= yrim - T && ly <= top) {
            double t = clamp((ly - yrim) / Math.max(top - yrim, 1), 0, 1);
            double ax = (RB + 6 * t) * Math.cos(ang), az = (RB + 6 * t) * Math.sin(ang);
            double sr = (4.5 + 0.05 * R) * Math.pow(1 - t, 0.9);
            if (Math.hypot(lx - ax, lz - az) < sr + 0.4) return SPIRE;
        }
        return OUT;
    }

    // ---- PODS: Chladni-like lens chambers (three interfering sine waves) in a flattened envelope under the rift,
    // with a few chimneys up from the envelope's top

    private static int pods(StaticDeeps.Deep d, int x, int y, int z, double lx, double ly, double lz, double R) {
        double r = Math.hypot(lx, lz), yc = -0.85 * R;
        double env = (r / (2.1 * R)) * (r / (2.1 * R)) + ((ly - yc) / (0.5 * R)) * ((ly - yc) / (0.5 * R));
        // chimneys: 4 shafts at hashed spots inside 1.4 R, from inside the envelope up to well above the ground
        for (int c = 0; c < 4; c++) {
            double ca = Noise.hash32(c, 3, d.id(), 0x90D) * 2 * Math.PI, cr = (0.5 + 0.9 * Noise.hash32(c, 4, d.id(), 0x90D)) * R;
            double cx = cr * Math.cos(ca), cz = cr * Math.sin(ca);
            double hd = Math.hypot(lx - cx, lz - cz);
            if (hd < 2.8 && ly > yc && y + 0.5 < ventTop(d)) return hd < 1.7 ? VENT : BODY;
        }
        if (env >= 1.05) return OUT;
        double lam = 13.0 + 0.1 * R, k = 2 * Math.PI / lam, a = 0.35, b = 1.1;
        double f = Math.sin(k * (lx * Math.cos(a) + lz * Math.sin(a))) + Math.sin(k * (lx * Math.cos(a + b) + lz * Math.sin(a + b)))
            + Math.sin(k * 1.6 * (ly + 0.25 * lx));
        double e = clamp(1 - env, 0, 1);
        if (env < 1 && f > 1.55 - 0.3 * e) return CAVITY;
        if (f > 0.95 - 0.3 * e) return BODY;
        return OUT;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    // ---- roots (SADDLE): branching random walks from the sheet's rim, made once per deep from its id and indexed
    // by chunk column, so a point query only tests the few segments near it

    record Seg(double ax, double ay, double az, double bx, double by, double bz, double r) {
    }

    static final class Roots {
        final Map<Long, List<Seg>> byColumn = new HashMap<>();
        /** Extent (world y, horizontal distance from the deep's centre) including the linings. */
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxR;
        double cx, cz;

        void add(Seg s) {
            double pad0 = s.r + 1.3;
            minY = Math.min(minY, Math.min(s.ay, s.by) - pad0);
            maxY = Math.max(maxY, Math.max(s.ay, s.by) + pad0);
            maxR = Math.max(maxR, Math.max(Math.hypot(s.ax - cx, s.az - cz), Math.hypot(s.bx - cx, s.bz - cz)) + pad0);
            double pad = s.r + 1.5;
            int x0 = (int) Math.floor((Math.min(s.ax, s.bx) - pad)) >> 4, x1 = (int) Math.floor((Math.max(s.ax, s.bx) + pad)) >> 4;
            int z0 = (int) Math.floor((Math.min(s.az, s.bz) - pad)) >> 4, z1 = (int) Math.floor((Math.max(s.az, s.bz) + pad)) >> 4;
            for (int cx = x0; cx <= x1; cx++)
                for (int cz = z0; cz <= z1; cz++) byColumn.computeIfAbsent((long) cx << 32 | (cz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(s);
        }

        /** VENT inside a root tube, BODY in its lining (one block of riftstone around it), OUT elsewhere. */
        int at(int x, int y, int z) {
            List<Seg> l = byColumn.get((long) (x >> 4) << 32 | ((z >> 4) & 0xFFFFFFFFL));
            if (l == null) return OUT;
            double px = x + 0.5, py = y + 0.5, pz = z + 0.5;
            int best = OUT;
            for (Seg s : l) {
                double vx = s.bx - s.ax, vy = s.by - s.ay, vz = s.bz - s.az, wx = px - s.ax, wy = py - s.ay, wz = pz - s.az;
                double vv = vx * vx + vy * vy + vz * vz, t = vv > 0 ? clamp((wx * vx + wy * vy + wz * vz) / vv, 0, 1) : 0;
                double ex = wx - t * vx, ey = wy - t * vy, ez = wz - t * vz, dist = Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (dist < s.r) return VENT;
                if (dist < s.r + 1.2) best = BODY;
            }
            return best;
        }
    }

    private static final Map<Integer, Roots> ROOTS = new ConcurrentHashMap<>();

    static Roots roots(StaticDeeps.Deep d) {
        Roots r = ROOTS.get(d.id());
        if (r != null) return r;
        if (ROOTS.size() > 256) ROOTS.clear();
        r = makeRoots(d);
        ROOTS.put(d.id(), r);
        return r;
    }

    private static Roots makeRoots(StaticDeeps.Deep d) {
        Roots out = new Roots();
        out.cx = d.x();
        out.cz = d.z();
        double R = d.radius(), rim = 2.2 * R;
        double a = Noise.hash32(d.id(), 78, 0, 0x57A7A) * 2 * Math.PI, ca = Math.cos(a), sa = Math.sin(a);
        Random rng = new Random(d.id() * 0x9E3779B97F4A7C15L ^ 0x2007L);
        int n = 24;
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * (i + rng.nextDouble() * 0.6 - 0.3) / n;
            double lx = rim * Math.cos(ang), lz = rim * Math.sin(ang), ly = -0.8 * R + (lx * lx - lz * lz) / (7 * R);
            boolean up = i % 2 == 0;
            boolean far = up && rng.nextDouble() < 0.5;   // about half the up roots run on until the vent top
            double[] p = {lx * ca - lz * sa + d.x(), ly + d.y(), lx * sa + lz * ca + d.z()};
            double[] dir = {Math.cos(ang) * ca - Math.sin(ang) * sa, up ? 1 : -1, Math.cos(ang) * sa + Math.sin(ang) * ca};
            walk(out, rng, d, p, dir, 2.0 + 0.01 * R, up, far, 0);
        }
        return out;
    }

    private static void walk(Roots out, Random rng, StaticDeeps.Deep d, double[] p, double[] dir, double r, boolean up, boolean far, int depth) {
        double top = far ? ventTop(d) : d.y() + rng.nextDouble() * d.radius() * 0.6;
        double bottom = d.y() - 0.8 * d.radius() - 45;
        normalize(dir);
        for (int step = 0; step < 220 && r > 0.6; step++) {
            double[] q = p.clone();
            for (int c = 0; c < 3; c++) dir[c] = dir[c] * 0.82 + rng.nextGaussian() * 0.18;
            dir[1] += (up ? 0.55 : -0.4) * 0.25;
            normalize(dir);
            for (int c = 0; c < 3; c++) p[c] += dir[c] * 1.5;
            out.add(new Seg(q[0], q[1], q[2], p[0], p[1], p[2], r));
            if (up && p[1] > top || !up && p[1] < bottom) return;
            r *= up ? 0.997 : 0.985;
            if (depth < 2 && rng.nextDouble() < 0.03) {
                double[] nd = {dir[0] + rng.nextGaussian() * 0.7, dir[1], dir[2] + rng.nextGaussian() * 0.7};
                walk(out, rng, d, p.clone(), nd, r * 0.7, up, far && rng.nextDouble() < 0.5, depth + 1);
            }
        }
    }

    private static void normalize(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l > 1e-9) for (int i = 0; i < 3; i++) v[i] /= l;
    }
}

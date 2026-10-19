package org.erisia.stasis.client;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.erisia.stasis.StasisNet;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightning from a tower's shield to its mast (Baughn: visual only, it hits the tower). The server sends from, to and
 * a seed (StasisNet.Bolt); every client builds the same branched bolt (a midpoint-displaced trunk with 2-3 branches),
 * and the post shader draws it (fill): bright for a few frames, then an afterglow.
 */
public final class BoltRenderer {
    private BoltRenderer() {
    }

    /** Seconds a bolt lasts (flash then afterglow). */
    static final double LIFE = 0.45;

    record Live(List<Vector3d[]> strokes, long born) {
    }

    static final List<Live> LIVE = new ArrayList<>();

    static void receive(StasisNet.Bolt b) {
        List<Vector3d[]> strokes = strokes(b);
        synchronized (LIVE) {
            LIVE.add(new Live(strokes, System.nanoTime()));
        }
    }

    /** The bolt's strokes (trunk first), the same on every client. */
    static List<Vector3d[]> strokes(StasisNet.Bolt b) {
        List<Vector3d[]> strokes = new ArrayList<>();
        RandomSource rnd = RandomSource.create(b.seed());
        Vector3d from = new Vector3d(b.fx(), b.fy(), b.fz()), to = new Vector3d(b.tx(), b.ty(), b.tz());
        Vector3d[] trunk = displace(from, to, 5, 0.22, rnd);
        strokes.add(trunk);
        int branches = 2 + rnd.nextInt(2);
        for (int i = 0; i < branches; i++) {
            Vector3d start = trunk[1 + rnd.nextInt(trunk.length / 2)];
            double len = from.distance(to) * (0.15 + 0.2 * rnd.nextDouble());
            Vector3d dir = new Vector3d(to).sub(from).normalize()
                .add(rnd.nextGaussian() * 0.8, rnd.nextGaussian() * 0.8, rnd.nextGaussian() * 0.8).normalize().mul(len);
            strokes.add(displace(start, new Vector3d(start).add(dir), 3, 0.3, rnd));
        }
        return strokes;
    }

    /** Midpoint displacement: 2^depth segments, sideways jitter of `rough` x the segment length. */
    static Vector3d[] displace(Vector3d a, Vector3d b, int depth, double rough, RandomSource rnd) {
        List<Vector3d> pts = new ArrayList<>(List.of(a, b));
        for (int d = 0; d < depth; d++) {
            List<Vector3d> next = new ArrayList<>();
            for (int i = 0; i < pts.size() - 1; i++) {
                Vector3d p = pts.get(i), q = pts.get(i + 1);
                double len = p.distance(q);
                Vector3d m = new Vector3d(p).add(q).mul(0.5)
                    .add(rnd.nextGaussian() * rough * len, rnd.nextGaussian() * rough * len, rnd.nextGaussian() * rough * len);
                next.add(p);
                next.add(m);
            }
            next.add(pts.getLast());
            pts = next;
        }
        return pts.toArray(new Vector3d[0]);
    }

    /**
     * The live bolts as screen-space polylines for the post shader (stasis.fsh draws them, after the desaturation, so
     * they keep their colour and are depth-tested against the scene): per point NDC x, y, depth (reversed Z), half
     * width (NDC), intensity; intensity 0 breaks the line. Returns the number of points written (at most max).
     */
    static int fill(float[] d, int base, int max, org.joml.Matrix4f viewProj, Vec3 cam) {
        long now = System.nanoTime();
        List<Live> live;
        synchronized (LIVE) {
            LIVE.removeIf(l -> (now - l.born()) / 1e9 > LIFE);
            live = new ArrayList<>(LIVE);
        }
        // live bolts first, then the frozen ones of tower remnants (held mid-strike, thinned to every other point)
        List<List<Vector3d[]>> bolts = new ArrayList<>();
        List<Float> alphas = new ArrayList<>();
        for (Live l : live) {
            double age = (now - l.born()) / 1e9;
            bolts.add(l.strokes());
            alphas.add(age < 0.12 ? (((int) (age * 60)) % 2 == 0 ? 1f : 0.6f) : (float) Math.max(0, 1 - (age - 0.12) / (LIFE - 0.12)) * 0.5f);
        }
        for (List<Vector3d[]> f : Remnants.bolts(cam, 160)) {
            bolts.add(f);
            alphas.add(0.8f);
        }
        int n = 0;
        org.joml.Vector4f v = new org.joml.Vector4f();
        for (int li = 0; li < bolts.size(); li++) {
            Live l = new Live(bolts.get(li), 0);
            float a = alphas.get(li);
            for (int s = 0; s < l.strokes().size(); s++) {
                float w = s == 0 ? 0.35f : 0.18f;
                for (Vector3d p : l.strokes().get(s)) {
                    if (n >= max - 1) return n;
                    v.set((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), 1f);
                    viewProj.transform(v);
                    int o = base + 5 * n++;
                    if (v.w <= 0.05f) {          // behind the camera: break the line
                        d[o + 4] = 0;
                        continue;
                    }
                    d[o] = v.x / v.w;
                    d[o + 1] = v.y / v.w;
                    d[o + 2] = v.z / v.w;
                    d[o + 3] = Math.max(0.0015f, w / v.w);
                    d[o + 4] = a;
                }
                int o = base + 5 * n++;
                d[o + 4] = 0;                    // end of stroke
            }
        }
        return n;
    }

    static void init() {
        StasisNet.boltSink = BoltRenderer::receive;
    }
}

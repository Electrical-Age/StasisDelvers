package org.erisia.stasis.towergen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Last-resort tier {@code patch} (Lookup): the closest plain entry by root pattern, with the missing boom root
 * nodes patched in. The q1 geometry is stretched to the actual rings (and bay height), then for every root of the
 * bay that the entry lacks:
 * <ul>
 *   <li>lattice bays: the leg segment spanning the root height is split at the root corner, and the new node is
 *   braced with minimum-section struts to the bracketing nodes of the two face-adjacent legs (so it is stiff in
 *   both faces of its corner; the two root nodes of one boom get a strut between them);</li>
 *   <li>pole bays: the pole segment spanning the root height is split (the pole bending springs hold the node).</li>
 * </ul>
 * The runtime pass then sizes the new struts. Only q1 geometry is used (the jittered low-q nodes are off the legs).
 */
final class Patch {
    private Patch() {}

    static final class Result {
        Library.Variant v;
        int[] pole;
        int[][] legs;
        int added;
    }

    private static final int[][] CORNER = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};

    /**
     * @param e     candidate entry
     * @param kk    rotation of the candidate onto the bay's canonical frame
     * @param s     the bay spec (actual rings, canonical roots)
     */
    static Result build(Library.Entry e, int kk, Spine.BaySpec s) {
        Library.Variant src = e.q1;
        int[] ek = e.key.k();
        double hE = ek[10], sbE = ek[0] / 4.0, stE = ek[1] / 4.0;
        List<double[]> nodes = new ArrayList<>();
        for (int i = 0; i < src.n(); i++) {
            double[] p = {src.nodes[3 * i], src.nodes[3 * i + 1], src.nodes[3 * i + 2]};
            nodes.add(Stitch.stretch(p, sbE, stE, hE, s.sb, s.st, s.h));
        }
        List<int[]> mem = new ArrayList<>();          // i, j, kind, ws, cs
        List<Double> pre = new ArrayList<>();
        for (int m = 0; m < src.m(); m++) { mem.add(new int[]{src.mi[m], src.mj[m], src.kind[m], src.ws[m], src.cs[m]}); pre.add(src.pre[m]); }
        List<Integer> pole = new ArrayList<>();
        for (int p : e.pole) pole.add(p);
        List<Double> pw = new ArrayList<>();
        for (double w : src.poleW) pw.add(w);
        List<List<Integer>> legs = new ArrayList<>();
        for (int[] ch : e.legs) { List<Integer> l = new ArrayList<>(); for (int x : ch) l.add(x); legs.add(l); }
        Result res = new Result();
        List<Integer> fresh = new ArrayList<>();
        for (int[] rt : Lookup.roots(s.ikey)) {
            int y = rt[0], dc = Math.floorMod(rt[1] - kk, 4);
            double sy = s.sb + (y / (double) s.h) * (s.st - s.sb);
            for (int sg : new int[]{1, -1}) {
                double[] p = V.rot(new double[]{sy, 0, sg * sy}, dc);
                p[1] = y;
                if (find(nodes, p) >= 0) continue;
                if (sy < 1e-9) {                                   // pole: split the pole segment
                    for (int q = 0; q + 1 < pole.size(); q++) {
                        int a = pole.get(q), b = pole.get(q + 1);
                        if (nodes.get(a)[1] < y - 1e-6 && nodes.get(b)[1] > y + 1e-6) {
                            int nn = nodes.size();
                            nodes.add(p);
                            split(mem, pre, a, b, nn);
                            pole.add(q + 1, nn);
                            pw.add(q + 1, Math.max(pw.get(Math.min(q, pw.size() - 1)), pw.get(Math.min(q + 1, pw.size() - 1))));
                            res.added++;
                            break;
                        }
                    }
                    continue;
                }
                int k = -1;
                for (int c = 0; c < 4; c++) if (Math.signum(p[0]) == CORNER[c][0] && Math.signum(p[2]) == CORNER[c][1]) k = c;
                if (k < 0 || k >= legs.size()) continue;
                List<Integer> ch = legs.get(k);
                for (int q = 0; q + 1 < ch.size(); q++) {
                    int a = ch.get(q), b = ch.get(q + 1);
                    if (nodes.get(a)[1] < y - 1e-6 && nodes.get(b)[1] > y + 1e-6) {
                        int nn = nodes.size();
                        nodes.add(p);
                        split(mem, pre, a, b, nn);
                        ch.add(q + 1, nn);
                        fresh.add(nn); fresh.add(k);
                        res.added++;
                        break;
                    }
                }
            }
        }
        // braces for the new leg nodes: to the bracketing nodes of both face-adjacent legs
        for (int f = 0; f < fresh.size(); f += 2) {
            int nn = fresh.get(f), k = fresh.get(f + 1);
            double y = nodes.get(nn)[1];
            for (int nb : new int[]{(k + 1) % 4, (k + 3) % 4}) {
                if (nb >= legs.size()) continue;
                List<Integer> ch = legs.get(nb);
                for (int q = 0; q < ch.size(); q++) {
                    int c = ch.get(q);
                    double yc = nodes.get(c)[1];
                    if (Math.abs(yc - y) < 1e-6) { brace(mem, pre, nn, c); break; }
                    if (q + 1 < ch.size() && yc < y && nodes.get(ch.get(q + 1))[1] > y) {
                        brace(mem, pre, nn, c);
                        brace(mem, pre, nn, ch.get(q + 1));
                        break;
                    }
                }
            }
        }
        Library.Variant v = new Library.Variant();
        v.nodes = new double[3 * nodes.size()];
        for (int i = 0; i < nodes.size(); i++) System.arraycopy(nodes.get(i), 0, v.nodes, 3 * i, 3);
        int m = mem.size();
        v.mi = new int[m]; v.mj = new int[m]; v.kind = new int[m]; v.ws = new int[m]; v.cs = new int[m]; v.pre = new double[m];
        for (int b = 0; b < m; b++) {
            int[] x = mem.get(b);
            v.mi[b] = x[0]; v.mj[b] = x[1]; v.kind[b] = x[2]; v.ws[b] = x[3]; v.cs[b] = x[4]; v.pre[b] = pre.get(b);
        }
        v.poleW = pw.stream().mapToDouble(Double::doubleValue).toArray();
        res.v = v;
        res.pole = pole.stream().mapToInt(Integer::intValue).toArray();
        res.legs = new int[legs.size()][];
        for (int i = 0; i < legs.size(); i++) res.legs[i] = legs.get(i).stream().mapToInt(Integer::intValue).toArray();
        return res;
    }

    private static int find(List<double[]> nodes, double[] p) {
        for (int i = 0; i < nodes.size(); i++) {
            double[] q = nodes.get(i);
            if (Math.abs(q[0] - p[0]) < 1e-3 && Math.abs(q[1] - p[1]) < 1e-3 && Math.abs(q[2] - p[2]) < 1e-3) return i;
        }
        return -1;
    }

    private static void split(List<int[]> mem, List<Double> pre, int a, int b, int nn) {
        for (int i = 0; i < mem.size(); i++) {
            int[] x = mem.get(i);
            if ((x[0] == a && x[1] == b) || (x[0] == b && x[1] == a)) {
                mem.set(i, new int[]{x[0], nn, x[2], x[3], x[4]});
                mem.add(new int[]{nn, x[1], x[2], x[3], x[4]});
                pre.add(pre.get(i));
                return;
            }
        }
        mem.add(new int[]{a, nn, 0, 0, 0}); pre.add(0.0);           // no member there: connect anyway
        mem.add(new int[]{nn, b, 0, 0, 0}); pre.add(0.0);
    }

    private static void brace(List<int[]> mem, List<Double> pre, int a, int b) {
        for (int[] x : mem) if ((x[0] == a && x[1] == b) || (x[0] == b && x[1] == a)) return;
        mem.add(new int[]{a, b, 0, 0, 0});
        pre.add(0.0);
    }

    static String describe(Result r) { return "patched " + r.added + " root nodes, " + Arrays.toString(r.pole); }
}

package org.erisia.stasis.towergen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Library lookup with the documented fallback order (p3acov.lookup_bay, NOTES-P3a section 6) plus a last-resort tier.
 *
 * <p>Bays: {@code seam_exact -> seam_near -> plain_exact -> plain_near -> plain_near_mid -> plain_super -> ring_near
 * -> ring_near_h -> patch -> error}.
 * <ul>
 *   <li>{@code seam_near}: a seam entry with the same own key and the nearest above key (same above rings, mid, h and
 *   root count; distance {@link #bayDist}).</li>
 *   <li>{@code plain_near}: same sb4, st4, mid, h and the same canonical root (y, d) pattern under one of the 4
 *   rotations (the rotation offset kk is added to the bay's r). {@code plain_near_mid}: mid may differ (+3).
 *   {@code plain_super}: root pattern a strict superset (+3 per extra root).</li>
 *   <li><b>Last resort</b> (not in the Python; for the ~0.5% of bays with 4-5 roots or an unseen ring signature):
 *   {@code ring_near}: any plain entry with the same bay height h and the same pole-ness at both rings
 *   (sb4 == 0 and st4 == 0 agree), whose root pattern equals or contains this bay's pattern under some rotation.
 *   Distance = bayDist + 3 per extra root + 3 |mid difference| + 4 (|sb4 difference| + |st4 difference|). The
 *   entry's nodes are then stretched radially from its ring half-widths to the actual ones (x, z scaled by
 *   s_actual(y) / s_entry(y), linear in y between the rings), so its ring corners and leg nodes land on the
 *   neighbouring bays and on the boom roots. {@code ring_near_h}: for bays without booms only, the same with any
 *   bay height (nodes also scaled in y by h / h_entry). {@code patch}: the closest entry by root pattern with the
 *   missing boom root nodes patched into its legs or pole and braced ({@link Patch}). The runtime pass then sizes
 *   the members. {@link NoEntryException} only if the library has no entry with the same pole-ness at all.</li>
 * </ul>
 * Booms: exact, else nearest by {@code 4 u(Lc_i) + 2 |rho_i| + |sr4| + |sa4|} with the same anchor type.
 */
public final class Lookup {
    public static final class NoEntryException extends RuntimeException {
        public NoEntryException(String m) { super(m); }
    }

    private final Library lib;

    public Lookup(Library lib) { this.lib = lib; }

    static int under(int n, int c, int w) { return w * (c < n ? 2 * (n - c) : (c - n)); }

    /** p3acov.boom_dist; null (Integer.MIN_VALUE) if the anchor types differ. */
    static int boomDist(int[] n, int no, int[] c, int co) {
        if ((n[no + 3] == 0) != (c[co + 3] == 0)) return Integer.MIN_VALUE;
        return under(n[no], c[co], 4) + 2 * Math.abs(n[no + 1] - c[co + 1]) + Math.abs(n[no + 2] - c[co + 2])
                + Math.abs(n[no + 3] - c[co + 3]);
    }

    public Library.Entry boom(int[] key, Spine.BoomInfo info) {
        Library.Entry e = lib.booms.get(new Library.Key(key));
        if (e != null) { info.tier = "exact"; return e; }
        Library.Entry best = null;
        int bestD = 0;
        for (Library.Entry c : lib.booms.values()) {
            int d = boomDist(key, 0, c.key.k(), 0);
            if (d == Integer.MIN_VALUE) continue;
            if (best == null || d < bestD || (d == bestD && Portable.compare(c.key.k(), best.key.k()) < 0)) { best = c; bestD = d; }
        }
        if (best == null) throw new NoEntryException("no boom entry for key " + Arrays.toString(key));
        info.tier = "near";
        return best;
    }

    /** Root rows (y, d, bk x4) of a bay key. */
    static List<int[]> roots(int[] k) {
        int nr = k[11];
        List<int[]> r = new ArrayList<>();
        for (int i = 0; i < nr; i++) r.add(Arrays.copyOfRange(k, 12 + 6 * i, 18 + 6 * i));
        return r;
    }

    private static Set<Long> pattern(List<int[]> roots) {
        Set<Long> s = new HashSet<>();
        for (int[] r : roots) s.add(((long) r[0] << 8) | r[1]);
        return s;
    }

    /** p3acov.bay_dist (candidate rotated by kk); Integer.MIN_VALUE where a root of n has no match (KeyError). */
    static int bayDist(int[] n, int[] c, int kk) {
        int d = 0;
        for (int i : new int[]{3, 4, 5, 6, 8}) d += under(n[i], c[i], 1);
        if (n[6] >= 0 && c[6] >= 0) {
            int a = Math.abs(Math.floorMod(c[7] + 2 * kk, 8) - n[7]);
            d += Math.min(a, 8 - a);
        } else if ((n[6] >= 0) != (c[6] >= 0)) d += 2;
        d += Math.abs(n[9] - c[9]);
        List<int[]> rc = Spine.rotRoots(roots(c), kk);
        for (int[] r : roots(n)) {
            int[] m = null;
            for (int[] x : rc) if (x[0] == r[0] && x[1] == r[1]) { m = x; break; }
            if (m == null) return Integer.MIN_VALUE;
            int bd = boomDist(r, 2, m, 2);
            d += bd == Integer.MIN_VALUE ? 6 : bd;
        }
        return d;
    }

    private record Cand(int d, int kk, int[] key, Library.Entry e) {
        boolean better(Cand o) {
            if (o == null) return true;
            if (d != o.d) return d < o.d;
            if (kk != o.kk) return kk < o.kk;
            return Portable.compare(key, o.key) < 0;
        }
    }

    /** p3acov.nearest_bay; mode 0 = same pattern, 1 = relax mid, 2 = superset + relax mid. */
    private Cand nearestBay(int[] n, int mode) {
        List<int[]> rn = roots(n);
        Set<Long> pn = pattern(rn);
        List<Library.Entry> cands = lib.plainBySig.get(Library.sig(n[0], n[1], n[10]));
        if (cands == null) return null;
        Cand best = null;
        for (Library.Entry e : cands) {
            int[] c = e.key.k();
            if (c[2] != n[2] && mode == 0) continue;
            List<int[]> rc = roots(c);
            for (int kk = 0; kk < 4; kk++) {
                Set<Long> pc = pattern(Spine.rotRoots(rc, kk));
                boolean ok = mode < 2 ? pc.equals(pn) : (pc.size() > pn.size() && pc.containsAll(pn));
                if (!ok) continue;
                int d = bayDist(n, c, kk) + (pc.size() - pn.size()) * 3 + 3 * Math.abs(c[2] - n[2]);
                Cand cd = new Cand(d, kk, c, e);
                if (cd.better(best)) best = cd;
            }
        }
        return best;
    }

    /** Last resort: other ring signatures (stretched), see the class doc. */
    private Cand ringNear(int[] n, boolean anyH) {
        List<int[]> rn = roots(n);
        Set<Long> pn = pattern(rn);
        if (anyH && !rn.isEmpty()) return null;
        Cand best = null;
        for (Library.Entry e : lib.plain.values()) {
            int[] c = e.key.k();
            if ((c[10] != n[10]) != anyH) continue;
            if ((c[0] == 0) != (n[0] == 0) || (c[1] == 0) != (n[1] == 0)) continue;
            List<int[]> rc = roots(c);
            for (int kk = 0; kk < 4; kk++) {
                Set<Long> pc = pattern(Spine.rotRoots(rc, kk));
                if (!pc.containsAll(pn)) continue;
                int d = bayDist(n, c, kk);
                if (d == Integer.MIN_VALUE) continue;
                d += (pc.size() - pn.size()) * 3 + 3 * Math.abs(c[2] - n[2]) + 4 * (Math.abs(c[0] - n[0]) + Math.abs(c[1] - n[1]))
                        + 4 * Math.abs(c[10] - n[10]);
                Cand cd = new Cand(d, kk, c, e);
                if (cd.better(best)) best = cd;
                if (rc.isEmpty()) break;     // rotations of a root-less entry are equivalent
            }
        }
        return best;
    }

    /** Class part of bayDist (no roots). */
    static int headDist(int[] n, int[] c, int kk) {
        int d = 0;
        for (int i : new int[]{3, 4, 5, 6, 8}) d += under(n[i], c[i], 1);
        if (n[6] >= 0 && c[6] >= 0) {
            int a = Math.abs(Math.floorMod(c[7] + 2 * kk, 8) - n[7]);
            d += Math.min(a, 8 - a);
        } else if ((n[6] >= 0) != (c[6] >= 0)) d += 2;
        return d + Math.abs(n[9] - c[9]);
    }

    /**
     * Last tier 'patch': the plain entry closest by root pattern (same pole-ness at both rings); the missing root
     * nodes are patched into its legs / pole (see {@link Patch}). Score: 10 per missing root + 3 per extra root +
     * 4 per quarter block of ring difference + 3 for a mid difference + 20 per block of bay height difference
     * (then no root counts as matched) + the class distance.
     */
    private Cand patchCand(int[] n) {
        List<int[]> rn = roots(n);
        Cand best = null;
        for (Library.Entry e : lib.plain.values()) {
            int[] c = e.key.k();
            if ((c[0] == 0) != (n[0] == 0) || (c[1] == 0) != (n[1] == 0)) continue;
            List<int[]> rc = roots(c);
            for (int kk = 0; kk < 4; kk++) {
                List<int[]> rr = Spine.rotRoots(rc, kk);
                int covered = 0, bd = 0;
                if (c[10] == n[10]) {
                    for (int[] r : rn) {
                        for (int[] x : rr) {
                            if (x[0] == r[0] && x[1] == r[1]) {
                                covered++;
                                int b = boomDist(r, 2, x, 2);
                                bd += b == Integer.MIN_VALUE ? 6 : b;
                                break;
                            }
                        }
                    }
                }
                int d = 10 * (rn.size() - covered) + 3 * (rr.size() - covered) + 4 * (Math.abs(c[0] - n[0]) + Math.abs(c[1] - n[1]))
                        + 3 * Math.abs(c[2] - n[2]) + 20 * Math.abs(c[10] - n[10]) + headDist(n, c, kk) + bd;
                Cand cd = new Cand(d, kk, c, e);
                if (cd.better(best)) best = cd;
                if (rc.isEmpty()) break;
            }
        }
        return best;
    }

    /** Resolves one bay spec: sets spec.entry, spec.tier, spec.kk. */
    public void bay(Spine.BaySpec s, boolean seamMode) {
        int[] n = s.ikey;
        s.kk = 0;
        if (seamMode && s.aboveKey != null) {
            Library.Entry e = lib.seam.get(new Library.Key(s.seamKey));
            if (e != null) { s.entry = e; s.tier = "seam_exact"; return; }
            List<Library.Entry> own = lib.seamByOwn.get(new Library.Key(n));
            if (own != null && !own.isEmpty()) {
                int[] ab = s.aboveKey;
                Library.Entry bestE = null;
                int bestD = Integer.MAX_VALUE;
                for (Library.Entry c0 : own) {
                    int[] k = c0.key.k();
                    int[] c = Arrays.copyOfRange(k, n.length + 2, k.length);
                    int d = Integer.MAX_VALUE;
                    if (c[0] == ab[0] && c[1] == ab[1] && c[2] == ab[2] && c[10] == ab[10] && c[11] == ab[11]) {
                        int bd = bayDist(ab, c, 0);
                        if (bd != Integer.MIN_VALUE) d = bd;
                    }
                    if (bestE == null || d < bestD || (d == bestD && Portable.compare(k, bestE.key.k()) < 0)) {
                        bestD = d; bestE = c0;
                    }
                }
                if (bestD < Integer.MAX_VALUE) { s.entry = bestE; s.tier = "seam_near"; return; }
            }
        }
        Library.Entry e = lib.plain.get(new Library.Key(n));
        if (e != null) { s.entry = e; s.tier = "plain_exact"; return; }
        String[] tiers = {"plain_near", "plain_near_mid", "plain_super"};
        for (int mode = 0; mode < 3; mode++) {
            Cand c = nearestBay(n, mode);
            if (c != null) { s.entry = c.e; s.tier = tiers[mode]; s.kk = c.kk; return; }
        }
        Cand c = ringNear(n, false);
        if (c != null) { s.entry = c.e; s.tier = "ring_near"; s.kk = c.kk; return; }
        c = ringNear(n, true);
        if (c != null) { s.entry = c.e; s.tier = "ring_near_h"; s.kk = c.kk; return; }
        c = patchCand(n);
        if (c != null) {
            s.entry = c.e; s.tier = "patch"; s.kk = c.kk;
            s.patch = Patch.build(c.e, c.kk, s);
            return;
        }
        throw new NoEntryException("bay y " + s.y0 + ".." + s.y1 + " (key " + Arrays.toString(n)
                + ") has no compatible library entry: simplify the booms at this height");
    }
}

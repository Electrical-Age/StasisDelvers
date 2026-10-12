package org.erisia.stasis.towergen;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** p3a_goldens.json.gz (14 runs: 7 layouts x q 1 / 0.3) and the comparisons used by the golden tests. */
final class Goldens {
    static List<Map<String, Object>> load() throws IOException {
        try (InputStream in = Goldens.class.getResourceAsStream("/p3a_goldens.json.gz")) {
            Object o = Json.parse(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object g : Json.arr(o)) out.add(Json.obj(g));
            return out;
        }
    }

    static String name(Map<String, Object> g) { return g.get("name") + " q" + Json.num(g.get("q")); }

    static Layout layout(Map<String, Object> g) { return Layout.fromJson(g.get("layout")); }

    /** Stage-by-stage comparison of the stitched result; returns a list of mismatch messages (empty = match). */
    static List<String> compareStitch(Map<String, Object> g, TowerGen.Result r) {
        List<String> err = new ArrayList<>();
        Spine.Result sp = r.spine;
        // rings
        List<Object> rings = Json.arr(g.get("rings"));
        if (rings.size() != sp.ys.length) err.add("ring count " + sp.ys.length + " vs " + rings.size());
        else for (int i = 0; i < rings.size(); i++) {
            int[] yr = Json.iarr(rings.get(i));
            if (yr[0] != sp.ys[i] || yr[1] != Spine.q4(sp.s[i]))
                err.add("ring " + i + ": y " + sp.ys[i] + " s4 " + Spine.q4(sp.s[i]) + " vs " + Arrays.toString(yr));
        }
        if (!err.isEmpty()) return err;
        List<Object> bk = Json.arr(g.get("boom_keys"));
        for (int i = 0; i < bk.size(); i++) {
            int[] want = Json.iarr(bk.get(i));
            if (!Arrays.equals(want, sp.booms.get(i).ikey)) err.add("boom " + i + " key " + Arrays.toString(sp.booms.get(i).ikey) + " vs " + Arrays.toString(want));
            if (!sp.booms.get(i).tier.equals("exact")) err.add("boom " + i + " tier " + sp.booms.get(i).tier);
        }
        List<Object> bs = Json.arr(g.get("backstays"));
        for (int i = 0; i < bs.size(); i++) {
            Map<String, Object> b = Json.obj(bs.get(i));
            Spine.BoomInfo bo = sp.booms.get(i);
            int n = Json.inum(b.get("n"));
            if (n != bo.wires.size()) err.add("boom " + i + " back-stay wires " + bo.wires.size() + " vs " + n);
            double T = Json.num(b.get("T"));
            if (n > 0 && Math.abs(T - bo.bs.T) > 1e-3 * Math.max(1, T)) err.add("boom " + i + " back-stay T " + bo.bs.T + " vs " + T);
            if (n > 0 && Math.abs(Json.num(b.get("D")) - bo.bs.D) > 1e-2) err.add("boom " + i + " back-stay D " + bo.bs.D + " vs " + b.get("D"));
        }
        List<Object> keys = Json.arr(g.get("bay_keys"));
        int[] rot = Json.iarr(g.get("bay_rot"));
        if (keys.size() != sp.specs.size()) err.add("bay count " + sp.specs.size() + " vs " + keys.size());
        else for (int i = 0; i < keys.size(); i++) {
            Spine.BaySpec s = sp.specs.get(i);
            int[] want = Json.iarr(keys.get(i));
            if (!Arrays.equals(want, s.seamKey)) err.add("bay " + i + " (y" + s.y0 + ") key\n   got  " + Arrays.toString(s.seamKey) + "\n   want " + Arrays.toString(want));
            if (rot[i] != s.r) err.add("bay " + i + " rot " + s.r + " vs " + rot[i]);
            String wantTier = i + 1 < keys.size() ? "seam_exact" : "plain_exact";
            if (!wantTier.equals(s.tier)) err.add("bay " + i + " tier " + s.tier + " (want " + wantTier + ")");
        }
        if (!err.isEmpty()) return err;
        List<Object> vars = Json.arr(g.get("variants"));
        if (!vars.equals(new ArrayList<Object>(r.raw.variants))) err.add("variants " + r.raw.variants + " vs " + vars);
        // stitched topology
        Map<String, Object> st = Json.obj(g.get("stitched"));
        Stitch.Tower t = r.raw;
        List<Object> nodes = Json.arr(st.get("nodes"));
        if (nodes.size() != t.n) { err.add("node count " + t.n + " vs " + nodes.size()); return err; }
        for (int i = 0; i < t.n; i++) {
            double[] p = Json.darr(nodes.get(i));
            for (int a = 0; a < 3; a++)
                if (Math.abs(p[a] - t.P[3 * i + a]) > 2e-3) { err.add("node " + i + " " + Arrays.toString(t.node(i)) + " vs " + Arrays.toString(p)); break; }
            if (err.size() > 5) return err;
        }
        List<Object> mem = Json.arr(st.get("members"));
        if (mem.size() != t.m) { err.add("member count " + t.m + " vs " + mem.size()); return err; }
        for (int b = 0; b < t.m; b++) {
            double[] x = Json.darr(mem.get(b));
            if ((int) x[0] != t.bi[b] || (int) x[1] != t.bj[b] || (int) x[2] != t.kind[b] || (int) x[3] != t.ws[b] || (int) x[4] != t.cs[b]
                    || Math.abs(x[5] - t.floor[b]) > 2e-3 * Math.max(1, x[5]))
                err.add("member " + b + " [" + t.bi[b] + "," + t.bj[b] + "," + t.kind[b] + "," + t.ws[b] + "," + t.cs[b] + "," + t.floor[b] + "] vs " + Arrays.toString(x));
            if (err.size() > 5) return err;
        }
        if (!Arrays.equals(Json.iarr(st.get("pole")), t.poleChain)) err.add("pole chain differs");
        double[] pw = Json.darr(st.get("pole_w"));
        for (int i = 0; i < pw.length && i < t.pw.length; i++) if (Math.abs(pw[i] - t.pw[i]) > 1e-3) { err.add("pole_w " + i + " " + t.pw[i] + " vs " + pw[i]); break; }
        int[] fx = Json.iarr(st.get("fixed"));
        int nfx = 0;
        for (boolean f : t.fixed) if (f) nfx++;
        boolean fxOk = nfx == fx.length;
        for (int i : fx) fxOk &= t.fixed[i];
        if (!fxOk) err.add("fixed set differs");
        if (!Arrays.equals(Json.iarr(st.get("em")), t.em)) err.add("emitters " + Arrays.toString(t.em) + " vs " + st.get("em"));
        if (!Arrays.equals(Json.iarr(st.get("ground")), t.ground)) err.add("ground " + Arrays.toString(t.ground) + " vs " + st.get("ground"));
        List<Object> legs = Json.arr(st.get("legs"));
        if (legs.size() != t.legs.size()) err.add("legs " + t.legs.size() + " vs " + legs.size());
        else for (int i = 0; i < legs.size(); i++) if (!Arrays.equals(Json.iarr(legs.get(i)), t.legs.get(i))) { err.add("leg " + i + " differs"); break; }
        return err;
    }

    /** Ladder steps between two member sections (kind, ws, cs); 99 if the kinds are incompatible. */
    static int steps(int k1, int w1, int k2, int w2) {
        boolean s1 = k1 == 0 || k1 == 2, s2 = k2 == 0 || k2 == 2;
        if (s1 && s2) return Math.abs(w1 - w2);
        if (k1 == k2) return Math.abs(w1 - w2);
        return 99;
    }
}

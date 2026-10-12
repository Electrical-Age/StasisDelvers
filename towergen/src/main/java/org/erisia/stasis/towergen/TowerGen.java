package org.erisia.stasis.towergen;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime tower generator: spine statics -> integer keys -> library lookup (with fallbacks) -> stitch -> pretensioned
 * widening pass. {@code new TowerGen(Library.shared()).build(layout, q)}.
 */
public final class TowerGen {
    public final Library lib;
    public final Consts c;
    private final Spine spine;
    private final Lookup lookup;
    private final Stitch stitcher;
    private final RuntimePass pass;
    /** Seam keys (NOTES-P3a section 3: kept). */
    public boolean seamMode = true;
    public int seed = 1;

    public TowerGen(Library lib) {
        this.lib = lib;
        this.c = lib.c;
        this.spine = new Spine(c);
        this.lookup = new Lookup(lib);
        this.stitcher = new Stitch(c);
        this.pass = new RuntimePass(c);
    }

    public static final class Result {
        public Layout layout;
        public double q;
        public Spine.Result spine;
        public Stitch.Tower raw;          // as stitched
        public Stitch.Tower tower;        // after the runtime pass
        public RuntimePass.Analysis analysis;
        public RuntimePass.Info info = new RuntimePass.Info();
        public long tSpineNs, tLookupNs, tStitchNs;
        public List<String> bayTiers = new ArrayList<>(), boomTiers = new ArrayList<>();

        public double msStatics() { return (tSpineNs + tLookupNs + tStitchNs) / 1e6; }
        public double msPass() { return info.tPassNs / 1e6; }
    }

    /** Spine + lookup + stitch only (no runtime pass). */
    public Result stitchOnly(Layout layout, double q) {
        Result r = new Result();
        r.layout = layout; r.q = q;
        long t0 = System.nanoTime();
        r.spine = spine.run(layout, lookup::boom);
        long t1 = System.nanoTime();
        for (Spine.BaySpec s : r.spine.specs) { lookup.bay(s, seamMode); r.bayTiers.add(s.tier); }
        for (Spine.BoomInfo b : r.spine.booms) r.boomTiers.add(b.tier);
        long t2 = System.nanoTime();
        r.raw = stitcher.stitch(layout, r.spine, q, seed);
        long t3 = System.nanoTime();
        r.tSpineNs = t1 - t0; r.tLookupNs = t2 - t1; r.tStitchNs = t3 - t2;
        return r;
    }

    public Result build(Layout layout, double q) {
        Result r = stitchOnly(layout, q);
        RuntimePass.Analysis[] fa = new RuntimePass.Analysis[1];
        r.tower = pass.run(r.raw, r.info, fa);
        r.analysis = fa[0];
        return r;
    }

    public RuntimePass pass() { return pass; }
    public Sections sections() { return new Sections(c); }
}

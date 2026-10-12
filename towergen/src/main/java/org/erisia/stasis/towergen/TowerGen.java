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
        /** Effective constants of the runtime pass (use these for the mesh: the width ladder may be extended). */
        public Consts c;
        /** Solver parameters used (null = the plain generator). */
        public SolverParams solver;
        /** After the wire reduction (== raw if none). */
        public Stitch.Tower reduced;
        public WireReduction.Report reduction;
        public long tReduceNs;
        /** Solver warnings (wire reduction, stabilisation, fallbacks). */
        public List<String> warnings = new ArrayList<>();
        /** Non-null: the tower was built but the game must refuse it (over the member catalogue); the tower is kept
         *  for previews. Layouts refused before building throw {@link RefusedException} instead. */
        public Refusal refusal;

        public double msReduce() { return tReduceNs / 1e6; }

        public double msStatics() { return (tSpineNs + tLookupNs + tStitchNs) / 1e6; }
        public double msPass() { return info.tPassNs / 1e6; }
    }

    /** Spine + lookup + stitch only (no runtime pass). */
    public Result stitchOnly(Layout layout, double q) { return stitchOnly(layout, q, spine); }

    private Result stitchOnly(Layout layout, double q, Spine sp) {
        Result r = new Result();
        r.layout = layout; r.q = q; r.c = c;
        long t0 = System.nanoTime();
        r.spine = sp.run(layout, lookup::boom);
        long t1 = System.nanoTime();
        for (Spine.BaySpec s : r.spine.specs) { lookup.bay(s, seamMode); r.bayTiers.add(s.tier); }
        for (Spine.BoomInfo b : r.spine.booms) r.boomTiers.add(b.tier);
        long t2 = System.nanoTime();
        r.raw = stitcher.stitch(layout, r.spine, q, seed);
        long t3 = System.nanoTime();
        r.tSpineNs = t1 - t0; r.tLookupNs = t2 - t1; r.tStitchNs = t3 - t2;
        return r;
    }

    /** Throws {@link RefusedException} (STAY_RISE) if the layout's stays can't be anchored (see {@link #check}). */
    public Result build(Layout layout, double q) {
        Result r = stitchOnly(layout, q);
        RuntimePass.Analysis[] fa = new RuntimePass.Analysis[1];
        r.tower = pass.run(r.raw, r.info, fa);
        r.analysis = fa[0];
        r.reduced = r.raw;
        refuseOverCatalogue(r, c);
        return r;
    }

    /**
     * Refusals known before building (the stay rule, {@link Spine#anchorLevels}; cheap, no statics), one per refused
     * boom; empty = buildable as far as the layout goes (the built tower can still be refused: {@link Result#refusal}).
     * sp may be null (library defaults). Game rule violations ({@link Layout#violations}) are checked separately.
     */
    public List<Refusal> check(Layout layout, SolverParams sp) {
        List<Refusal> out = new ArrayList<>();
        Spine.anchorLevels(layout, sp == null ? c : c.forSpine(sp), out);
        return out;
    }

    private static void refuseOverCatalogue(Result r, Consts cp) {
        if (r.analysis != null && r.analysis.maxUtil > cp.UTIL_LIMIT + 1e-3)
            r.refusal = new Refusal(Refusal.Reason.OVER_CATALOGUE, String.format("over the member catalogue: max "
                    + "utilisation %.3f > %.3f (too tall for its booms: lower the tower or shorten the long booms)",
                    r.analysis.maxUtil, cp.UTIL_LIMIT));
    }

    /**
     * Build with solver parameters (null = {@link #build(Layout, double)}; the defaults give the same tower). Never
     * throws for a mechanism (throws {@link RefusedException} for a refused layout, as the plain build): falls back to the unreduced tower, else returns the stitched tower without an analysis,
     * with a warning in {@link Result#warnings}.
     */
    public Result build(Layout layout, double q, SolverParams sp) {
        if (sp == null) return build(layout, q);
        Consts cs = c.forSpine(sp), cp = c.forPass(sp);
        Result r = stitchOnly(layout, q, cs == c ? spine : new Spine(cs));
        RuntimePass ps = cp == c ? pass : new RuntimePass(cp);
        r.c = cp;
        r.solver = sp;
        if (!sp.reducesWires()) {
            r.reduced = r.raw;
            runPass(r, ps, r.raw);
        } else {
            // the full reduction, then (if the result does not pass) stays only, X panels only, none
            List<SolverParams> tries = new ArrayList<>();
            tries.add(sp);
            boolean stays = sp.maxStaysPerBoom() > 0, bays = !sp.xPanels().equals("keep") || sp.maxCablesPerBay() > 0;
            if (stays && bays) {
                tries.add(sp.withWires(sp.maxStaysPerBoom(), "keep", 0));
                tries.add(sp.withWires(0, sp.xPanels(), sp.maxCablesPerBay()));
            }
            long tPass = 0;
            boolean ok = false;
            for (int i = 0; i < tries.size() && !ok; i++) {
                Result a = new Result();
                long t0 = System.nanoTime();
                WireReduction wr = new WireReduction(r.raw, ps, tries.get(i));
                r.tReduceNs += System.nanoTime() - t0;
                a.reduced = wr.tower();
                runPass(a, ps, a.reduced);
                // over the limit: put back the removed members nearest the worst one and rerun
                for (int k = 0; k < MAX_UTIL_RETRIES && !wr.failed() && a.analysis != null
                        && a.analysis.maxUtil > cp.UTIL_LIMIT + 1e-3; k++) {
                    int worst = 0;
                    for (int b = 1; b < a.analysis.util.length; b++) if (a.analysis.util[b] > a.analysis.util[worst]) worst = b;
                    if (!wr.putBackNear(worst, PUT_BACK_BATCH)) break;
                    tPass += a.info.tPassNs;
                    a.info = new RuntimePass.Info();
                    a.reduced = wr.tower();
                    runPass(a, ps, a.reduced);
                }
                ok = a.analysis != null && a.analysis.maxUtil <= cp.UTIL_LIMIT + 1e-3;
                if (ok || i == tries.size() - 1 || r.reduction == null) {
                    tPass += a.info.tPassNs;
                    r.tower = a.tower; r.analysis = a.analysis; r.info = a.info; r.reduced = a.reduced;
                    r.reduction = wr.rep;
                    r.warnings.clear();
                    r.warnings.addAll(wr.rep.warnings);
                    r.warnings.addAll(a.warnings);
                    if (i > 0) r.warnings.add(0, "wire reduction: the full reduction did not pass; used "
                            + (tries.get(i).maxStaysPerBoom() > 0 ? "the stay reduction only" : "the bay (X panel) reduction only"));
                    if (wr.rep.staysReadded > 0)
                        r.warnings.add("maxStaysPerBoom: " + wr.rep.staysReadded + " of " + wr.rep.staysRemoved
                                + " removed stays put back (flat booms need about one stay per boom node to stand)");
                    if (wr.rep.utilReadded > 0)
                        r.warnings.add("wire reduction: " + wr.rep.utilReadded + " members put back to bring the utilisation under the limit");
                } else tPass += a.info.tPassNs;
            }
            if (!ok && r.reduced != r.raw) {
                Result u = new Result();
                runPass(u, ps, r.raw);
                tPass += u.info.tPassNs;
                if (u.analysis != null && u.analysis.maxUtil <= cp.UTIL_LIMIT + 1e-3) {
                    r.warnings.add(String.format("wire reduction: the reduced tower did not pass (util %s); using the unreduced tower",
                            r.analysis == null ? "mechanism" : String.format("%.2f", r.analysis.maxUtil)));
                    r.tower = u.tower; r.analysis = u.analysis; r.info = u.info; r.reduced = r.raw;
                    r.warnings.addAll(u.warnings);
                }
            }
            r.info.tPassNs = tPass;
        }
        refuseOverCatalogue(r, cp);
        return r;
    }

    /** Pass reruns after an over-limit result of a reduced tower, and members put back before each. */
    static final int MAX_UTIL_RETRIES = 2, PUT_BACK_BATCH = 4;

    /** The runtime pass; on a mechanism the stitched input is kept without an analysis and a warning is added. */
    private static void runPass(Result r, RuntimePass ps, Stitch.Tower in) {
        RuntimePass.Analysis[] fa = new RuntimePass.Analysis[1];
        try {
            r.tower = ps.run(in, r.info, fa);
            r.analysis = fa[0];
        } catch (RuntimePass.MechanismException e) {
            r.warnings.add("runtime pass: " + e.getMessage());
            r.tower = in;
            r.analysis = null;
        }
    }

    public RuntimePass pass() { return pass; }
    public Sections sections() { return new Sections(c); }
}

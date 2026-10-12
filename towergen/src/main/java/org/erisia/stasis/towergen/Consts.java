package org.erisia.stasis.towergen;

import java.util.List;
import java.util.Map;

/** Ladders, reference curve, constants and load cases from the library header (p3aexport.header()). */
public final class Consts {
    public final double[] CAB_W, CAB_A, BS_W, WIDTHS, AREAS, RHO_LADDER, S_LADDER;
    /** Strut / laced outer width ladder (SolverParams.extraWidthSteps may extend it). */
    public double[] WEXT;
    public final int[] L_LADDER, DEPTH_LADDER;
    public final double[] refD, refN, refV, refM;
    public final double BAY, S_CORE, W_LEG_TARGET, MID_F, M_POLE_MAX, POLE_A, W_POLE_MIN,
            SIG_CAB, SIG_T, SIG_C0, L0, E, Q_LOW, TAUT_PRE_MAX, LACE_W, CH_MAX, R_REF;
    /** Overridable by SolverParams (forSpine / forPass): loads, back-stay constants, pretension margin. */
    public double EMIT_W, EMIT_WIND0, RHO, CD0, BS_MIN_L, D_CAP, D_FRAC, BS_T_MIN, BS_CAP, PRE_MARGIN;
    /** Stay anchor rule (Spine.anchorLevel): cap mode (STAY_CAP_*), preferred rise (x L), refusal floor (x L, blocks).
     *  Library header constants STAY_CAP / STAY_RISE / STAY_MIN_RATIO / STAY_MIN_RISE; these defaults if absent. */
    public int STAY_CAP = STAY_CAP_SAME;
    public double STAY_RISE = 0.7, STAY_MIN_RATIO = 0.35, STAY_MIN_RISE = 2.0;
    /** Boom keys (Arrays.toString) whose library solve failed (header "boom_failed"): refused, never a fallback. */
    public java.util.Set<String> BOOM_FAILED = new java.util.HashSet<>();
    public static final int STAY_CAP_NONE = 0, STAY_CAP_SAME = 1, STAY_CAP_ANY = 2;
    public static final java.util.List<String> STAY_CAP_NAMES = java.util.List.of("none", "same", "any");
    /** Not in the header: fem.TORQ0 (torsion load at an emitter off the axis). */
    public double TORQ0 = 0.4;
    /** Runtime only (SolverParams): back-stays on, sizing utilisation limit, raise iterations, resize rounds. */
    public boolean BACKSTAYS = true;
    public double UTIL_LIMIT = 1.0;
    public int RAISE_ITS = 60, MAX_ROUNDS = 30;
    /** Ladder floors / ratios (N, V, Mg, T) and class tolerances. */
    public final double N_FLOOR, N_R, V_FLOOR, V_R, MG_FLOOR, MG_R, T_FLOOR, T_R, BELOW, CLASS_TOL, CEIL_EPS, DEMAND_EPS;
    /** Load cases: wind unit vector (null for gravity / torsion) and torsion sign. */
    public final double[][] caseWind;
    public final int[] caseTors;
    public final String[] caseName;
    public final int K;
    public final int I_SOLID_MAX;
    /** f = 1.5 (NOTES-P3a section 5). */
    public double FIXF = 1.5;

    /** Field-by-field copy (TeaVM-safe; no clone). */
    private Consts(Consts o) {
        WEXT = o.WEXT; CAB_W = o.CAB_W; CAB_A = o.CAB_A; BS_W = o.BS_W; WIDTHS = o.WIDTHS; AREAS = o.AREAS;
        RHO_LADDER = o.RHO_LADDER; S_LADDER = o.S_LADDER; L_LADDER = o.L_LADDER; DEPTH_LADDER = o.DEPTH_LADDER;
        refD = o.refD; refN = o.refN; refV = o.refV; refM = o.refM;
        BAY = o.BAY; S_CORE = o.S_CORE; W_LEG_TARGET = o.W_LEG_TARGET; MID_F = o.MID_F; M_POLE_MAX = o.M_POLE_MAX;
        POLE_A = o.POLE_A; W_POLE_MIN = o.W_POLE_MIN; SIG_CAB = o.SIG_CAB; SIG_T = o.SIG_T; SIG_C0 = o.SIG_C0; L0 = o.L0;
        E = o.E; Q_LOW = o.Q_LOW; TAUT_PRE_MAX = o.TAUT_PRE_MAX; LACE_W = o.LACE_W; CH_MAX = o.CH_MAX; R_REF = o.R_REF;
        EMIT_W = o.EMIT_W; EMIT_WIND0 = o.EMIT_WIND0; RHO = o.RHO; CD0 = o.CD0; BS_MIN_L = o.BS_MIN_L; D_CAP = o.D_CAP;
        D_FRAC = o.D_FRAC; BS_T_MIN = o.BS_T_MIN; BS_CAP = o.BS_CAP; PRE_MARGIN = o.PRE_MARGIN; TORQ0 = o.TORQ0;
        BACKSTAYS = o.BACKSTAYS; UTIL_LIMIT = o.UTIL_LIMIT; RAISE_ITS = o.RAISE_ITS; MAX_ROUNDS = o.MAX_ROUNDS;
        FIXF = o.FIXF;
        BOOM_FAILED = o.BOOM_FAILED;
        STAY_CAP = o.STAY_CAP; STAY_RISE = o.STAY_RISE; STAY_MIN_RATIO = o.STAY_MIN_RATIO; STAY_MIN_RISE = o.STAY_MIN_RISE;
        N_FLOOR = o.N_FLOOR; N_R = o.N_R; V_FLOOR = o.V_FLOOR; V_R = o.V_R; MG_FLOOR = o.MG_FLOOR; MG_R = o.MG_R;
        T_FLOOR = o.T_FLOOR; T_R = o.T_R; BELOW = o.BELOW; CLASS_TOL = o.CLASS_TOL; CEIL_EPS = o.CEIL_EPS;
        DEMAND_EPS = o.DEMAND_EPS; caseWind = o.caseWind; caseTors = o.caseTors; caseName = o.caseName; K = o.K;
        I_SOLID_MAX = o.I_SOLID_MAX;
    }

    /** Constants for the spine statics under p (back-stay keys only); this instance if nothing differs. */
    public Consts forSpine(SolverParams p) {
        if (p.backstays() == BACKSTAYS && p.backstayMinBoom() == BS_MIN_L && p.backstayDistFactor() == D_FRAC
                && p.backstayDistCap() == D_CAP && p.backstayMinTension() == BS_T_MIN && p.backstaySecondPair() == BS_CAP
                && STAY_CAP_NAMES.indexOf(p.stayCap()) == STAY_CAP && p.stayRise() == STAY_RISE
                && p.stayMinRatio() == STAY_MIN_RATIO && p.stayMinRise() == STAY_MIN_RISE)
            return this;
        Consts c = new Consts(this);
        c.STAY_CAP = STAY_CAP_NAMES.indexOf(p.stayCap()); c.STAY_RISE = p.stayRise();
        c.STAY_MIN_RATIO = p.stayMinRatio(); c.STAY_MIN_RISE = p.stayMinRise();
        c.BACKSTAYS = p.backstays(); c.BS_MIN_L = p.backstayMinBoom(); c.D_FRAC = p.backstayDistFactor();
        c.D_CAP = p.backstayDistCap(); c.BS_T_MIN = p.backstayMinTension(); c.BS_CAP = p.backstaySecondPair();
        return c;
    }

    /** Constants for the runtime pass and the mesh under p (loads, pretension, limits, ladder); this if unchanged. */
    public Consts forPass(SolverParams p) {
        if (p.pretensionFactor() == FIXF && p.pretensionMargin() == PRE_MARGIN && p.raiseIters() == RAISE_ITS
                && p.maxRounds() == MAX_ROUNDS && p.utilLimit() == UTIL_LIMIT && p.emitterWeight() == EMIT_W
                && p.emitterWind() == EMIT_WIND0 && p.windDrag() == CD0 && p.torsion() == TORQ0 && p.density() == RHO
                && p.extraWidthSteps() == 0)
            return this;
        Consts c = new Consts(this);
        c.FIXF = p.pretensionFactor(); c.PRE_MARGIN = p.pretensionMargin(); c.RAISE_ITS = p.raiseIters();
        c.MAX_ROUNDS = p.maxRounds(); c.UTIL_LIMIT = p.utilLimit(); c.EMIT_W = p.emitterWeight();
        c.EMIT_WIND0 = p.emitterWind(); c.CD0 = p.windDrag(); c.TORQ0 = p.torsion(); c.RHO = p.density();
        int n = WEXT.length, x = p.extraWidthSteps();
        if (x > 0) {
            double[] w = java.util.Arrays.copyOf(WEXT, n + x);
            double ratio = WEXT[n - 1] / WEXT[n - 2];
            for (int i = n; i < n + x; i++) w[i] = Math.rint(w[i - 1] * ratio * 1000) / 1000;
            c.WEXT = w;
        }
        return c;
    }

    public Consts(Map<String, Object> h) {
        WEXT = Json.darr(h.get("WEXT"));
        CAB_W = Json.darr(h.get("CAB_W"));
        BS_W = Json.darr(h.get("BS_W"));
        WIDTHS = Json.darr(h.get("WIDTHS"));
        RHO_LADDER = Json.darr(h.get("RHO_LADDER"));
        S_LADDER = Json.darr(h.get("S_LADDER"));
        L_LADDER = Json.iarr(h.get("L_LADDER"));
        DEPTH_LADDER = Json.iarr(h.get("DEPTH_LADDER"));
        CAB_A = sq(CAB_W);
        AREAS = sq(WIDTHS);
        Map<String, Object> ref = Json.obj(h.get("reference"));
        refD = Json.darr(ref.get("d")); refN = Json.darr(ref.get("N"));
        refV = Json.darr(ref.get("V")); refM = Json.darr(ref.get("M"));
        Map<String, Object> c = Json.obj(h.get("constants"));
        BAY = g(c, "BAY"); S_CORE = g(c, "S_CORE"); W_LEG_TARGET = g(c, "W_LEG_TARGET"); MID_F = g(c, "MID_F");
        M_POLE_MAX = g(c, "M_POLE_MAX"); POLE_A = g(c, "POLE_A"); W_POLE_MIN = g(c, "W_POLE_MIN");
        EMIT_W = g(c, "EMIT_W"); EMIT_WIND0 = g(c, "EMIT_WIND0"); RHO = g(c, "RHO"); CD0 = g(c, "CD0");
        SIG_CAB = g(c, "SIG_CAB"); SIG_T = g(c, "SIG_T"); SIG_C0 = g(c, "SIG_C0"); L0 = g(c, "L0"); E = g(c, "E");
        BS_MIN_L = g(c, "BS_MIN_L"); D_CAP = g(c, "D_CAP"); D_FRAC = g(c, "D_FRAC"); BS_T_MIN = g(c, "BS_T_MIN");
        BS_CAP = g(c, "BS_CAP"); Q_LOW = g(c, "Q_LOW"); PRE_MARGIN = g(c, "PRE_MARGIN");
        if (h.get("boom_failed") != null)
            for (Object k : Json.arr(h.get("boom_failed"))) BOOM_FAILED.add(java.util.Arrays.toString(Json.iarr(k)));
        if (c.get("STAY_CAP") != null) {
            Object sc = c.get("STAY_CAP");
            STAY_CAP = sc instanceof String str ? STAY_CAP_NAMES.indexOf(str) : Json.inum(sc);
            if (STAY_CAP < 0) throw new IllegalArgumentException("library header: bad STAY_CAP " + sc);
        }
        if (c.get("STAY_RISE") != null) STAY_RISE = g(c, "STAY_RISE");
        if (c.get("STAY_MIN_RATIO") != null) STAY_MIN_RATIO = g(c, "STAY_MIN_RATIO");
        if (c.get("STAY_MIN_RISE") != null) STAY_MIN_RISE = g(c, "STAY_MIN_RISE");
        TAUT_PRE_MAX = g(c, "TAUT_PRE_MAX"); LACE_W = g(c, "LACE_W"); CH_MAX = g(c, "CH_MAX"); R_REF = g(c, "R_REF");
        Map<String, Object> l = Json.obj(h.get("ladders"));
        double[] a;
        a = Json.darr(l.get("N")); N_FLOOR = a[0]; N_R = a[1];
        a = Json.darr(l.get("V")); V_FLOOR = a[0]; V_R = a[1];
        a = Json.darr(l.get("Mg")); MG_FLOOR = a[0]; MG_R = a[1];
        a = Json.darr(l.get("T")); T_FLOOR = a[0]; T_R = a[1];
        BELOW = g(l, "below"); CLASS_TOL = g(l, "class_tol"); CEIL_EPS = g(l, "ceil_eps"); DEMAND_EPS = g(l, "demand_eps");
        List<Object> cs = Json.arr(h.get("cases"));
        K = cs.size();
        caseWind = new double[K][];
        caseTors = new int[K];
        caseName = new String[K];
        for (int k = 0; k < K; k++) {
            List<Object> e = Json.arr(cs.get(k));
            caseName[k] = (String) e.get(0);
            caseWind[k] = e.get(1) == null ? null : Json.darr(e.get(1));
            caseTors[k] = Json.inum(e.get(2));
        }
        int i = 0;
        while (WEXT[i] < LACE_W) i++;          // searchsorted(WEXT, LACE_W) - 1
        I_SOLID_MAX = i - 1;
    }

    private static double g(Map<String, Object> m, String k) {
        Object o = m.get(k);
        if (o == null) throw new IllegalArgumentException("library header lacks " + k);
        return Json.num(o);
    }

    private static double[] sq(double[] a) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] * a[i];
        return r;
    }

    public double sigC(double L) { return SIG_C0 / (1 + (L / L0) * (L / L0)); }

    /** Python bisect_left / numpy searchsorted(side='left'): first i with a[i] >= x. */
    public static int searchLeft(double[] a, double x) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] < x) lo = m + 1; else hi = m; }
        return lo;
    }

    /** Wind case k (1..8) under q quarter turns (p2lib.perm). */
    public static int perm(int k, int q) {
        return (k == 0 || k > 8) ? k : 1 + Math.floorMod(k - 1 + 2 * q, 8);
    }
}

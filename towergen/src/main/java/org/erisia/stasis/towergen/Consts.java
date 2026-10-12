package org.erisia.stasis.towergen;

import java.util.List;
import java.util.Map;

/** Ladders, reference curve, constants and load cases from the library header (p3aexport.header()). */
public final class Consts {
    public final double[] WEXT, CAB_W, CAB_A, BS_W, WIDTHS, AREAS, RHO_LADDER, S_LADDER;
    public final int[] L_LADDER, DEPTH_LADDER;
    public final double[] refD, refN, refV, refM;
    public final double BAY, S_CORE, W_LEG_TARGET, MID_F, M_POLE_MAX, POLE_A, W_POLE_MIN, EMIT_W, EMIT_WIND0, RHO, CD0,
            SIG_CAB, SIG_T, SIG_C0, L0, E, BS_MIN_L, D_CAP, D_FRAC, BS_T_MIN, BS_CAP, Q_LOW, PRE_MARGIN, TAUT_PRE_MAX,
            LACE_W, CH_MAX, R_REF;
    /** Not in the header: fem.TORQ0 (torsion load at an emitter off the axis). */
    public final double TORQ0 = 0.4;
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

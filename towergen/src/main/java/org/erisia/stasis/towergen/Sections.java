package org.erisia.stasis.towergen;

/**
 * Member sections (p25fem.props / sig_laced). kind 0 solid (outer WEXT[ws]), 1 cable (CAB_W[ws]), 2 laced (outer
 * WEXT[ws], chords WEXT[cs], 3 chords if outer <= 0.7 else 4), 3 back-stay (BS_W[ws]).
 */
public final class Sections {
    private final Consts c;

    public Sections(Consts c) { this.c = c; }

    private static int clip(int i, int n) { return Math.max(0, Math.min(i, n - 1)); }

    /** Drawn outer width. */
    public double width(int kind, int ws) {
        return switch (kind) {
            case 1 -> c.CAB_W[clip(ws, c.CAB_W.length)];
            case 3 -> c.BS_W[clip(ws, c.BS_W.length)];
            default -> c.WEXT[clip(ws, c.WEXT.length)];
        };
    }

    public double chord(int kind, int ws, int cs) { return kind == 2 ? c.WEXT[clip(cs, c.WEXT.length)] : width(kind, ws); }

    public static int nchords(double b) { return b <= 0.7 + 1e-9 ? 3 : 4; }

    public double area(int kind, int ws, int cs) {
        double w = width(kind, ws);
        if (kind == 2) { double cw = chord(kind, ws, cs); return nchords(w) * cw * cw; }
        return w * w;
    }

    public double weightArea(int kind, int ws, int cs) { return kind == 2 ? 1.25 * area(kind, ws, cs) : area(kind, ws, cs); }

    public double dragWidth(int kind, int ws, int cs) {
        double w = width(kind, ws);
        return kind == 2 ? Math.min(w, 2.5 * chord(kind, ws, cs)) : w;
    }

    public static boolean isCable(int kind) { return kind == 1 || kind == 3; }

    public double capT(int kind, int ws, int cs) { return (isCable(kind) ? c.SIG_CAB : c.SIG_T) * area(kind, ws, cs); }

    public double capC(int kind, int ws, int cs, double L) {
        if (isCable(kind)) return 0.0;
        double A = area(kind, ws, cs);
        if (kind == 2) return sigLaced(L, width(kind, ws), chord(kind, ws, cs)) * A;
        return c.sigC(L) * A;
    }

    public double sigLaced(double L, double b, double cw) {
        int nc = nchords(b);
        double rc = cw / Math.sqrt(12.0);
        double ro = nc == 4 ? b / 2 : b / Math.sqrt(6.0);
        double lp = Math.min(Math.max(Math.min(b, 8 * cw), 0.25), Math.max(L / 2, 0.25));
        double lc = lp * c.R_REF / (c.L0 * rc);
        double lo = 1.1 * L * c.R_REF / (c.L0 * ro);
        return c.SIG_C0 / (1 + lc * lc + lo * lo);
    }
}

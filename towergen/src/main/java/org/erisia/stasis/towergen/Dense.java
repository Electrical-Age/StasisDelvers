package org.erisia.stasis.towergen;

/** Hand-written dense linear algebra: in-place Cholesky of a packed row-major SPD matrix and its solves. */
public final class Dense {
    private Dense() {}

    /** 3x3 solve by Gaussian elimination with partial pivoting. */
    public static double[] solve3(double[][] A0, double[] b0) {
        double[][] A = {A0[0].clone(), A0[1].clone(), A0[2].clone()};
        double[] b = b0.clone();
        for (int col = 0; col < 3; col++) {
            int p = col;
            for (int r = col + 1; r < 3; r++) if (Math.abs(A[r][col]) > Math.abs(A[p][col])) p = r;
            double[] t = A[col]; A[col] = A[p]; A[p] = t;
            double tb = b[col]; b[col] = b[p]; b[p] = tb;
            for (int r = col + 1; r < 3; r++) {
                double f = A[r][col] / A[col][col];
                for (int k = col; k < 3; k++) A[r][k] -= f * A[col][k];
                b[r] -= f * b[col];
            }
        }
        double[] x = new double[3];
        for (int r = 2; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < 3; k++) s -= A[r][k] * x[k];
            x[r] = s / A[r][r];
        }
        return x;
    }

    /**
     * Cholesky factorisation A = L L^T, in place on the lower triangle of a row-major n x n array. Returns false if
     * a pivot is not positive. Blocked by rows for cache locality (right-looking, j-outer).
     */
    public static boolean cholesky(double[] a, int n) {
        for (int j = 0; j < n; j++) {
            int rj = j * n;
            double d = a[rj + j];
            for (int k = 0; k < j; k++) d -= a[rj + k] * a[rj + k];
            if (!(d > 0)) return false;
            double ljj = Math.sqrt(d);
            a[rj + j] = ljj;
            double inv = 1.0 / ljj;
            for (int i = j + 1; i < n; i++) {
                int ri = i * n;
                double s = a[ri + j];
                for (int k = 0; k < j; k++) s -= a[ri + k] * a[rj + k];
                a[ri + j] = s * inv;
            }
        }
        for (int i = 0; i < n; i++) for (int k = i + 1; k < n; k++) a[i * n + k] = a[k * n + i];   // L^T above
        return true;
    }

    /** Solves L L^T x = b in place (b overwritten with x); l from {@link #cholesky} (L below, L^T above). */
    public static void cholSolve(double[] l, int n, double[] b) {
        for (int i = 0; i < n; i++) {
            int ri = i * n;
            double s = b[i];
            for (int k = 0; k < i; k++) s -= l[ri + k] * b[k];
            b[i] = s / l[ri + i];
        }
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            int ri = i * n;
            for (int k = i + 1; k < n; k++) s -= l[ri + k] * b[k];
            b[i] = s / l[i * n + i];
        }
    }
}

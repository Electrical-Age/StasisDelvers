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
     * Profile (skyline) Cholesky, A = L L^T in place on a row-major n x n array: loops start at the first structurally
     * non-zero column of each row, so the skipped terms are exact zeros (the inner products use 4 partial sums).
     * first[i] = first non-zero column of row i (lower triangle). On return the upper triangle holds L^T within the
     * profile. Returns -1 on success, else the row of the first non-positive pivot.
     */
    public static int choleskyProfileAt(double[] a, int n, int[] first) {
        for (int j = 0; j < n; j++) {
            int rj = j * n, fj = first[j];
            double d = a[rj + j];
            for (int k = fj; k < j; k++) d -= a[rj + k] * a[rj + k];
            if (!(d > 0)) return j;
            double ljj = Math.sqrt(d);
            a[rj + j] = ljj;
            double inv = 1.0 / ljj;
            for (int i = j + 1; i < n; i++) {
                int fi = first[i];
                if (fi > j) continue;                   // L[i][j] stays 0
                int ri = i * n;
                double s = a[ri + j];
                int k0 = Math.max(fi, fj);
                double s0 = 0, s1 = 0, s2 = 0, s3 = 0;
                int k = k0;
                for (; k + 3 < j; k += 4) {
                    s0 += a[ri + k] * a[rj + k]; s1 += a[ri + k + 1] * a[rj + k + 1];
                    s2 += a[ri + k + 2] * a[rj + k + 2]; s3 += a[ri + k + 3] * a[rj + k + 3];
                }
                for (; k < j; k++) s0 += a[ri + k] * a[rj + k];
                a[ri + j] = (s - ((s0 + s1) + (s2 + s3))) * inv;
            }
        }
        for (int i = 0; i < n; i++) for (int k = i + 1; k < n; k++) a[i * n + k] = first[k] <= i ? a[k * n + i] : 0.0;
        return -1;
    }

    /** Solve with a profile factor; last[i] = largest row k with first[k] <= i. */
    public static void cholSolveProfile(double[] l, int n, int[] first, int[] last, double[] b) {
        for (int i = 0; i < n; i++) {
            int ri = i * n;
            double s = b[i];
            for (int k = first[i]; k < i; k++) s -= l[ri + k] * b[k];
            b[i] = s / l[ri + i];
        }
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            int ri = i * n;
            for (int k = i + 1; k <= last[i]; k++) s -= l[ri + k] * b[k];
            b[i] = s / l[ri + i];
        }
    }
}

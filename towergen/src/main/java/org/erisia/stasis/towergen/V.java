package org.erisia.stasis.towergen;

/** Small 3-vector helpers on double[3]. */
public final class V {
    private V() {}

    public static double[] v(double x, double y, double z) { return new double[]{x, y, z}; }
    public static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    public static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    public static double[] mul(double[] a, double s) { return new double[]{a[0] * s, a[1] * s, a[2] * s}; }
    public static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    public static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
    public static double[] unit(double[] a) { return mul(a, 1.0 / norm(a)); }

    public static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    public static void addTo(double[] acc, double[] b) { acc[0] += b[0]; acc[1] += b[1]; acc[2] += b[2]; }

    /** rot^q: q quarter turns of (x, y, z) -> (-z, y, x) (E -> S). */
    public static double[] rot(double[] p, int q) {
        double x = p[0], y = p[1], z = p[2];
        for (int i = 0; i < Math.floorMod(q, 4); i++) { double nx = -z; z = x; x = nx; }
        return new double[]{x, y, z};
    }

    /** numpy.linalg.norm(cross(a, b)) without allocating. */
    public static double crossNorm(double[] a, double[] b) {
        double x = a[1] * b[2] - a[2] * b[1], y = a[2] * b[0] - a[0] * b[2], z = a[0] * b[1] - a[1] * b[0];
        return Math.sqrt(x * x + y * y + z * z);
    }
}

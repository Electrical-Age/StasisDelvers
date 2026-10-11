package org.erisia.stasis;

/** Smooth 3D value noise in [0, 1] (the same as the render prototype's vnoise), for collapse patches. */
final class Noise {
    private Noise() {
    }

    static double hash(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    static double value(double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = s(x - ix), fy = s(y - iy), fz = s(z - iz);
        double a = lerp(fx, hash(ix, iy, iz), hash(ix + 1, iy, iz)), b = lerp(fx, hash(ix, iy + 1, iz), hash(ix + 1, iy + 1, iz));
        double c = lerp(fx, hash(ix, iy, iz + 1), hash(ix + 1, iy, iz + 1)), d = lerp(fx, hash(ix, iy + 1, iz + 1), hash(ix + 1, iy + 1, iz + 1));
        return lerp(fz, lerp(fy, a, b), lerp(fy, c, d));
    }

    private static double s(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }
}

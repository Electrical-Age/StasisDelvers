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

    // ---- 32-bit variant, written identically in GLSL (shaders/post/stasis.fsh: hash32, vnoise32, fbm32): ints wrap
    // the same way, and float maths agrees closely. Seeds are 24-bit (exact as floats in the shader's data texture).

    static float hash32(int x, int y, int z, int seed) {
        return (hashInt(x, y, z, seed) >>> 8) / 16777216f;
    }

    /**
     * 32 hashed bits of (x, y, z, seed). The coordinates are mixed in one after another (not XORed together first: with
     * odd multipliers x*a ^ z*c equals (-x)*a ^ (-z)*c whenever x and z are odd, so cells mirrored through the origin
     * got the same hash, and rifts came in point-symmetric pairs with the same shape, ruins and timing: ErisiAgent saw
     * two identical ruin sets on the fresh incognito). stasis.fsh has the same.
     */
    static int hashInt(int x, int y, int z, int seed) {
        int h = x * 0x8da6b343;
        h ^= h >>> 15;
        h += y * 0xd8163841;
        h ^= h >>> 13;
        h += z * 0xcb1ab31f;
        h ^= h >>> 16;
        h += seed * 0x9e3779b9;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    static float value32(float x, float y, float z, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        float fx = s32(x - ix), fy = s32(y - iy), fz = s32(z - iz);
        float a = lerp32(fx, hash32(ix, iy, iz, seed), hash32(ix + 1, iy, iz, seed));
        float b = lerp32(fx, hash32(ix, iy + 1, iz, seed), hash32(ix + 1, iy + 1, iz, seed));
        float c = lerp32(fx, hash32(ix, iy, iz + 1, seed), hash32(ix + 1, iy, iz + 1, seed));
        float d = lerp32(fx, hash32(ix, iy + 1, iz + 1, seed), hash32(ix + 1, iy + 1, iz + 1, seed));
        return lerp32(fz, lerp32(fy, a, b), lerp32(fy, c, d));
    }

    /** Two octaves in [0, 1]. */
    static float fbm32(float x, float y, float z, int seed) {
        return 0.65f * value32(x, y, z, seed) + 0.35f * value32(x * 2.03f + 17.1f, y * 2.03f + 5.3f, z * 2.03f + 11.7f, seed);
    }

    private static float s32(float t) {
        return t * t * (3 - 2 * t);
    }

    private static float lerp32(float t, float a, float b) {
        return a + (b - a) * t;
    }

    private static double s(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }
}

package org.erisia.stasis.towergen;

/**
 * Small stand-ins for JDK methods that the browser build (TeaVM, towerweb) lacks: Arrays.compare(int[], int[]) and
 * MessageDigest SHA-1. Same results as the JDK.
 */
public final class Portable {
    private Portable() {}

    /** Lexicographic comparison as java.util.Arrays.compare(int[], int[]). */
    public static int compare(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        return a.length - b.length;
    }

    /** SHA-1 digest (FIPS 180-4). */
    public static byte[] sha1(byte[] msg) {
        int h0 = 0x67452301, h1 = 0xEFCDAB89, h2 = 0x98BADCFE, h3 = 0x10325476, h4 = 0xC3D2E1F0;
        int nblk = (msg.length + 9 + 63) / 64;
        byte[] p = new byte[nblk * 64];
        System.arraycopy(msg, 0, p, 0, msg.length);
        p[msg.length] = (byte) 0x80;
        long bits = (long) msg.length * 8;
        for (int i = 0; i < 8; i++) p[p.length - 1 - i] = (byte) (bits >>> (8 * i));
        int[] w = new int[80];
        for (int blk = 0; blk < nblk; blk++) {
            for (int i = 0; i < 16; i++) {
                int o = blk * 64 + 4 * i;
                w[i] = ((p[o] & 0xff) << 24) | ((p[o + 1] & 0xff) << 16) | ((p[o + 2] & 0xff) << 8) | (p[o + 3] & 0xff);
            }
            for (int i = 16; i < 80; i++) w[i] = Integer.rotateLeft(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
            int a = h0, b = h1, c = h2, d = h3, e = h4;
            for (int i = 0; i < 80; i++) {
                int f, k;
                if (i < 20) { f = (b & c) | (~b & d); k = 0x5A827999; }
                else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
                else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
                else { f = b ^ c ^ d; k = 0xCA62C1D6; }
                int t = Integer.rotateLeft(a, 5) + f + e + k + w[i];
                e = d; d = c; c = Integer.rotateLeft(b, 30); b = a; a = t;
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e;
        }
        int[] h = {h0, h1, h2, h3, h4};
        byte[] out = new byte[20];
        for (int i = 0; i < 5; i++) for (int j = 0; j < 4; j++) out[4 * i + j] = (byte) (h[i] >>> (24 - 8 * j));
        return out;
    }
}

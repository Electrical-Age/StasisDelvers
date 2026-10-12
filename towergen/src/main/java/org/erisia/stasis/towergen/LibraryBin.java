package org.erisia.stasis.towergen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Compact binary form of the library (format TGLB1) for the browser build (towerweb), lossless with respect to the
 * JSON loader: the header as JSON text, then every field {@link Library#load} keeps (bay reactions are dropped as
 * there). Integers are zigzag varints; doubles are varints of k with a 2-bit tag when the value is exactly k / 16
 * (node coordinates) or k / 1e4 (rounded reactions, pretensions), else 8 raw bytes. ~1/3 of the JSON's gzipped size
 * once gzipped, and no text parsing.
 * <p>
 * {@code java -cp towergen.jar org.erisia.stasis.towergen.LibraryBin [in.json.gz] out.bin[.gz]}
 */
public final class LibraryBin {
    private LibraryBin() {}

    static final byte[] MAGIC = {'T', 'G', 'L', 'B', '1'};
    private static final int TAG_16 = 0, TAG_1E4 = 1, TAG_RAW = 2;

    // ------------------------------------------------------------------------------------------------ writer

    private static final class Out {
        final ByteArrayOutputStream b = new ByteArrayOutputStream(1 << 22);

        void uv(int v) {                                  // unsigned varint
            while ((v & ~0x7f) != 0) { b.write((v & 0x7f) | 0x80); v >>>= 7; }
            b.write(v);
        }
        void i(int v) { uv((v << 1) ^ (v >> 31)); }
        void ia(int[] a) { uv(a.length); for (int x : a) i(x); }
        void d(double x) {
            long bits = Double.doubleToRawLongBits(x);
            double k16 = Math.rint(x * 16);
            if (Math.abs(k16) < (1 << 28) && Double.doubleToRawLongBits((int) k16 / 16.0) == bits) { tagged((int) k16, TAG_16); return; }
            double k4 = Math.rint(x * 1e4);
            if (Math.abs(k4) < (1 << 28) && Double.doubleToRawLongBits((int) k4 / 1e4) == bits) { tagged((int) k4, TAG_1E4); return; }
            uv(TAG_RAW);
            for (int s = 56; s >= 0; s -= 8) b.write((int) (bits >>> s) & 0xff);
        }
        private void tagged(int k, int tag) { uv((((k << 1) ^ (k >> 31)) << 2) | tag); }
        void da(double[] a) { uv(a.length); for (double x : a) d(x); }
        void str(String s) { byte[] u = s.getBytes(StandardCharsets.UTF_8); uv(u.length); b.write(u, 0, u.length); }
    }

    public static byte[] write(Library lib) {
        if (lib.header == null) throw new IllegalArgumentException("library has no header (load it from JSON)");
        Out o = new Out();
        o.b.write(MAGIC, 0, MAGIC.length);
        o.str(Json.write(lib.header));
        o.uv(lib.entries.size());
        for (Library.Entry e : lib.entries) {
            o.uv(switch (e.kind) { case "boom" -> 0; case "bay" -> 1; default -> 2; });
            o.ia(e.key.k());
            o.ia(e.pole); o.ia(e.ghost); o.ia(e.sup); o.ia(e.top);
            o.uv(e.legs.length);
            for (int[] l : e.legs) o.ia(l);
            o.ia(e.roots); o.ia(e.anchors); o.i(e.tip);
            write3(o, e.reacRoots); write3(o, e.reacAnchors);
            writeVariant(o, e.q1); writeVariant(o, e.q03);
        }
        return o.b.toByteArray();
    }

    private static void write3(Out o, double[][][] a) {
        if (a == null) { o.uv(0); return; }
        o.uv(a.length + 1);
        for (double[][] r : a) { o.uv(r.length); for (double[] x : r) o.da(x); }
    }

    private static void writeVariant(Out o, Library.Variant v) {
        if (v == null) { o.uv(0); return; }
        o.uv(1);
        o.da(v.nodes);
        o.ia(v.mi); o.ia(v.mj); o.ia(v.kind); o.ia(v.ws); o.ia(v.cs);
        o.da(v.pre);
        if (v.poleW == null) o.uv(0); else { o.uv(1); o.da(v.poleW); }
    }

    // ------------------------------------------------------------------------------------------------ reader

    private static final class In {
        final byte[] b;
        int p;
        In(byte[] b) { this.b = b; }

        int uv() {
            int v = 0, s = 0, x;
            do { x = b[p++]; v |= (x & 0x7f) << s; s += 7; } while ((x & 0x80) != 0);
            return v;
        }
        int i() { int v = uv(); return (v >>> 1) ^ -(v & 1); }
        int[] ia() { int[] a = new int[uv()]; for (int k = 0; k < a.length; k++) a[k] = i(); return a; }
        double d() {
            int v = uv();
            int tag = v & 3;
            if (tag == TAG_RAW) {
                long bits = 0;
                for (int k = 0; k < 8; k++) bits = (bits << 8) | (b[p++] & 0xff);
                return Double.longBitsToDouble(bits);
            }
            int z = v >>> 2, k = (z >>> 1) ^ -(z & 1);
            return tag == TAG_16 ? k / 16.0 : k / 1e4;
        }
        double[] da() { double[] a = new double[uv()]; for (int k = 0; k < a.length; k++) a[k] = d(); return a; }
        String str() { int n = uv(); String s = new String(b, p, n, StandardCharsets.UTF_8); p += n; return s; }
    }

    public static boolean isBinary(byte[] b) {
        if (b.length < MAGIC.length) return false;
        for (int k = 0; k < MAGIC.length; k++) if (b[k] != MAGIC[k]) return false;
        return true;
    }

    /** Reads an uncompressed TGLB1 image. */
    public static Library read(byte[] bytes) {
        long t0 = System.nanoTime();
        if (!isBinary(bytes)) throw new IllegalArgumentException("not a TGLB1 library");
        In in = new In(bytes);
        in.p = MAGIC.length;
        java.util.Map<String, Object> header = Json.obj(Json.parse(in.str()));
        Consts c = new Consts(header);
        int n = in.uv();
        List<Library.Entry> ents = new ArrayList<>(n);
        for (int q = 0; q < n; q++) {
            Library.Entry e = new Library.Entry();
            int kind = in.uv();
            e.kind = kind == 0 ? "boom" : kind == 1 ? "bay" : "seam";
            e.key = new Library.Key(in.ia());
            e.pole = in.ia(); e.ghost = in.ia(); e.sup = in.ia(); e.top = in.ia();
            e.legs = new int[in.uv()][];
            for (int k = 0; k < e.legs.length; k++) e.legs[k] = in.ia();
            e.roots = in.ia(); e.anchors = in.ia(); e.tip = in.i();
            e.reacRoots = read3(in); e.reacAnchors = read3(in);
            e.q1 = readVariant(in); e.q03 = readVariant(in);
            ents.add(e);
        }
        Library lib = new Library(c, ents);
        lib.header = header;
        lib.loadMs = (System.nanoTime() - t0) / 1_000_000;
        return lib;
    }

    private static double[][][] read3(In in) {
        int n = in.uv();
        if (n == 0) return null;
        double[][][] a = new double[n - 1][][];
        for (int k = 0; k < a.length; k++) {
            a[k] = new double[in.uv()][];
            for (int j = 0; j < a[k].length; j++) a[k][j] = in.da();
        }
        return a;
    }

    private static Library.Variant readVariant(In in) {
        if (in.uv() == 0) return null;
        Library.Variant v = new Library.Variant();
        v.nodes = in.da();
        v.mi = in.ia(); v.mj = in.ia(); v.kind = in.ia(); v.ws = in.ia(); v.cs = in.ia();
        v.pre = in.da();
        if (in.uv() != 0) v.poleW = in.da();
        return v;
    }

    // ------------------------------------------------------------------------------------------------ CLI

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: LibraryBin [in.json.gz] out.bin[.gz]   (default input: the classpath library)");
            System.exit(2);
        }
        Library lib = args.length == 2 ? Library.load(Path.of(args[0])) : Library.loadDefault();
        Path out = Path.of(args[args.length - 1]);
        byte[] b = write(lib);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out)) {
            if (out.toString().endsWith(".gz")) {
                try (GZIPOutputStream gz = new GZIPOutputStream(os, 1 << 16)) { gz.write(b); }
            } else os.write(b);
        }
        System.out.printf("%d entries, %d bytes raw, %d bytes written to %s%n", lib.entries.size(), b.length, Files.size(out), out);
    }
}

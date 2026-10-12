package org.erisia.stasis.towergen;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The P3a module library (p3a_library.json.gz, format p3a-1): booms, plain bays and seam bays keyed by integer
 * tuples (NOTES-P3a section 1). Entries are read with the streaming JSON reader straight into typed arrays; bay
 * support reactions are skipped (the runtime only needs the boom reactions).
 */
public final class Library {
    /** Integer tuple key with value semantics. */
    public record Key(int[] k) {
        @Override public boolean equals(Object o) { return o instanceof Key x && Arrays.equals(k, x.k); }
        @Override public int hashCode() { return Arrays.hashCode(k); }
        @Override public String toString() { return Arrays.toString(k); }
        public int len() { return k.length; }
    }

    /** One geometry variant (q1 or q0.3) of an entry. */
    public static final class Variant {
        public double[] nodes;       // n x 3
        public int[] mi, mj, kind, ws, cs;
        public double[] pre;
        public double[] poleW;
        public int n() { return nodes.length / 3; }
        public int m() { return mi.length; }
    }

    public static final class Entry {
        public Key key;
        public String kind;          // boom | bay | seam
        public int[] pole = {}, ghost = {}, sup = {}, top = {};
        public Variant q1, q03;
        public int[][] legs = {};
        // booms
        public int[] roots = {}, anchors = {};
        public int tip = -1;
        public double[][][] reacRoots, reacAnchors;   // [K][j][3]

        /** p2lib.split_key: number of roots of a bay key. */
        public int nroots() { return key.k[11]; }
    }

    public final Consts c;
    public final List<Entry> entries;
    public final Map<Key, Entry> booms = new HashMap<>(), plain = new HashMap<>(), seam = new HashMap<>();
    /** Plain bays by (sb4, st4, h) ring signature (fallback tiers scan only these). */
    public final Map<Long, List<Entry>> plainBySig = new HashMap<>();
    /** Seam bays by their own (plain) key prefix. */
    public final Map<Key, List<Entry>> seamByOwn = new HashMap<>();
    public long loadMs;

    private Library(Consts c, List<Entry> entries) {
        this.c = c;
        this.entries = entries;
        for (Entry e : entries) {
            switch (e.kind) {
                case "boom" -> booms.put(e.key, e);
                case "bay" -> {
                    plain.put(e.key, e);
                    plainBySig.computeIfAbsent(sig(e.key.k[0], e.key.k[1], e.key.k[10]), x -> new ArrayList<>()).add(e);
                }
                default -> {
                    seam.put(e.key, e);
                    int nOwn = 12 + 6 * e.key.k[11];
                    seamByOwn.computeIfAbsent(new Key(Arrays.copyOf(e.key.k, nOwn)), x -> new ArrayList<>()).add(e);
                }
            }
        }
    }

    public static long sig(int sb4, int st4, int h) { return ((long) sb4 << 40) | ((long) st4 << 20) | h; }

    /** Default library from the classpath (/towergen/p3a_library.json.gz). */
    public static Library loadDefault() throws IOException {
        InputStream in = Library.class.getResourceAsStream("/towergen/p3a_library.json.gz");
        if (in == null) throw new IOException("library resource /towergen/p3a_library.json.gz missing");
        return load(in, true);
    }

    public static Library load(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) { return load(in, p.toString().endsWith(".gz")); }
    }

    private static volatile Library cached;

    /** Shared default library (loaded once). */
    public static Library shared() {
        Library l = cached;
        if (l == null) {
            synchronized (Library.class) {
                if (cached == null) {
                    try { cached = loadDefault(); } catch (IOException e) { throw new RuntimeException(e); }
                }
                l = cached;
            }
        }
        return l;
    }

    public static Library load(InputStream raw, boolean gz) throws IOException {
        long t0 = System.nanoTime();
        InputStream in = new BufferedInputStream(gz ? new GZIPInputStream(raw, 1 << 16) : raw, 1 << 16);
        Json j = new Json(new InputStreamReader(in, StandardCharsets.UTF_8));
        Consts c = null;
        List<Entry> ents = new ArrayList<>();
        j.beginObject();
        while (j.hasNext()) {
            String name = j.nextName();
            if (name.equals("header")) {
                c = new Consts(Json.obj(j.readValue()));
            } else if (name.equals("entries")) {
                if (c == null) throw new IOException("header must precede entries");
                j.beginArray();
                while (j.hasNext()) ents.add(readEntry(j, c.K));
                j.endArray();
            } else j.skipValue();
        }
        j.endObject();
        Library lib = new Library(c, ents);
        lib.loadMs = (System.nanoTime() - t0) / 1_000_000;
        return lib;
    }

    private static Entry readEntry(Json j, int K) throws IOException {
        Entry e = new Entry();
        j.beginObject();
        while (j.hasNext()) {
            String n = j.nextName();
            switch (n) {
                case "key" -> e.key = new Key(j.nextIntArray());
                case "kind" -> e.kind = j.nextString();
                case "pole" -> e.pole = j.nextIntArray();
                case "ghost" -> e.ghost = j.nextIntArray();
                case "sup" -> e.sup = j.nextIntArray();
                case "top" -> e.top = j.nextIntArray();
                case "q1" -> e.q1 = readVariant(j);
                case "q03" -> {
                    if (j.peek() == Json.Tok.NULL) j.nextNull(); else e.q03 = readVariant(j);
                }
                case "legs" -> {
                    List<int[]> l = new ArrayList<>();
                    j.beginArray();
                    while (j.hasNext()) l.add(j.nextIntArray());
                    j.endArray();
                    e.legs = l.toArray(new int[0][]);
                }
                case "roots" -> e.roots = j.nextIntArray();
                case "anchors" -> e.anchors = j.nextIntArray();
                case "tip" -> e.tip = j.nextInt();
                case "reac_roots" -> e.reacRoots = read3(j);
                case "reac_anchors" -> e.reacAnchors = read3(j);
                default -> j.skipValue();      // bay reactions, reactions_pre: not needed at runtime
            }
        }
        j.endObject();
        return e;
    }

    private static double[][][] read3(Json j) throws IOException {
        List<double[][]> ks = new ArrayList<>();
        j.beginArray();
        while (j.hasNext()) {
            List<double[]> r = new ArrayList<>();
            j.beginArray();
            while (j.hasNext()) r.add(j.nextDoubleArray());
            j.endArray();
            ks.add(r.toArray(new double[0][]));
        }
        j.endArray();
        return ks.toArray(new double[0][][]);
    }

    private static Variant readVariant(Json j) throws IOException {
        Variant v = new Variant();
        j.beginObject();
        while (j.hasNext()) {
            String n = j.nextName();
            switch (n) {
                case "nodes" -> {
                    double[] a = new double[96]; int k = 0;
                    j.beginArray();
                    while (j.hasNext()) {
                        if (k + 3 > a.length) a = Arrays.copyOf(a, a.length * 2);
                        j.beginArray();
                        a[k++] = j.nextDouble(); a[k++] = j.nextDouble(); a[k++] = j.nextDouble();
                        j.endArray();
                    }
                    j.endArray();
                    v.nodes = Arrays.copyOf(a, k);
                }
                case "members" -> {
                    int cap = 64, m = 0;
                    int[] mi = new int[cap], mj = new int[cap], kd = new int[cap], ws = new int[cap], cs = new int[cap];
                    double[] pre = new double[cap];
                    j.beginArray();
                    while (j.hasNext()) {
                        if (m == cap) {
                            cap *= 2;
                            mi = Arrays.copyOf(mi, cap); mj = Arrays.copyOf(mj, cap); kd = Arrays.copyOf(kd, cap);
                            ws = Arrays.copyOf(ws, cap); cs = Arrays.copyOf(cs, cap); pre = Arrays.copyOf(pre, cap);
                        }
                        j.beginArray();
                        mi[m] = j.nextInt(); mj[m] = j.nextInt(); kd[m] = j.nextInt(); ws[m] = j.nextInt(); cs[m] = j.nextInt();
                        pre[m] = j.nextDouble();
                        j.endArray();
                        m++;
                    }
                    j.endArray();
                    v.mi = Arrays.copyOf(mi, m); v.mj = Arrays.copyOf(mj, m); v.kind = Arrays.copyOf(kd, m);
                    v.ws = Arrays.copyOf(ws, m); v.cs = Arrays.copyOf(cs, m); v.pre = Arrays.copyOf(pre, m);
                }
                case "pole_w" -> v.poleW = j.nextDoubleArray();
                default -> j.skipValue();
            }
        }
        j.endObject();
        return v;
    }
}

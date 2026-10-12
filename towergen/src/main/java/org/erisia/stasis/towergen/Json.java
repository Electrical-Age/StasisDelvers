package org.erisia.stasis.towergen;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader (no dependencies). Two uses:
 * <ul>
 *   <li>a pull API ({@link #peek()}, {@link #beginObject()}, {@link #nextName()}, {@link #nextDouble()} ...) for the
 *   7.8 MB library, read straight into typed arrays;</li>
 *   <li>{@link #parse(String)} / {@link #readValue()}: a tree of {@code Map<String,Object>} (insertion ordered),
 *   {@code List<Object>}, {@code Double}, {@code String}, {@code Boolean} and {@code null} for small files
 *   (layouts, visual parameters, goldens).</li>
 * </ul>
 */
public final class Json {
    public enum Tok { BEGIN_OBJ, END_OBJ, BEGIN_ARR, END_ARR, NAME, STRING, NUMBER, BOOL, NULL, EOF }

    private final Reader in;
    private final char[] buf = new char[1 << 16];
    private int pos, lim;
    private final StringBuilder sb = new StringBuilder();
    // container stack: true = object
    private boolean[] stack = new boolean[64];
    private int depth;
    private boolean expectName;   // inside an object, at a name position
    private Tok peeked;

    public Json(Reader in) { this.in = in; }

    public static Object parse(String s) {
        try { return new Json(new StringReader(s)).readValue(); }
        catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    public static Object parse(Reader r) throws IOException { return new Json(r).readValue(); }

    private int read() throws IOException {
        if (pos >= lim) {
            lim = in.read(buf, 0, buf.length);
            pos = 0;
            if (lim <= 0) { lim = 0; return -1; }
        }
        return buf[pos++];
    }

    private int peekChar() throws IOException {
        if (pos >= lim) {
            lim = in.read(buf, 0, buf.length);
            pos = 0;
            if (lim <= 0) { lim = 0; return -1; }
        }
        return buf[pos];
    }

    private int skipWs() throws IOException {
        while (true) {
            int c = peekChar();
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') { pos++; continue; }
            return c;
        }
    }

    public Tok peek() throws IOException {
        if (peeked != null) return peeked;
        int c = skipWs();
        if (c == ',' ) { pos++; c = skipWs(); }
        boolean inObj = depth > 0 && stack[depth - 1];
        if (c == -1) return peeked = Tok.EOF;
        switch (c) {
            case '{': return peeked = Tok.BEGIN_OBJ;
            case '}': return peeked = Tok.END_OBJ;
            case '[': return peeked = Tok.BEGIN_ARR;
            case ']': return peeked = Tok.END_ARR;
            case '"': return peeked = (inObj && expectName) ? Tok.NAME : Tok.STRING;
            case 't': case 'f': return peeked = Tok.BOOL;
            case 'n': return peeked = Tok.NULL;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return peeked = Tok.NUMBER;
                throw new IOException("bad JSON char '" + (char) c + "'");
        }
    }

    private void push(boolean obj) {
        if (depth == stack.length) stack = java.util.Arrays.copyOf(stack, depth * 2);
        stack[depth++] = obj;
        expectName = obj;
    }

    private void afterValue() {
        boolean inObj = depth > 0 && stack[depth - 1];
        expectName = inObj;
    }

    public void beginObject() throws IOException { expect(Tok.BEGIN_OBJ); pos++; peeked = null; push(true); }
    public void beginArray() throws IOException { expect(Tok.BEGIN_ARR); pos++; peeked = null; push(false); }
    public void endObject() throws IOException { expect(Tok.END_OBJ); pos++; peeked = null; depth--; afterValue(); }
    public void endArray() throws IOException { expect(Tok.END_ARR); pos++; peeked = null; depth--; afterValue(); }
    public boolean hasNext() throws IOException { Tok t = peek(); return t != Tok.END_ARR && t != Tok.END_OBJ && t != Tok.EOF; }

    private void expect(Tok t) throws IOException {
        Tok p = peek();
        if (p != t) throw new IOException("expected " + t + " got " + p);
    }

    public String nextName() throws IOException {
        expect(Tok.NAME); peeked = null;
        String s = readString();
        int c = skipWs();
        if (c != ':') throw new IOException("expected ':'");
        pos++;
        expectName = false;
        return s;
    }

    public String nextString() throws IOException {
        expect(Tok.STRING); peeked = null;
        String s = readString();
        afterValue();
        return s;
    }

    private String readString() throws IOException {
        read(); // opening quote
        sb.setLength(0);
        while (true) {
            int c = read();
            if (c == -1) throw new IOException("unterminated string");
            if (c == '"') break;
            if (c == '\\') {
                int e = read();
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        int v = 0;
                        for (int k = 0; k < 4; k++) v = v * 16 + Character.digit(read(), 16);
                        sb.append((char) v);
                    }
                    default -> sb.append((char) e);
                }
            } else sb.append((char) c);
        }
        return sb.toString();
    }

    public double nextDouble() throws IOException {
        expect(Tok.NUMBER); peeked = null;
        sb.setLength(0);
        while (true) {
            int c = peekChar();
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') { sb.append((char) c); pos++; }
            else break;
        }
        afterValue();
        return Double.parseDouble(sb.toString());
    }

    public int nextInt() throws IOException { return (int) Math.round(nextDouble()); }

    public boolean nextBool() throws IOException {
        expect(Tok.BOOL); peeked = null;
        int c = read();
        String w = c == 't' ? "rue" : "alse";
        for (int k = 0; k < w.length(); k++) read();
        afterValue();
        return c == 't';
    }

    public void nextNull() throws IOException {
        expect(Tok.NULL); peeked = null;
        for (int k = 0; k < 4; k++) read();
        afterValue();
    }

    public void skipValue() throws IOException { readValue(); }

    /** Reads the next value as a tree. */
    public Object readValue() throws IOException {
        switch (peek()) {
            case BEGIN_OBJ -> {
                beginObject();
                Map<String, Object> m = new LinkedHashMap<>();
                while (hasNext()) { String k = nextName(); m.put(k, readValue()); }
                endObject();
                return m;
            }
            case BEGIN_ARR -> {
                beginArray();
                List<Object> l = new ArrayList<>();
                while (hasNext()) l.add(readValue());
                endArray();
                return l;
            }
            case STRING -> { return nextString(); }
            case NUMBER -> { return nextDouble(); }
            case BOOL -> { return nextBool(); }
            case NULL -> { nextNull(); return null; }
            default -> throw new IOException("unexpected " + peek());
        }
    }

    /** Array of numbers -> double[]. */
    public double[] nextDoubleArray() throws IOException {
        beginArray();
        double[] a = new double[16]; int n = 0;
        while (hasNext()) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = nextDouble(); }
        endArray();
        return java.util.Arrays.copyOf(a, n);
    }

    public int[] nextIntArray() throws IOException {
        double[] d = nextDoubleArray();
        int[] r = new int[d.length];
        for (int i = 0; i < d.length; i++) r[i] = (int) Math.round(d[i]);
        return r;
    }

    // ------------------------------------------------------------------ tree helpers
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return (List<Object>) o; }

    public static double num(Object o) { return ((Number) o).doubleValue(); }

    public static int inum(Object o) { return (int) Math.round(((Number) o).doubleValue()); }

    public static double[] darr(Object o) {
        List<Object> l = arr(o);
        double[] r = new double[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = num(l.get(i));
        return r;
    }

    public static int[] iarr(Object o) {
        List<Object> l = arr(o);
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = inum(l.get(i));
        return r;
    }

    /** Serialises a tree (Map / List / Number / String / Boolean / null) as compact JSON. */
    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o, -1, 0);
        return b.toString();
    }

    /** Pretty-printed JSON (2-space indent; short numeric arrays stay on one line). */
    public static String writePretty(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o, 2, 0);
        b.append('\n');
        return b.toString();
    }

    private static void write(StringBuilder b, Object o, int ind, int lvl) {
        if (o == null) b.append("null");
        else if (o instanceof String s) quote(b, s);
        else if (o instanceof Boolean x) b.append(x);
        else if (o instanceof Double d) {
            if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) (double) d); else b.append(d);
        } else if (o instanceof Float f) {
            double d = f;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(f);
        } else if (o instanceof Number n) b.append(n);
        else if (o instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                nl(b, ind, lvl + 1);
                quote(b, String.valueOf(e.getKey()));
                b.append(ind >= 0 ? ": " : ":");
                write(b, e.getValue(), ind, lvl + 1);
            }
            if (!m.isEmpty()) nl(b, ind, lvl);
            b.append('}');
        } else if (o instanceof List<?> l) {
            boolean flat = l.stream().allMatch(x -> x == null || x instanceof Number || x instanceof String || x instanceof Boolean);
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) b.append(flat && ind >= 0 ? ", " : ",");
                if (!flat) nl(b, ind, lvl + 1);
                write(b, l.get(i), ind, lvl + 1);
            }
            if (!flat && !l.isEmpty()) nl(b, ind, lvl);
            b.append(']');
        } else quote(b, o.toString());
    }

    private static void nl(StringBuilder b, int ind, int lvl) {
        if (ind < 0) return;
        b.append('\n');
        for (int i = 0; i < ind * lvl; i++) b.append(' ');
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        b.append('"');
    }
}

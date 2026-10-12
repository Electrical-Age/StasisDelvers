package org.erisia.stasis.towerweb;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.erisia.stasis.towergen.Json;
import org.erisia.stasis.towergen.Library;
import org.erisia.stasis.towergen.SolverParams;
import org.erisia.stasis.towergen.VisualParams;

/**
 * Build-time generator (runs on the JVM, where reflection works): TeaVM has no record reflection, so this writes
 * VisualParamsCodec.java (VisualParams from a JSON tree with an explicit constructor call) plus the default
 * visual.json and the key documentation (from the comments in VisualParams.java) for the web page.
 * Args: VisualParams.java source, generated-source dir, web output dir.
 */
public final class GenCodec {
    /** Docs for the keys without a comment in VisualParams.java. */
    private static final Map<String, String> EXTRA = Map.ofEntries(
            Map.entry("lacingMinWidth", "minimum lacing bar width (blocks)"),
            Map.entry("lacingPanelsMin", "fewest lacing panels per laced member"),
            Map.entry("lacingPanelsMax", "most lacing panels per laced member"),
            Map.entry("poleCollarAdd", "added to the pole collar width (blocks)"),
            Map.entry("poleCollarHeight", "pole collar height (blocks)"),
            Map.entry("footingMin", "smallest footing width (blocks)"),
            Map.entry("footingHeight", "footing height (blocks)"),
            Map.entry("deadmanSize", "back-stay deadman block width (blocks)"),
            Map.entry("deadmanHeight", "back-stay deadman height (blocks)"),
            Map.entry("turnbuckleWidth", "back-stay turnbuckle width (blocks)"),
            Map.entry("turnbuckleLength", "back-stay turnbuckle length (blocks)"),
            Map.entry("emitterScale", "size of the insulator-stack emitters"),
            Map.entry("drawCore", "draw the concrete core block at the mast foot"),
            Map.entry("coreWidth", "core block width (blocks)"),
            Map.entry("coreHeight", "core block height (blocks)"),
            Map.entry("colPole", "pole segments"),
            Map.entry("colLacing", "lacing bars and battens of laced members"),
            Map.entry("colKnuckle", "joint hubs (unless hubMemberColour)"),
            Map.entry("colCable", "cables"),
            Map.entry("colBackstay", "back-stay wire rope"),
            Map.entry("colPorcelain", "emitter insulator discs"),
            Map.entry("colGlow", "emitter glow ring (drawn emissive on the web page)"),
            Map.entry("colBrass", "pole collars, turnbuckles"),
            Map.entry("colFooting", "footings"),
            Map.entry("colDeadman", "back-stay deadmen"),
            Map.entry("colCore", "core block"),
            Map.entry("skyTop", "sky colour at the zenith"),
            Map.entry("skyHorizon", "sky colour at the horizon"),
            Map.entry("groundColour", "ground plane colour"),
            Map.entry("ambient", "ambient light fraction (0..1)"),
            Map.entry("fovDeg", "camera vertical field of view (degrees)"),
            Map.entry("drawGround", "draw the ground plane"));

    public static void main(String[] args) throws Exception {
        Path javaOut = Path.of(args[1]), webOut = Path.of(args[2]);
        Path srcDir = Path.of(args[0]).getParent();
        gen(VisualParams.class, Files.readString(Path.of(args[0])), "VisualParamsCodec", "visual",
                "", "VisualParams.defaults()", VisualParams.defaults().toJson(), EXTRA, javaOut, webOut);
        Library lib = Library.shared();
        gen(SolverParams.class, Files.readString(srcDir.resolve("SolverParams.java")), "SolverParamsCodec", "solver",
                ", org.erisia.stasis.towergen.Consts c", "SolverParams.defaults(c)", SolverParams.defaults(lib.c).toJson(),
                Map.of(), javaOut, webOut);
    }

    /**
     * Writes NAME.java (KEYS, fromJson(map[, consts]) with an explicit constructor call), PREFIX-defaults.json and
     * PREFIX-doc.json for one parameter record.
     */
    static void gen(Class<?> cls, String src, String name, String prefix, String extraParam, String defaultsExpr,
                    Map<String, Object> defs, Map<String, String> extra, Path javaOut, Path webOut) throws Exception {
        RecordComponent[] rc = cls.getRecordComponents();
        String simple = cls.getSimpleName();

        // comments: "type name,   // comment" (+ continuation lines starting with //), section headers "// --- x"
        Map<String, String> doc = new LinkedHashMap<>(), section = new LinkedHashMap<>();
        String sec = "";
        String last = null;
        Pattern comp = Pattern.compile("^\\s*[\\w\\[\\]]+\\s+(\\w+)\\s*[,)]?\\s*(?://\\s*(.*))?$");
        int start = src.indexOf("public record " + simple + "(");
        int end = src.indexOf(") {", start);
        for (String line : src.substring(start, end + 1).split("\n")) {
            String t = line.trim();
            if (t.startsWith("// ---")) { sec = t.substring(6).trim(); last = null; continue; }
            if (t.startsWith("//") && last != null) { doc.merge(last, t.substring(2).trim(), (a, b) -> a + " " + b); continue; }
            Matcher m = comp.matcher(line);
            if (m.matches() && !t.startsWith("public")) {
                last = m.group(1);
                section.put(last, sec);
                if (m.group(2) != null) doc.put(last, m.group(2).trim());
            }
        }

        StringBuilder j = new StringBuilder();
        j.append("package org.erisia.stasis.towerweb;\n\n")
         .append("import java.util.List;\nimport java.util.Map;\nimport org.erisia.stasis.towergen.Json;\nimport ")
         .append(cls.getName()).append(";\n\n")
         .append("/** Generated by GenCodec from the ").append(simple).append(" record; do not edit. */\n")
         .append("final class ").append(name).append(" {\n    private ").append(name).append("() {}\n\n")
         .append("    static final String[] KEYS = {");
        for (int i = 0; i < rc.length; i++) j.append(i > 0 ? ", " : "").append('"').append(rc[i].getName()).append('"');
        j.append("};\n\n");
        j.append("    /** Defaults overridden by the keys present; type errors name the key. */\n")
         .append("    static ").append(simple).append(" fromJson(Map<String, Object> m").append(extraParam).append(") {\n        ")
         .append(simple).append(" d = ").append(defaultsExpr).append(";\n")
         .append("        return new ").append(simple).append("(\n");
        for (int i = 0; i < rc.length; i++) {
            String n = rc[i].getName();
            Class<?> ty = rc[i].getType();
            String fn = ty == double.class ? "dbl" : ty == int.class ? "integer" : ty == boolean.class ? "bool"
                    : ty == double[].class ? "rgb" : ty == String.class ? "str" : null;
            if (fn == null) throw new IllegalStateException("unsupported type " + ty + " of " + n);
            j.append("                ").append(fn).append("(m, \"").append(n).append("\", d.").append(n).append("())")
             .append(i + 1 < rc.length ? ",\n" : ");\n");
        }
        j.append("    }\n\n")
         .append("    private static Object get(Map<String, Object> m, String k) { return m.get(k); }\n")
         .append("    private static double dbl(Map<String, Object> m, String k, double d) {\n")
         .append("        Object v = get(m, k); if (v == null) return d;\n")
         .append("        if (!(v instanceof Number x)) throw new IllegalArgumentException(k + \": expected a number\");\n")
         .append("        return x.doubleValue();\n    }\n")
         .append("    private static int integer(Map<String, Object> m, String k, int d) {\n")
         .append("        Object v = get(m, k); if (v == null) return d;\n")
         .append("        if (!(v instanceof Number x)) throw new IllegalArgumentException(k + \": expected an integer\");\n")
         .append("        return (int) Math.round(x.doubleValue());\n    }\n")
         .append("    private static boolean bool(Map<String, Object> m, String k, boolean d) {\n")
         .append("        Object v = get(m, k); if (v == null) return d;\n")
         .append("        if (!(v instanceof Boolean x)) throw new IllegalArgumentException(k + \": expected true or false\");\n")
         .append("        return x;\n    }\n")
         .append("    private static String str(Map<String, Object> m, String k, String d) {\n")
         .append("        Object v = get(m, k); if (v == null) return d;\n")
         .append("        if (!(v instanceof String x)) throw new IllegalArgumentException(k + \": expected a string\");\n")
         .append("        return x;\n    }\n")
         .append("    private static double[] rgb(Map<String, Object> m, String k, double[] d) {\n")
         .append("        Object v = get(m, k); if (v == null) return d;\n")
         .append("        if (!(v instanceof List<?> l) || l.size() != 3) throw new IllegalArgumentException(k + \": expected [r, g, b] (0..1)\");\n")
         .append("        double[] a = new double[3];\n")
         .append("        for (int i = 0; i < 3; i++) {\n")
         .append("            if (!(l.get(i) instanceof Number x)) throw new IllegalArgumentException(k + \": expected [r, g, b] (0..1)\");\n")
         .append("            a[i] = x.doubleValue();\n        }\n        return a;\n    }\n}\n");
        Path pk = javaOut.resolve("org/erisia/stasis/towerweb");
        Files.createDirectories(pk);
        Files.writeString(pk.resolve(name + ".java"), j.toString());

        Files.createDirectories(webOut);
        Files.writeString(webOut.resolve(prefix + "-defaults.json"), Json.writePretty(defs));
        List<Object> docs = new ArrayList<>();
        for (RecordComponent r : rc) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("key", r.getName());
            Class<?> ty = r.getType();
            e.put("type", ty == double.class ? "number" : ty == int.class ? "integer" : ty == boolean.class ? "boolean"
                    : ty == String.class ? "string" : "colour [r,g,b]");
            e.put("default", defs.get(r.getName()));
            e.put("section", section.getOrDefault(r.getName(), ""));
            e.put("doc", doc.getOrDefault(r.getName(), extra.getOrDefault(r.getName(), "")));
            docs.add(e);
        }
        Files.writeString(webOut.resolve(prefix + "-doc.json"), Json.writePretty(docs));
        System.out.println("GenCodec: " + simple + " " + rc.length + " keys, " + doc.size() + " documented");
    }
}

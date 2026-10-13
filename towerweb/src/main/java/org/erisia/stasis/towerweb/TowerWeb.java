package org.erisia.stasis.towerweb;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.erisia.stasis.towergen.Json;
import org.erisia.stasis.towergen.Layout;
import org.erisia.stasis.towergen.LayoutGen;
import org.erisia.stasis.towergen.Library;
import org.erisia.stasis.towergen.LibraryBin;
import org.erisia.stasis.towergen.Mesh;
import org.erisia.stasis.towergen.RefusedException;
import org.erisia.stasis.towergen.SolverParams;
import org.erisia.stasis.towergen.WireReduction;
import org.erisia.stasis.towergen.MeshBuilder;
import org.erisia.stasis.towergen.Stitch;
import org.erisia.stasis.towergen.TowerGen;
import org.erisia.stasis.towergen.VisualParams;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Browser facade of towergen (compiled by TeaVM to an ES module; used from the page's Web Worker). Strings in and
 * out (JSON), typed arrays for the mesh of the last build. Errors come back as {"error": "..."}.
 */
public final class TowerWeb {
    private static Library lib;
    private static TowerGen gen;
    private static Mesh last;

    public static void main(String[] args) {}

    /** Library bytes, uncompressed: TGLB1 binary (LibraryBin) or the p3a JSON. */
    @JSExport
    public static String loadLibrary(Int8Array bytes) {
        try {
            long t0 = System.currentTimeMillis();
            byte[] b = bytes.copyToJavaArray();
            lib = LibraryBin.isBinary(b) ? LibraryBin.read(b) : Library.load(new ByteArrayInputStream(b), false);
            gen = new TowerGen(lib);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entries", (double) lib.entries.size());
            m.put("ms", (double) (System.currentTimeMillis() - t0));
            return Json.write(m);
        } catch (Throwable t) {
            return err(t);
        }
    }

    /** Builds a tower; the mesh is then available from positions / normals / parts. */
    @JSExport
    public static String build(String layoutJson, double q, String visualJson, String solverJson) {
        last = null;
        if (gen == null) return err("library not loaded");
        List<String> warnings = new ArrayList<>();
        Layout layout;
        VisualParams vp;
        try {
            layout = parseLayout(layoutJson);
        } catch (Throwable t) {
            return err("layout: " + msg(t));
        }
        try {
            Object tree = visualJson == null || visualJson.isBlank() ? null : Json.parse(visualJson);
            if (tree != null && !(tree instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
            Map<String, Object> m = tree == null ? Map.of() : Json.obj(tree);
            List<String> keys = List.of(VisualParamsCodec.KEYS);
            for (String k : m.keySet()) {
                if (VisualParams.OBSOLETE.contains(k)) warnings.add("visual: obsolete key \"" + k + "\" (ignored; joints are hull hubs now, see jointMargin)");
                else if (!keys.contains(k)) warnings.add("visual: unknown key \"" + k + "\" (ignored)");
            }
            vp = VisualParamsCodec.fromJson(m);
        } catch (Throwable t) {
            return err("visual: " + msg(t));
        }
        SolverParams sp;
        try {
            Object tree = solverJson == null || solverJson.isBlank() ? null : Json.parse(solverJson);
            if (tree != null && !(tree instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
            Map<String, Object> m = tree == null ? Map.of() : Json.obj(tree);
            List<String> keys = List.of(SolverParamsCodec.KEYS);
            for (String k : m.keySet()) if (!keys.contains(k)) warnings.add("solver: unknown key \"" + k + "\" (ignored)");
            sp = SolverParamsCodec.fromJson(m, gen.c);
        } catch (Throwable t) {
            return err("solver: " + msg(t));
        }
        List<String> bad = layout.violations();
        if (!bad.isEmpty()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("error", "layout breaks the game rules: " + String.join("; ", bad));
            return Json.write(e);
        }
        try {
            q = Math.max(0.3, Math.min(1.0, q));
            long t0 = System.nanoTime();
            TowerGen.Result r = gen.build(layout, q, sp);
            long t1 = System.nanoTime();
            Mesh mesh = new MeshBuilder(r.c, vp).build(r);
            long t2 = System.nanoTime();
            last = mesh;
            return stats(r, mesh, vp, warnings, (t1 - t0) / 1e6, (t2 - t1) / 1e6);
        } catch (RefusedException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "REFUSED (the game would not build this): " + e.refusal.message());
            Map<String, Object> rf = new LinkedHashMap<>();
            rf.put("reason", e.refusal.reason().name());
            rf.put("message", e.refusal.message());
            m.put("refusal", rf);
            return Json.write(m);
        } catch (Throwable t) {
            return err("generator: " + msg(t));
        }
    }

    @JSExport
    public static Float32Array positions() { return last == null ? Float32Array.create(0) : Float32Array.copyFromJavaArray(java.util.Arrays.copyOf(last.pos, 9 * last.tris)); }

    /** One normal per triangle. */
    @JSExport
    public static Float32Array normals() { return last == null ? Float32Array.create(0) : Float32Array.copyFromJavaArray(java.util.Arrays.copyOf(last.nrm, 3 * last.tris)); }

    /** Part ordinal per triangle (VisualParams.Part). */
    @JSExport
    public static Int8Array parts() { return last == null ? Int8Array.create(0) : Int8Array.copyFromJavaArray(java.util.Arrays.copyOf(last.part, last.tris)); }

    /** A random layout by the game rules (LayoutGen, the generator's own distribution). */
    @JSExport
    public static String randomLayout(double seed) {
        Random rng = new Random((long) seed);
        Layout l = gen != null ? LayoutGen.randomBuildable(rng, gen.c) : LayoutGen.random(rng);   // no stay refusals
        return prettyLayout(l);
    }

    /** Layout JSON normalised to one boom per line. */
    @JSExport
    public static String formatLayout(String layoutJson) {
        try { return prettyLayout(parseLayout(layoutJson)); } catch (Throwable t) { return err("layout: " + msg(t)); }
    }

    // ------------------------------------------------------------------------------------------------

    static String prettyLayout(Layout l) {
        StringBuilder b = new StringBuilder("{\n  \"H\": ").append(l.H()).append(",\n  \"booms\": [");
        for (int i = 0; i < l.booms().size(); i++) {
            Layout.Boom x = l.booms().get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    [").append(x.y()).append(", \"").append(x.dirChar()).append("\", ").append(x.length()).append(']');
        }
        b.append(l.booms().isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        return b.toString();
    }

    static Layout parseLayout(String s) {
        Object tree = Json.parse(s);
        if (!(tree instanceof Map)) throw new IllegalArgumentException("expected an object {\"H\": .., \"booms\": [..]}");
        Map<String, Object> m = Json.obj(tree);
        if (!(m.get("H") instanceof Number)) throw new IllegalArgumentException("\"H\" must be a number (mast height 4..100)");
        Object b = m.get("booms");
        if (b != null) {
            if (!(b instanceof List<?> l)) throw new IllegalArgumentException("\"booms\" must be a list of [y, \"E|S|W|N\", length]");
            for (int i = 0; i < l.size(); i++) {
                if (!(l.get(i) instanceof List<?> e) || e.size() != 3 || !(e.get(0) instanceof Number) || !(e.get(2) instanceof Number)
                        || !(e.get(1) instanceof String || e.get(1) instanceof Number) || e.get(1) instanceof String s2 && s2.isEmpty())
                    throw new IllegalArgumentException("boom " + (i + 1) + ": expected [y, \"E|S|W|N\", length]");
            }
        }
        for (String k : m.keySet()) if (!k.equals("H") && !k.equals("booms")) throw new IllegalArgumentException("unknown key \"" + k + "\"");
        return Layout.fromJson(tree);
    }

    private static String stats(TowerGen.Result r, Mesh mesh, VisualParams vp, List<String> warnings, double msGen, double msMesh) {
        Stitch.Tower t = r.tower != null ? r.tower : r.raw;
        int struts = 0, laced = 0, cables = 0, bs = 0;
        for (int b = 0; b < t.m; b++) {
            switch (t.kind[b]) {
                case 0 -> struts++;
                case 1 -> cables++;
                case 2 -> { struts++; laced++; }
                default -> bs++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("H", (double) r.layout.H());
        m.put("meshHash", mesh.fnv());
        Map<String, Object> ph = new LinkedHashMap<>();
        for (VisualParams.Part p : VisualParams.Part.values()) ph.put(p.name(), mesh.fnv(p));
        ph.put("STRUTS", mesh.fnv(VisualParams.Part.STRUT_C, VisualParams.Part.STRUT_T));   // geometry, either colour
        m.put("meshHashParts", ph);
        m.put("booms", (double) r.layout.booms().size());
        m.put("q", r.q);
        m.put("nodes", (double) t.n);
        m.put("members", (double) t.m);
        m.put("struts", (double) struts);
        m.put("laced", (double) laced);
        m.put("cables", (double) cables);
        m.put("backstays", (double) bs);
        Map<String, Object> wires = new LinkedHashMap<>();
        wires.put("stitched", counts(WireReduction.Counts.of(r.raw)));
        wires.put("final", counts(WireReduction.Counts.of(t)));
        if (r.reduction != null) {
            Map<String, Object> red = new LinkedHashMap<>();
            red.put("xSingled", (double) r.reduction.xSingled);
            red.put("xRestored", (double) r.reduction.xRestored);
            red.put("bayCablesToStruts", (double) r.reduction.bayConverted);
            red.put("staysRemoved", (double) r.reduction.staysRemoved);
            red.put("staysPutBack", (double) r.reduction.staysReadded);
            red.put("analyses", (double) r.reduction.analyses);
            wires.put("reduction", red);
        }
        m.put("wires", wires);
        m.put("triangles", (double) mesh.tris);
        double util = r.analysis != null ? r.analysis.maxUtil : Double.NaN;
        m.put("util", Double.isNaN(util) ? null : util);
        m.put("rawUtil", r.info.rawUtil);
        m.put("volume", r.info.vol);
        m.put("volumeRaw", r.info.volRaw);
        m.put("rounds", (double) r.info.rounds);
        Map<String, Object> tiers = new TreeMap<>();
        for (String s : r.bayTiers) tiers.merge("bay " + s, 1.0, (a, b) -> (Double) a + (Double) b);
        for (String s : r.boomTiers) tiers.merge("boom " + s, 1.0, (a, b) -> (Double) a + (Double) b);
        m.put("tiers", tiers);
        Map<String, Object> tm = new LinkedHashMap<>();
        tm.put("statics", r.msStatics());
        tm.put("reduce", r.msReduce());
        tm.put("pass", r.msPass());
        tm.put("mesh", msMesh);
        tm.put("total", msGen + msMesh);
        m.put("ms", tm);
        warnings.addAll(r.warnings);
        if (r.analysis == null) warnings.add("the structure is a mechanism (singular stiffness); drawn without force colours");
        if (r.refusal != null) {
            Map<String, Object> rf = new LinkedHashMap<>();
            rf.put("reason", r.refusal.reason().name());
            rf.put("message", r.refusal.message());
            m.put("refusal", rf);
        }
        for (String s : r.bayTiers)
            if (s.startsWith("ring_near") || s.equals("patch")) { warnings.add("some bays use last-resort library tiers (" + s + ")"); break; }
        m.put("warnings", new ArrayList<Object>(warnings));
        double[] bb = mesh.bounds();
        List<Object> bl = new ArrayList<>();
        for (double x : bb) bl.add(x);
        m.put("bounds", bl);
        List<Object> cols = new ArrayList<>(), names = new ArrayList<>(), mats = new ArrayList<>();
        for (VisualParams.Part p : VisualParams.Part.values()) {
            List<Object> c = new ArrayList<>();
            for (double x : vp.colour(p)) c.add(x);
            cols.add(c);
            names.add(p.name());
            mats.add(p.material.name());
        }
        m.put("partColours", cols);
        m.put("partNames", names);
        m.put("partMaterials", mats);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("skyTop", rgb(vp.skyTop()));
        view.put("skyHorizon", rgb(vp.skyHorizon()));
        view.put("groundColour", rgb(vp.groundColour()));
        view.put("sunAzimuth", vp.sunAzimuth());
        view.put("sunElevation", vp.sunElevation());
        view.put("ambient", vp.ambient());
        view.put("fovDeg", vp.fovDeg());
        view.put("drawGround", vp.drawGround());
        view.put("groundGrid", vp.groundGrid());
        m.put("view", view);
        return Json.write(m);
    }

    private static Map<String, Object> counts(WireReduction.Counts c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cables", (double) c.cables());
        m.put("stays", (double) c.stays());
        m.put("stayCables", (double) c.stayCables());
        m.put("backstays", (double) c.backstays());
        return m;
    }

    private static List<Object> rgb(double[] a) {
        List<Object> l = new ArrayList<>();
        for (double x : a) l.add(x);
        return l;
    }

    private static String msg(Throwable t) {
        String s = t.getMessage();
        return s == null || s.isEmpty() ? t.getClass().getSimpleName() : s;
    }

    private static String err(Throwable t) { return err(msg(t)); }

    private static String err(String s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", s);
        return Json.write(m);
    }
}

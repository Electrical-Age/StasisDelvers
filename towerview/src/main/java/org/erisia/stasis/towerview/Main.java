package org.erisia.stasis.towerview;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

import org.erisia.stasis.towergen.Json;
import org.erisia.stasis.towergen.Layout;
import org.erisia.stasis.towergen.Library;
import org.erisia.stasis.towergen.Mesh;
import org.erisia.stasis.towergen.MeshBuilder;
import org.erisia.stasis.towergen.Stitch;
import org.erisia.stasis.towergen.TowerGen;
import org.erisia.stasis.towergen.SolverParams;
import org.erisia.stasis.towergen.VisualParams;
import org.erisia.stasis.towergen.WireReduction;

/**
 * towerview: standalone tower viewer.
 * <pre>
 *   towerview render LAYOUT.json [--q 1.0] [--out DIR] [--shots a215,a300,close,wide,legs] [--orbit N]
 *                    [--size 1280x720] [--ss 2] [--params visual.json] [--solver solver.json] [--name TAG]
 *                    [--lib library.json.gz]
 *   towerview view LAYOUT.json [--q 1.0] [--params visual.json] [--solver solver.json]   interactive window
 *   towerview solver                                                   print the default solver parameters
 *   towerview contact OUT.png [--cols 3] [--width 1920] IMG...         contact sheet
 *   towerview params                                                   print the default visual parameters
 *   towerview info LAYOUT.json [--q 1.0] [--solver solver.json]        keys, lookup tiers, util, wires, timings
 *   towerview obj LAYOUT.json OUT.obj [--q 1.0] [--params visual.json] [--solver solver.json]  Wavefront OBJ
 * </pre>
 * Layout JSON: {"H": 40, "booms": [[10, "E", 16], ...]}.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 0) { usage(); return; }
        Map<String, String> opt = new LinkedHashMap<>();
        List<String> pos = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) opt.put(k, args[++i]); else opt.put(k, "true");
            } else pos.add(args[i]);
        }
        switch (args[0]) {
            case "render" -> render(pos, opt);
            case "view" -> {
                Class.forName("org.erisia.stasis.towerview.Interactive").getMethod("run", List.class, Map.class).invoke(null, pos, opt);
            }
            case "contact" -> contact(pos, opt);
            case "params" -> System.out.print(Json.writePretty(VisualParams.defaults().toJson()));
            case "solver" -> System.out.print(Json.writePretty(SolverParams.defaults(gen(opt).c).toJson()));
            case "info" -> info(pos, opt);
            case "obj" -> {
                TowerGen g = gen(opt);
                TowerGen.Result r = g.build(Layout.read(Path.of(pos.get(0))), Double.parseDouble(opt.getOrDefault("q", "1.0")), solver(g, opt));
                Mesh m = new MeshBuilder(r.c, params(opt)).build(r);
                Files.writeString(Path.of(pos.get(1)), m.toObj());
                System.out.println("wrote " + pos.get(1) + " (" + m.tris + " triangles)");
            }
            default -> usage();
        }
    }

    static void usage() {
        System.out.println("""
                towerview render LAYOUT.json [--q 1.0] [--out DIR] [--shots a215,a300,close] [--orbit N] [--size 1280x720] [--ss 2] [--params visual.json] [--solver solver.json] [--name TAG]
                towerview view LAYOUT.json [--q 1.0] [--params visual.json] [--solver solver.json]
                towerview contact OUT.png [--cols 3] [--width 1920] IMG...
                towerview params
                towerview solver
                towerview info LAYOUT.json [--q 1.0] [--solver solver.json]
                towerview obj LAYOUT.json OUT.obj [--q 1.0] [--solver solver.json]""");
    }

    static TowerGen gen(Map<String, String> opt) throws IOException {
        Library lib = opt.containsKey("lib") ? Library.load(Path.of(opt.get("lib"))) : Library.shared();
        return new TowerGen(lib);
    }

    /** --solver FILE (null = the plain generator). */
    static SolverParams solver(TowerGen g, Map<String, String> opt) throws IOException {
        return opt.containsKey("solver") ? SolverParams.load(Path.of(opt.get("solver")), g.c) : null;
    }

    static VisualParams params(Map<String, String> opt) throws IOException {
        return opt.containsKey("params") ? VisualParams.load(Path.of(opt.get("params"))) : VisualParams.defaults();
    }

    /** Caption as p25shots.caption (member counts are raw members, not merged chains). */
    static String caption(String name, TowerGen.Result r) {
        Stitch.Tower t = r.tower;
        int struts = 0, laced = 0, cables = 0, bs = 0;
        for (int b = 0; b < t.m; b++) {
            switch (t.kind[b]) {
                case 0 -> struts++;
                case 1 -> cables++;
                case 2 -> { struts++; laced++; }
                default -> bs++;
            }
        }
        return String.format("%s q%.1f: %d struts (%d laced) %d cables, vol %.0f, util %.2f->%.2f%s  [statics %.0f ms, pass %.0f ms]",
                name, r.q, struts, laced, cables, r.info.vol, r.info.rawUtil, r.analysis.maxUtil,
                bs > 0 ? ", " + bs + " back-stays" : "", r.msStatics(), r.msPass());
    }

    /** Camera presets (p2shots.cams / p25shots.cams in tower coordinates: ground y = 0, core centre x = z = 0). */
    static Map<String, Raster.Camera> cams(TowerGen.Result r, double fov) {
        Stitch.Tower t = r.tower;
        int H = r.layout.H();
        java.util.Set<Integer> g = new java.util.HashSet<>();
        for (int x : t.ground) g.add(x);
        double maxAbs = 0, minx = 1e30, maxx = -1e30, minz = 1e30, maxz = -1e30, extAll = 0;
        for (int i = 0; i < t.n; i++) {
            double x = t.P[3 * i], z = t.P[3 * i + 2];
            extAll = Math.max(extAll, Math.max(Math.abs(x), Math.abs(z)));
            if (g.contains(i)) continue;
            maxAbs = Math.max(maxAbs, Math.max(Math.abs(x), Math.abs(z)));
            minx = Math.min(minx, x); maxx = Math.max(maxx, x); minz = Math.min(minz, z); maxz = Math.max(maxz, z);
        }
        double span = Math.max(H, 2 * maxAbs);
        double d = Math.max(20.0, span);
        double[] tgt = {0.5 * (maxx + minx), 0.5 * H, 0.5 * (maxz + minz)};
        Map<String, Raster.Camera> out = new LinkedHashMap<>();
        for (int a : new int[]{215, 300}) {
            double rad = Math.toRadians(a);
            out.put("a" + a, new Raster.Camera(new double[]{tgt[0] + d * Math.cos(rad), 0.62 * H + 4, tgt[2] + d * Math.sin(rad)}, tgt, fov));
        }
        double yb = !r.layout.booms().isEmpty() ? r.layout.booms().get(0).y() : Math.min(12, H * 0.3);
        double rc = Math.toRadians(235);
        out.put("close", new Raster.Camera(new double[]{12 * Math.cos(rc), yb + 3, 12 * Math.sin(rc)}, new double[]{1, yb, 0}, fov));
        if (t.ground.length > 0) {
            double dd = Math.max(H, 2 * extAll) * 1.05;
            double rw = Math.toRadians(250);
            out.put("wide", new Raster.Camera(new double[]{dd * Math.cos(rw), 0.55 * H + 4, dd * Math.sin(rw)}, new double[]{0, 0.42 * H, 0}, fov));
        }
        if (H >= 40) {
            double ym = 0.3 * H;
            double rl = Math.toRadians(215);
            out.put("legs", new Raster.Camera(new double[]{9 * Math.cos(rl), ym + 2, 9 * Math.sin(rl)}, new double[]{0, ym + 1, 0}, fov));
        }
        return out;
    }

    static void render(List<String> pos, Map<String, String> opt) throws Exception {
        Path lp = Path.of(pos.get(0));
        Layout layout = Layout.read(lp);
        double q = Double.parseDouble(opt.getOrDefault("q", "1.0"));
        String name = opt.getOrDefault("name", lp.getFileName().toString().replaceFirst("\\.json$", ""));
        Path out = Path.of(opt.getOrDefault("out", "."));
        Files.createDirectories(out);
        String[] wh = opt.getOrDefault("size", "1280x720").split("x");
        int W = Integer.parseInt(wh[0]), H = Integer.parseInt(wh[1]);
        int ss = Integer.parseInt(opt.getOrDefault("ss", "2"));
        VisualParams vp = params(opt);
        TowerGen gen = gen(opt);
        TowerGen.Result r = gen.build(layout, q, solver(gen, opt));
        long m0 = System.nanoTime();
        Mesh mesh = new MeshBuilder(r.c, vp).build(r);
        double msMesh = (System.nanoTime() - m0) / 1e6;
        System.out.printf("%s q%.1f: %d nodes %d members, util %.3f, slack %.4f, vol %.1f -> %.1f, rounds %d; statics %.1f ms, pass %.1f ms, mesh %.1f ms (%d triangles)%n",
                name, q, r.tower.n, r.tower.m, r.analysis.maxUtil, r.analysis.minSlack, r.info.volRaw, r.info.vol, r.info.rounds,
                r.msStatics(), r.msPass(), msMesh, mesh.tris);
        printWires(r);
        String cap = caption(name, r);
        Raster ras = new Raster(W, H, ss, vp);
        Map<String, Raster.Camera> cams = cams(r, vp.fovDeg());
        String shots = opt.getOrDefault("shots", opt.containsKey("orbit") ? "" : String.join(",", cams.keySet()));
        String tag = name + "_q" + q;
        for (String s : shots.split(",")) {
            if (s.isBlank()) continue;
            Raster.Camera cam = cams.get(s);
            if (cam == null) { System.out.println("no camera " + s + " (have " + cams.keySet() + ")"); continue; }
            long t0 = System.nanoTime();
            BufferedImage img = ras.render(mesh, cam, s.startsWith("a") || s.equals("wide") ? cap : name + " q" + q + " " + s);
            File f = out.resolve("tv_" + tag + "_" + s + ".png").toFile();
            ImageIO.write(img, "png", f);
            System.out.printf("  %s (%.0f ms)%n", f, (System.nanoTime() - t0) / 1e6);
        }
        if (opt.containsKey("orbit")) {
            int n = Integer.parseInt(opt.get("orbit"));
            Raster.Camera a = cams.get("a215");
            double[] tg = a.target();
            double dx = a.eye()[0] - tg[0], dz = a.eye()[2] - tg[2];
            double rad = Math.hypot(dx, dz), a0 = Math.atan2(dz, dx);
            Path od = out.resolve("orbit_" + tag);
            Files.createDirectories(od);
            for (int i = 0; i < n; i++) {
                double an = a0 + 2 * Math.PI * i / n;
                Raster.Camera c = new Raster.Camera(new double[]{tg[0] + rad * Math.cos(an), a.eye()[1], tg[2] + rad * Math.sin(an)}, tg, a.fovDeg());
                ImageIO.write(ras.render(mesh, c, cap), "png", od.resolve(String.format("frame_%03d.png", i)).toFile());
            }
            System.out.println("  " + n + " orbit frames in " + od);
        }
    }

    static void info(List<String> pos, Map<String, String> opt) throws IOException {
        Layout layout = Layout.read(Path.of(pos.get(0)));
        double q = Double.parseDouble(opt.getOrDefault("q", "1.0"));
        TowerGen g = gen(opt);
        TowerGen.Result r0 = g.stitchOnly(layout, q);
        System.out.println("layout " + layout + (layout.violations().isEmpty() ? "" : "  RULE VIOLATIONS " + layout.violations()));
        TowerGen.Result r;
        try {
            r = g.build(layout, q, solver(g, opt));
        } catch (RuntimeException ex) {
            for (var s : r0.spine.specs)
                System.out.printf("  bay y%3d-%3d r%d %-14s kk%d key %s%n", s.y0, s.y1, s.r, s.tier, s.kk, java.util.Arrays.toString(s.seamKey));
            System.out.println("  runtime pass failed: " + ex);
            return;
        }
        for (var s : r.spine.specs)
            System.out.printf("  bay y%3d-%3d r%d %-14s kk%d key %s%n", s.y0, s.y1, s.r, s.tier, s.kk, java.util.Arrays.toString(s.seamKey));
        for (var b : r.spine.booms)
            System.out.printf("  boom %c y%d L%d ya%d key %s %s, back-stays %d (T %.1f, D %.1f)%n", "ESWN".charAt(b.dir), b.yb, b.L, b.ya,
                    java.util.Arrays.toString(b.ikey), b.tier, b.wires.size(), b.bs.T, b.bs.D);
        System.out.printf("  util %.4f (raw %.3f) per case %s%n  min taut margin %.4f, vol %.2f -> %.2f, rounds %d, converted %d, leg fixes %d%n  statics+lookup+stitch %.1f ms, runtime pass %.1f ms%n",
                r.analysis.maxUtil, r.info.rawUtil, java.util.Arrays.toString(r.analysis.utilPerCase), r.analysis.minSlack,
                r.info.volRaw, r.info.vol, r.info.rounds, r.info.converted, r.info.legFix, r.msStatics(), r.msPass());
        printWires(r);
    }

    static void printWires(TowerGen.Result r) {
        System.out.println("  wires: " + WireReduction.Counts.of(r.tower) + (r.reduced != r.raw || r.reduction != null
                ? "  (stitched: " + WireReduction.Counts.of(r.raw) + "; reduction " + String.format("%.1f", r.msReduce()) + " ms)" : ""));
        for (String w : r.warnings) System.out.println("  warning: " + w);
    }

    static void contact(List<String> pos, Map<String, String> opt) throws IOException {
        String outFile = pos.get(0);
        int cols = Integer.parseInt(opt.getOrDefault("cols", "3"));
        int width = Integer.parseInt(opt.getOrDefault("width", "1920"));
        List<BufferedImage> imgs = new ArrayList<>();
        for (String p : pos.subList(1, pos.size())) imgs.add(ImageIO.read(new File(p)));
        int cw = width / cols;
        int ch = (int) Math.round(cw * imgs.get(0).getHeight() / (double) imgs.get(0).getWidth());
        int rows = (imgs.size() + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cw * cols, ch * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        for (int i = 0; i < imgs.size(); i++) g.drawImage(imgs.get(i), (i % cols) * cw, (i / cols) * ch, cw, ch, null);
        g.dispose();
        ImageIO.write(sheet, "png", new File(outFile));
        System.out.println("wrote " + outFile + " " + sheet.getWidth() + "x" + sheet.getHeight());
    }
}

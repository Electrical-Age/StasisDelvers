package org.erisia.stasis.towergen;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every visual proportion of the tower mesh (and the viewer's sky / sun), loaded from a JSON file whose keys are the
 * component names; missing keys keep their defaults, unknown keys are ignored. Colours are [r, g, b] in 0..1. Edit
 * the file while towerview runs: it is re-read on save. {@code towerview params > visual.json} writes the defaults.
 */
public record VisualParams(
        // --- solid struts, pole, cables
        double strutMinWidth,        // drawn width floor of solid struts
        double poleMinWidth,         // drawn width floor of pole segments
        double cableDrawMin,         // drawn width floor of cables (cables are thin boxes)
        double cableDrawScale,       // drawn cable width = max(min, width * scale)
        double backstayDrawMin,      // drawn width floor of back-stay wire rope
        // --- laced (built-up) members: chords at the section corners + zigzag lacing + end battens
        double lacingWidthFrac,      // lacing bar width = max(lacingMinWidth, frac * chord)
        double lacingMinWidth,
        double battenScale,          // end batten width = lacing width * this
        double lacingPanelPerWidth,  // lacing panel length = outer width * this
        int lacingPanelsMin,
        int lacingPanelsMax,
        double lacedEndTaper,        // fraction of the length over which the chords converge at a free end (not at a
                                     // node where a collinear member continues, e.g. along a leg)
        double lacedEndFrac,         // chord offset at the very end, as a fraction of the full offset
        // --- tapered joints (knuckles)
        double knuckleScale,         // node block size = scale * (largest connected drawn width) + add
        double knuckleAdd,
        double knuckleMaxRatio,      // node block at most this x the second-largest connected width (taper, not blob)
        boolean knuckleStubs,        // tapered stubs from the node block into each strut
        double knuckleTaperLength,   // stub length = this * node size
        double knuckleTaperStart,    // stub start width = this * node size (it tapers to the member width)
        double poleCollarScale,      // pole collar width = scale * pole width + add (brass)
        double poleCollarAdd,
        double poleCollarHeight,
        // --- footings, back-stay deadmen, core, emitters
        double footingScale,         // footing width = max(min, scale * connected width)
        double footingMin,
        double footingHeight,
        double deadmanSize,
        double deadmanHeight,
        double turnbuckleWidth,
        double turnbuckleLength,
        double emitterScale,
        boolean drawCore,
        double coreWidth,
        double coreHeight,
        // --- colours (parts; each maps to a material id for the game)
        double[] colRust,            // struts whose largest force is compression
        double[] colSteel,           // struts whose largest force is tension
        double[] colPole,
        double[] colLacing,
        double[] colKnuckle,
        double[] colCable,
        double[] colBackstay,
        double[] colPorcelain,
        double[] colGlow,
        double[] colBrass,
        double[] colFooting,
        double[] colDeadman,
        double[] colCore,
        // --- viewer (headless renderer and window)
        double[] skyTop,
        double[] skyHorizon,
        double[] groundColour,
        double sunAzimuth,           // degrees, 0 = +x, 90 = +z
        double sunElevation,         // degrees
        double ambient,
        double fovDeg,
        boolean drawGround,
        double groundGrid            // grid line spacing in blocks (0 = none)
) {
    /** Mesh parts (per triangle) and their material ids. */
    public enum Part {
        STRUT_C(Material.RUST), STRUT_T(Material.STEEL), POLE(Material.RUST), LACING(Material.STEEL),
        KNUCKLE(Material.STEEL), CABLE(Material.CABLE), BACKSTAY(Material.CABLE), PORCELAIN(Material.PORCELAIN),
        GLOW(Material.GLOW), BRASS(Material.BRASS), FOOTING(Material.CONCRETE), DEADMAN(Material.CONCRETE),
        CORE(Material.CONCRETE);
        public final Material material;
        Part(Material m) { this.material = m; }
    }

    public enum Material { RUST, STEEL, CABLE, PORCELAIN, GLOW, BRASS, CONCRETE }

    public double[] colour(Part p) {
        return switch (p) {
            case STRUT_C -> colRust; case STRUT_T -> colSteel; case POLE -> colPole; case LACING -> colLacing;
            case KNUCKLE -> colKnuckle; case CABLE -> colCable; case BACKSTAY -> colBackstay;
            case PORCELAIN -> colPorcelain; case GLOW -> colGlow; case BRASS -> colBrass; case FOOTING -> colFooting;
            case DEADMAN -> colDeadman; case CORE -> colCore;
        };
    }

    public static VisualParams defaults() {
        return new VisualParams(
                0.05, 0.30, 0.06, 1.0, 0.09,
                0.4, 0.035, 1.3, 1.0, 2, 14, 0.15, 0.2,
                1.05, 0.02, 1.3, true, 1.2, 0.9, 1.4, 0.04, 0.24,
                1.6, 0.5, 0.30, 1.0, 0.65, 0.18, 0.40, 1.0, true, 3.0, 0.9,
                c(0.40, 0.22, 0.13), c(0.48, 0.47, 0.45), c(0.30, 0.20, 0.14), c(0.55, 0.52, 0.47),
                c(0.16, 0.15, 0.14), c(0.86, 0.84, 0.76), c(0.70, 0.66, 0.58), c(0.86, 0.84, 0.78),
                c(0.55, 0.90, 1.00), c(0.70, 0.55, 0.25), c(0.35, 0.34, 0.32), c(0.45, 0.44, 0.41),
                c(0.20, 0.19, 0.18),
                c(0.36, 0.52, 0.78), c(0.80, 0.84, 0.88), c(0.42, 0.45, 0.36), 215, 48, 0.38, 60, true, 4.0);
    }

    private static double[] c(double r, double g, double b) { return new double[]{r, g, b}; }

    /** Defaults overridden by the keys present in the JSON tree. */
    public static VisualParams fromJson(Object tree) {
        Map<String, Object> m = tree == null ? Map.of() : Json.obj(tree);
        VisualParams d = defaults();
        RecordComponent[] rc = VisualParams.class.getRecordComponents();
        Object[] args = new Object[rc.length];
        Class<?>[] types = new Class<?>[rc.length];
        try {
            for (int i = 0; i < rc.length; i++) {
                types[i] = rc[i].getType();
                Object def = rc[i].getAccessor().invoke(d);
                Object v = m.get(rc[i].getName());
                if (v == null) { args[i] = def; continue; }
                if (types[i] == double.class) args[i] = Json.num(v);
                else if (types[i] == int.class) args[i] = Json.inum(v);
                else if (types[i] == boolean.class) args[i] = (Boolean) v;
                else if (types[i] == double[].class) args[i] = Json.darr(v);
                else args[i] = def;
            }
            Constructor<VisualParams> k = VisualParams.class.getDeclaredConstructor(types);
            return k.newInstance(args);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalArgumentException("bad visual parameter file: " + e, e);
        }
    }

    public static VisualParams load(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return defaults();
        return fromJson(Json.parse(Files.readString(p)));
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            for (RecordComponent rc : VisualParams.class.getRecordComponents()) {
                Object v = rc.getAccessor().invoke(this);
                if (v instanceof double[] a) {
                    List<Object> l = new java.util.ArrayList<>();
                    for (double x : a) l.add(x);
                    v = l;
                } else if (v instanceof Integer i) v = (double) i;
                m.put(rc.getName(), v);
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        return m;
    }
}

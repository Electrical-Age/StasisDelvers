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
 * Runtime solver knobs (solver.json): wire reduction applied to the stitched tower, back-stay constants, pretension,
 * sizing-loop limits, load magnitudes of the runtime check and the member catalogue. The library entries stay as
 * solved offline. {@link #defaults(Consts)} reproduces the plain generator exactly (the goldens hold). Missing keys
 * keep their defaults; the defaults of the library constants come from the library header.
 * {@code towerview solver > solver.json} writes the defaults.
 */
public record SolverParams(
        // --- wire reduction (on the stitched tower, before the runtime pass)
        int maxStaysPerBoom,         // keep at most this many stays per boom (stays = the fan members from the mast
                                     // anchor node(s) to the boom, cable or strut), the ones with the largest force in a
                                     // first analysis of the stitched tower; 0 = keep all. Stays the boom needs to stand
                                     // are put back one by one (warning), so a flat boom keeps about one per node
        int minStaysPerBoom,         // never keep fewer stays per boom than this (1..2)
        String xPanels,              // crossing cable pairs (X panels) in mast bays: "keep"; "single" = one strut
                                     // diagonal instead of the pair (alternating hand per bay); "auto" = single only
                                     // where the smallest strut carries twice the pair's largest force
        int maxCablesPerBay,         // at most this many cables per mast bay (after xPanels); the lowest-force
                                     // others become struts; 0 = no limit
        // --- back-stays (spine statics: these change which library entries are picked)
        boolean backstays,           // ground back-stays for long booms
        double backstayMinBoom,      // booms at least this long (blocks) get back-stays (BS_MIN_L)
        double backstayDistFactor,   // ground anchor distance from the mast = factor * attachment height ... (D_FRAC)
        double backstayDistCap,      // ... capped at this (blocks) (D_CAP)
        double backstayMinTension,   // back-stays whose tension comes out below this are dropped (BS_T_MIN)
        double backstaySecondPair,   // above this tension a second wire pair is added one ring higher (BS_CAP)
        // --- boom stays (spine statics: these change the boom keys, so which library entries are picked)
        String stayCap,              // where a boom's stays may anchor on the mast: "same" = at or below the next boom
                                     // level above in the same direction (the only booms its stays could cross);
                                     // "any" = at or below the next boom level above in any direction; "none" = no
                                     // cap (the old rule: stays may pass through booms above). A layout whose stays
                                     // can't get stayMinRise / stayMinRatio under the cap is refused (STAY_RISE)
        double stayRise,             // preferred stay anchor height above the boom = this x boom length (at least one
                                     // bay; rounded up to the next ring level, then capped by stayCap)
        double stayMinRatio,         // refuse if the capped anchor rise is below this x boom length (0.35 = the
                                     // flattest stay angle the boom library is solved for, about 19 degrees)
        double stayMinRise,          // ... or below this many blocks
        // --- pretension and the sizing loop (runtime pass)
        double pretensionFactor,     // cable pretension = recorded library pretension x this, then raised as needed
        double pretensionMargin,     // raise target = this x the largest compression a cable would see (PRE_MARGIN)
        int raiseIters,              // most pretension raise iterations per analysis
        int maxRounds,               // most resize rounds (analysis + widening) per widening pass
        double utilLimit,            // members are widened until utilisation <= this (1 = full capacity)
        // --- loads of the runtime check (sizes only; the library choice uses the library's own loads)
        double emitterWeight,        // weight of each emitter (EMIT_W)
        double emitterWind,          // wind force on each emitter (EMIT_WIND0)
        double windDrag,             // wind drag per block of member length per block of width (CD0)
        double torsion,              // torsion load at an emitter off the mast axis (TORQ0)
        double density,              // self-weight per block^3 of member volume (RHO; laced members x 1.25)
        // --- member catalogue
        int extraWidthSteps          // outer widths added to the end of the strut / laced ladder (x1.2 each, from
                                     // 3.31 blocks), so over-catalogue towers can try bigger members
) {
    public static final List<String> X_PANELS = List.of("keep", "single", "auto");
    public static final List<String> STAY_CAPS = Consts.STAY_CAP_NAMES;

    public SolverParams {
        if (xPanels == null || !X_PANELS.contains(xPanels))
            throw new IllegalArgumentException("xPanels: expected \"keep\", \"single\" or \"auto\", got \"" + xPanels + "\"");
        if (stayCap == null || !STAY_CAPS.contains(stayCap))
            throw new IllegalArgumentException("stayCap: expected \"same\", \"any\" or \"none\", got \"" + stayCap + "\"");
        if (!(stayRise > 0 && stayRise <= 10)) throw new IllegalArgumentException("stayRise: expected 0..10");
        if (!(stayMinRatio >= 0 && stayMinRatio <= 10)) throw new IllegalArgumentException("stayMinRatio: expected 0..10");
        if (!(stayMinRise >= 0 && stayMinRise <= 100)) throw new IllegalArgumentException("stayMinRise: expected 0..100");
        if (maxStaysPerBoom < 0) throw new IllegalArgumentException("maxStaysPerBoom: expected >= 0");
        if (minStaysPerBoom < 0) throw new IllegalArgumentException("minStaysPerBoom: expected >= 0");
        if (maxCablesPerBay < 0) throw new IllegalArgumentException("maxCablesPerBay: expected >= 0");
        if (!(utilLimit > 0.05 && utilLimit <= 10)) throw new IllegalArgumentException("utilLimit: expected 0.05..10");
        if (!(pretensionFactor >= 0)) throw new IllegalArgumentException("pretensionFactor: expected >= 0");
        if (!(pretensionMargin >= 1)) throw new IllegalArgumentException("pretensionMargin: expected >= 1");
        if (raiseIters < 0 || raiseIters > 1000) throw new IllegalArgumentException("raiseIters: expected 0..1000");
        if (maxRounds < 1 || maxRounds > 200) throw new IllegalArgumentException("maxRounds: expected 1..200");
        if (extraWidthSteps < 0 || extraWidthSteps > 12) throw new IllegalArgumentException("extraWidthSteps: expected 0..12");
        if (!(density >= 0) || !(emitterWeight >= 0) || !(windDrag >= 0))
            throw new IllegalArgumentException("density, emitterWeight, windDrag: expected >= 0");
    }

    /** The plain generator's behaviour for library constants c. */
    public static SolverParams defaults(Consts c) {
        return new SolverParams(0, 2, "keep", 0,
                true, c.BS_MIN_L, c.D_FRAC, c.D_CAP, c.BS_T_MIN, c.BS_CAP,
                Consts.STAY_CAP_NAMES.get(c.STAY_CAP), c.STAY_RISE, c.STAY_MIN_RATIO, c.STAY_MIN_RISE,
                1.5, c.PRE_MARGIN, 60, 30, 1.0,
                c.EMIT_W, c.EMIT_WIND0, c.CD0, c.TORQ0, c.RHO,
                0);
    }

    /** A copy with other wire-reduction keys. */
    public SolverParams withWires(int maxStays, String x, int maxCables) {
        return new SolverParams(maxStays, minStaysPerBoom, x, maxCables, backstays, backstayMinBoom, backstayDistFactor,
                backstayDistCap, backstayMinTension, backstaySecondPair, stayCap, stayRise, stayMinRatio, stayMinRise,
                pretensionFactor, pretensionMargin, raiseIters,
                maxRounds, utilLimit, emitterWeight, emitterWind, windDrag, torsion, density, extraWidthSteps);
    }

    /** True if the wire reduction step changes anything. */
    public boolean reducesWires() { return maxStaysPerBoom > 0 || !xPanels.equals("keep") || maxCablesPerBay > 0; }

    /** Defaults overridden by the keys present in the JSON tree (JVM only: uses record reflection). */
    public static SolverParams fromJson(Object tree, Consts c) {
        Map<String, Object> m = tree == null ? Map.of() : Json.obj(tree);
        SolverParams d = defaults(c);
        RecordComponent[] rc = SolverParams.class.getRecordComponents();
        Object[] args = new Object[rc.length];
        Class<?>[] types = new Class<?>[rc.length];
        try {
            for (int i = 0; i < rc.length; i++) {
                types[i] = rc[i].getType();
                Object def = rc[i].getAccessor().invoke(d);
                Object v = m.get(rc[i].getName());
                String k = rc[i].getName();
                if (v == null) { args[i] = def; continue; }
                if (types[i] == double.class) { if (!(v instanceof Number)) throw new IllegalArgumentException(k + ": expected a number"); args[i] = Json.num(v); }
                else if (types[i] == int.class) { if (!(v instanceof Number)) throw new IllegalArgumentException(k + ": expected an integer"); args[i] = Json.inum(v); }
                else if (types[i] == boolean.class) { if (!(v instanceof Boolean)) throw new IllegalArgumentException(k + ": expected true or false"); args[i] = v; }
                else if (types[i] == String.class) { if (!(v instanceof String)) throw new IllegalArgumentException(k + ": expected a string"); args[i] = v; }
                else args[i] = def;
            }
            Constructor<SolverParams> k = SolverParams.class.getDeclaredConstructor(types);
            return k.newInstance(args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable t = e.getCause();
            throw t instanceof IllegalArgumentException ia ? ia : new IllegalArgumentException("bad solver parameter file: " + t, t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("bad solver parameter file: " + e, e);
        }
    }

    public static SolverParams load(Path p, Consts c) throws IOException {
        if (p == null || !Files.exists(p)) return defaults(c);
        return fromJson(Json.parse(Files.readString(p)), c);
    }

    /** Keys of the JSON file (JVM only). */
    public static List<String> keys() {
        List<String> l = new java.util.ArrayList<>();
        for (RecordComponent rc : SolverParams.class.getRecordComponents()) l.add(rc.getName());
        return l;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            for (RecordComponent rc : SolverParams.class.getRecordComponents()) {
                Object v = rc.getAccessor().invoke(this);
                if (v instanceof Integer i) v = (double) i;
                m.put(rc.getName(), v);
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        return m;
    }
}

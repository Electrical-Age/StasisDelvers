package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import org.erisia.stasis.ClientFields;
import org.erisia.stasis.Field;
import org.erisia.stasis.Stasis;
import org.erisia.stasis.StasisDelvers;
import org.erisia.stasis.StasisNet;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The desaturation of stopped land and the freeze warning: a post effect (assets/stasisdelvers/post_effect/stasis.json)
 * that reads the depth buffer. It learns where the fields are from a 1024x4 texture of packed floats (slot i at
 * (i % 1024, i / 1024)), refreshed every frame (the layout is in shaders/post/stasis.fsh; its size also in
 * post_effect/stasis.json). Requested through the player's active post effects while the
 * player is in a dimension where time stops.
 */
public final class StasisPost {
    private StasisPost() {
    }

    public static final Identifier EFFECT = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "stasis");
    /** Post chains look textures up at textures/effect/&lt;path&gt;.png. */
    static final Identifier DATA = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "textures/effect/fielddata.png");
    static DynamicTexture texture;
    /** Data texture size: 1024 x 4 (GL 3.3 only promises 1024-wide textures). */
    static final int WIDTH = 1024, ROWS = 4, SLOTS = WIDTH * ROWS;
    /** Drifting deeps: at most MAX_DEEPS (stasis.fsh MAX_DEEPS), DEEP_STRIDE slots each from DEEP_BASE. */
    static final int DEEP_BASE = 1024, DEEP_STRIDE = 12, MAX_DEEPS = 24;
    /** Ground grids (G x G surface heights) of the drifting deeps (GROUND_BASE + G*G i) and the static deeps (STATIC_GROUND_BASE + G*G i). */
    static final int G = 8, GROUND_BASE = 2048, STATIC_GROUND_BASE = GROUND_BASE + G * G * MAX_DEEPS;
    /** A deep's ground grid, absolute surface y (grid()). */
    record Grid(double ox, double oz, double step, float[] h, float min, float max, boolean complete, long made) {
        /** The bilinear ground at (x, z), clamped to the grid (stasis.fsh groundAt). */
        double at(double x, double z) {
            double lx = Math.max(0, Math.min(G - 1 - 1e-3, (x - ox) / step)), lz = Math.max(0, Math.min(G - 1 - 1e-3, (z - oz) / step));
            int i = (int) lx, j = (int) lz;
            double fx = lx - i, fz = lz - j;
            double a = h[j * G + i] * (1 - fx) + h[j * G + i + 1] * fx, b = h[(j + 1) * G + i] * (1 - fx) + h[(j + 1) * G + i + 1] * fx;
            return a * (1 - fz) + b * fz;
        }
    }
    static final java.util.Map<Integer, Grid> grids = new java.util.HashMap<>();
    static final int GRID_TICKS = 10;
    /** A drifting deep's ground lattice spacing: G - 1 steps cover radius + 6 (at most ~54) on both sides plus a step of slack. */
    static final double MOVING_STEP = 19;

    /** On the render thread (texture creation asserts it): the first frame. */
    static void init() {
        texture = new DynamicTexture(() -> "stasisdelvers field data", WIDTH, ROWS, true);
        Minecraft.getInstance().getTextureManager().register(DATA, texture);
        StrataMask.init();
    }

    static void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        List<Identifier> effects = mc.player.getActivePostEffects();
        // only once the data texture exists: the chain binds it when it loads
        boolean want = texture != null && Stasis.stops(mc.level);
        if (want != effects.contains(EFFECT)) {
            List<Identifier> l = new ArrayList<>(effects);
            if (want) l.add(EFFECT);
            else l.remove(EFFECT);
            mc.player.setActivePostEffects(l);
        }
    }

    static void frame(FrameGraphSetupEvent e) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (texture == null) init();
        var camState = e.getCameraState();
        float[] d = new float[SLOTS];
        Matrix4f vp = new Matrix4f(camState.projectionMatrix).mul(camState.viewRotationMatrix).invert();
        vp.get(d, 0);   // column-major
        var cam = camState.pos;
        double fx = Math.floor(cam.x), fy = Math.floor(cam.y), fz = Math.floor(cam.z);
        d[16] = (float) fx;
        d[17] = (float) fy;
        d[18] = (float) fz;
        d[19] = (float) (cam.x - fx);
        d[20] = (float) (cam.y - fy);
        d[21] = (float) (cam.z - fz);
        d[22] = Stasis.stops(mc.level) ? 1 : 0;
        d[23] = StasisNet.clientFreeze;
        // all time-driven visuals on the smoothed clock: in a slow field the client ticks slowly and the server's time
        // packet steps its game time every second (AdventurAgent: the whole screen stepped once a second)
        double clock = weatherClock(mc);
        // the drifting weather's clock, warped by storms (Storms.tau: up to 10x); pockets and the eye keep real time
        double wclock = org.erisia.stasis.Storms.tau(ClientFields.deepSeed(), org.erisia.stasis.Storms.clientOverrideForClient(), clock);
        debugFrame++;
        d[29] = (float) ((clock % 1200.0));
        // eddy time (Eddies.time), with the partial tick so the mist drifts smoothly
        d[30] = (float) (clock % (1048576 / 20.0));
        int[] spawn = Stasis.spawnArea(mc.level);
        if (spawn != null) {
            d[25] = spawn[0];
            d[26] = spawn[1];
            d[27] = spawn[2];
            d[28] = spawn[3];
        } else {
            d[25] = 1;
            d[27] = 0;   // empty
        }
        int n = 0;
        for (Field f : ClientFields.fields()) {
            if (n == 13) break;
            int o = 32 + 10 * n++;
            d[o] = (float) (f.x() - fx);
            d[o + 1] = (float) (f.y() - fy);
            d[o + 2] = (float) (f.z() - fz);
            d[o + 3] = (float) shownRadius(f);
            d[o + 4] = (float) f.collapse();
            d[o + 5] = (float) f.failing();
            d[o + 6] = f.column() ? 1 : 0;
            d[o + 7] = f.seed();
            d[o + 8] = f.formedAt() > 0
                ? (mc.level.getGameTime() - f.formedAt() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20f : -1;
        }
        d[24] = n;
        // the client's render distance in blocks (terrain ends, and vanilla's render-distance fog with it, there)
        float view = mc.options.getEffectiveRenderDistance() * 16f;
        d[216] = view;
        // drifting deeps within the render distance (Deeps), nearest first: centre - floor(camera) (x, z), radius,
        // strength, local surface y - floor(camera y), edge-noise id; at most MAX_DEEPS
        int nd = 0;
        if (Stasis.stops(mc.level)) {
            double t = wclock;
            List<org.erisia.stasis.Deeps.Deep> deeps = new ArrayList<>();
            List<Double> gaps = new ArrayList<>();
            for (var dp : org.erisia.stasis.Deeps.within((float) cam.x, (float) cam.z, view + 50, t, ClientFields.deepSeed())) {
                // the shader's cylinder is radius + 6 (ragged edge); past the render distance it's fogged away
                double gap = Math.hypot(dp.x() - cam.x, dp.z() - cam.z) - dp.radius();
                if (gap - 6 > view || dp.strength() <= 0) continue;
                deeps.add(dp);
                gaps.add(gap);
            }
            Integer[] order = new Integer[deeps.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(gaps::get));
            for (int i : order) {
                if (nd == MAX_DEEPS) break;
                var dp = deeps.get(i);
                double surface = surface(mc, dp.x(), dp.z(), cam.y - 10);
                Grid g = grid(mc, dp.id(), dp.x(), dp.z(), dp.radius() + 6, (float) surface, true);
                packGrid(d, GROUND_BASE + G * G * nd, g, fy);
                // the deep's surface for the shader: the ground grid at the centre (bilinear on the fixed lattice), not
                // the centre column itself, which hops onto trunks and back as the deep moves (the tint blinked off)
                surface = g.at(dp.x(), dp.z());
                int o = DEEP_BASE + DEEP_STRIDE * nd++;
                d[o + 6] = (float) (g.min() - fy);
                d[o + 7] = (float) (g.max() - fy);
                d[o + 8] = (float) (g.ox() - fx);
                d[o + 9] = (float) (g.oz() - fz);
                d[o + 10] = (float) g.step();
                if (DEBUG_DEEPS) StasisDelvers.LOG.info("deep frame={} clock={} id={} x={} z={} r={} st={} surface={} gmin={} gmax={} complete={}",
                    debugFrame, String.format("%.3f", t), dp.id(), String.format("%.2f", dp.x()), String.format("%.2f", dp.z()),
                    String.format("%.2f", dp.radius()), String.format("%.3f", dp.strength()), surface, g.min(), g.max(), g.complete());
                d[o] = (float) (dp.x() - fx);
                d[o + 1] = (float) (dp.z() - fz);
                d[o + 2] = dp.radius();
                d[o + 3] = dp.strength() + (dp.puff() ? 2 : 0);   // (stasis.fsh: over 1.5 = a puff from a rift)
                d[o + 4] = (float) (surface - fy);
                d[o + 5] = dp.id() & 0xFFFFFF;
            }
        }
        d[31] = nd;
        // the mist's clocks (stasis.fsh mistWind ..), wrapped here in double where each is seamless
        double mt = wclock;
        d[176] = (float) ((0.4 * mt) % 96.0);
        d[177] = (float) ((0.25 * mt) % 96.0);
        d[178] = (float) ((0.02 * mt) % 32.0);
        d[179] = (float) ((0.014 * mt) % 32.0);
        d[180] = (float) ((0.015 * mt) % 32.0);
        d[181] = (float) ((0.0105 * mt) % 32.0);
        d[182] = (float) ((0.39 * mt) % (2 * Math.PI));
        // static deeps near the camera (StaticDeeps): centre - floor(camera), radius, shape id; at most 4
        int ns = 0;
        if (Stasis.stops(mc.level) && ClientFields.deepSeed() != 0) {
            for (var sd : org.erisia.stasis.StaticDeeps.near((float) cam.x, (float) cam.y, (float) cam.z, 260, ClientFields.deepSeed())) {
                if (ns == 4) break;
                double dist = Math.sqrt(Math.pow(sd.x() - cam.x, 2) + Math.pow(sd.y() - cam.y, 2) + Math.pow(sd.z() - cam.z, 2));
                if (dist - sd.radius() * 1.5 > 200) continue;
                // the mist's reach (stasis.fsh STATIC_REACH): the warped, roughened shape stays within ~1.3 r + 8
                Grid g = grid(mc, ~sd.id(), sd.x(), sd.z(), sd.radius() * 1.3f + 10, sd.y(), false);
                packGrid(d, STATIC_GROUND_BASE + G * G * ns, g, fy);
                d[168 + 2 * ns] = (float) (g.min() - fy);
                d[169 + 2 * ns] = (float) (g.max() - fy);
                int o = 220 + 5 * ns++;
                d[o] = (float) (sd.x() - fx);
                d[o + 1] = (float) (sd.y() - fy);
                d[o + 2] = (float) (sd.z() - fz);
                d[o + 3] = sd.radius();
                d[o + 4] = sd.id() & 0xFFFFFF;
                // the inhale before a puff (0..1 over its last 4 s): the rift's mist draws in and darkens
                double inh = org.erisia.stasis.Deeps.nextPuffIn(sd, wclock, ClientFields.deepSeed());
                d[1597 + ns - 1] = inh < 4 ? (float) Math.pow(1 - inh / 4, 2) : 0;
                // the eye (λ-Nero): upright at the warped core; it holds its facing and twitches towards the nearest
                // player now and then (saccades: parallax as you walk round it, not a sticker); plus its destination
                float[] core = org.erisia.stasis.StaticDeeps.core(sd);
                d[204 + 3 * (ns - 1)] = (float) (core[0] - fx);
                d[205 + 3 * (ns - 1)] = (float) (core[1] - fy);
                d[206 + 3 * (ns - 1)] = (float) (core[2] - fz);
                double best = Double.MAX_VALUE, want = 0;
                for (var pl : mc.level.players()) {
                    double dx = pl.getX() - core[0], dz = pl.getZ() - core[2], dd = dx * dx + dz * dz + Math.pow(pl.getEyeY() - core[1], 2);
                    if (dd < best) {
                        best = dd;
                        want = Math.atan2(dx, dz);
                    }
                }
                double yaw = saccade(sd.id(), want, clock);
                String dest = org.erisia.stasis.Gates.destination(sd);
                int destCode = dest.equals("minecraft:the_end") ? 0 : dest.equals("minecraft:the_nether") ? 1 : 2;
                d[200 + ns - 1] = (float) (yaw + 10 * destCode);
            }
        }
        d[218] = ns;
        // rift core surroundings (λ-Nero): each core's place on screen (for the lens pull) and the strain on reality,
        // stronger nearer a core, so players can home in by following "darker and greyer"
        Matrix4f fwd0 = new Matrix4f(camState.projectionMatrix).mul(camState.viewRotationMatrix);
        float strain = 0;
        for (int i = 0; i < ns; i++) {
            float cx = d[204 + 3 * i] + (float) fx, cy = d[205 + 3 * i] + (float) fy, cz = d[206 + 3 * i] + (float) fz;
            double dist = Math.sqrt(Math.pow(cx - cam.x, 2) + Math.pow(cy - cam.y, 2) + Math.pow(cz - cam.z, 2));
            float R = d[223 + 5 * i];
            strain = (float) Math.max(strain, 1 - dist / (R * 2.5 + 20));
            org.joml.Vector4f v = new org.joml.Vector4f((float) (cx - cam.x), (float) (cy - cam.y), (float) (cz - cam.z), 1f);
            fwd0.transform(v);
            int o = 1580 + 4 * i;
            d[o] = v.w > 0.05f ? v.x / v.w : 0;
            d[o + 1] = v.w > 0.05f ? v.y / v.w : 0;
            d[o + 2] = (float) dist;
            d[o + 3] = v.w > 0.05f ? 1 : 0;
        }
        d[1596] = Math.max(0, strain);
        lastStrain = Math.max(0, strain);
        // strata weather (rift stage 2): the mask around the camera, and the weather clock per pocket epoch length
        // (wrapped here in double; the shader adds a pocket's phase and carries)
        if (Stasis.stops(mc.level)) StrataMask.frame(cam.x, cam.y, cam.z, ClientFields.deepSeed(), d, fx, fy, fz);
        // storms (stage 3): intensity, warning, the upwind direction, the storm field's noise origin at floor(camera)
        // and its thresholds, and a 16 x 16 ground grid (spacing 16) around the camera for the storm's height band
        if (Stasis.stops(mc.level) && ClientFields.deepSeed() != 0) {
            int seed = ClientFields.deepSeed();
            double[] ov = org.erisia.stasis.Storms.clientOverrideForClient();
            double si = org.erisia.stasis.Storms.intensity(seed, ov, clock);
            d[1312] = (float) si;
            d[1313] = (float) org.erisia.stasis.Storms.warning(seed, ov, clock);
            d[1314] = org.erisia.stasis.Deeps.windAngle(seed);
            if (si > 0) {
                double[] dr = org.erisia.stasis.Deeps.drift(wclock, seed);
                d[1315] = (float) ((fx - dr[0]) / org.erisia.stasis.Storms.SCALE);
                d[1316] = (float) ((fz - dr[1]) / org.erisia.stasis.Storms.SCALE);
                d[1317] = org.erisia.stasis.Storms.threshold(org.erisia.stasis.Storms.mistShare(si));
                d[1318] = org.erisia.stasis.Storms.threshold(org.erisia.stasis.Storms.riftShare(si));
                d[1319] = seed ^ 0x5701;
                double gx = Math.floor(cam.x / 16) * 16 - 120, gz = Math.floor(cam.z / 16) * 16 - 120;
                d[1576] = (float) (gx - fx);
                d[1577] = (float) (gz - fz);
                for (int j = 0; j < 16; j++)
                    for (int i = 0; i < 16; i++)
                        d[1320 + j * 16 + i] = (float) (surface(mc, gx + i * 16, gz + j * 16, cam.y - 10) - fy);
            }
        }
        int[] periods = {600, 900, 1200, 1800};
        for (int i = 0; i < 4; i++) {
            d[188 + i] = (float) (clock - Math.floor(clock / periods[i]) * periods[i]);
            d[192 + i] = (float) Math.floor(clock / periods[i]);
        }
        long now = mc.level.getGameTime();
        grids.values().removeIf(g -> now - g.made() > 200);
        // lightning bolts (BoltRenderer), projected to the screen
        Matrix4f fwd = new Matrix4f(camState.projectionMatrix).mul(camState.viewRotationMatrix);
        d[256] = BoltRenderer.fill(d, 260, 150, fwd, cam, 1.0);
        var img = texture.getPixels();
        if (img == null) return;
        for (int i = 0; i < SLOTS; i++) img.setPixelABGR(i % WIDTH, i / WIDTH, Float.floatToRawIntBits(d[i]));
        texture.upload();
    }

    /** Per eye (static deep id): its facing (rad) and when it may move next (weather clock, s). */
    static final java.util.Map<Integer, double[]> EYES = new java.util.HashMap<>();

    /** The eye's facing: held for 0.5-1.5 s, then a quick twitch towards the wanted facing (over ~0.12 s). */
    static double saccade(int id, double want, double clock) {
        double[] e = EYES.computeIfAbsent(id, k -> new double[]{want, clock, want, clock});
        // e: {from, twitch start, to, next decision}
        if (clock >= e[3]) {
            double cur = facing(e, clock);
            double diff = Math.atan2(Math.sin(want - cur), Math.cos(want - cur));
            if (Math.abs(diff) > 0.05) {
                e[0] = cur;
                e[1] = clock;
                e[2] = cur + diff;
            }
            e[3] = clock + 0.5 + Math.abs(Math.sin(clock * 12.9898 + id)) * 1.0;
        }
        if (EYES.size() > 64) EYES.clear();
        return Math.atan2(Math.sin(facing(e, clock)), Math.cos(facing(e, clock)));
    }

    static double facing(double[] e, double clock) {
        double u = Math.min(1, Math.max(0, (clock - e[1]) / 0.12));
        return e[0] + (e[2] - e[0]) * (u * u * (3 - 2 * u));
    }

    /** The shield's radius as drawn: eased towards the field's (the server steps it coarsely; Baughn: a size change
     *  should play over a second or two). Shrinking ~1.5 s, growing slower. */
    static final java.util.Map<Integer, double[]> SHOWN = new java.util.HashMap<>();

    static double shownRadius(Field f) {
        long now = System.nanoTime();
        double[] e = SHOWN.computeIfAbsent(f.id(), k -> new double[]{f.radius(), now});
        double dt = Math.min(0.25, (now - e[1]) / 1e9);
        e[1] = now;
        double tc = f.radius() < e[0] ? 0.45 : 2.0;
        e[0] += (f.radius() - e[0]) * Math.min(1, dt / tc);
        if (Math.abs(f.radius() - e[0]) < 0.01) e[0] = f.radius();
        if (SHOWN.size() > 64) SHOWN.keySet().removeIf(id -> ClientFields.fields().stream().noneMatch(x -> x.id() == id));
        return e[0];
    }

    /** The strain on reality near a rift core, 0..1 (for the HUD's flicker, SuitGauge). */
    public static volatile float lastStrain;

    /** -Dstasisdelvers.debugDeeps=true: log every deep sent to the shader, every frame (for AdventurAgent's tests). */
    static final boolean DEBUG_DEEPS = Boolean.getBoolean("stasisdelvers.debugDeeps");
    static long debugFrame;
    static double smoothClock = Double.NaN;
    static long smoothNanos;

    /**
     * The weather's clock on the client (Deeps.time, s), smoothed: it runs on real time and slews gently towards the
     * game time. The client's game time steps whenever the server's time packet corrects it (and the client's tick
     * rate changes in fields), and the whole mist jumped with it (AdventurAgent). More than 5 s off: snap.
     */
    static double weatherClock(Minecraft mc) {
        double target = (mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20.0;
        long now = System.nanoTime();
        double dt = Math.min(0.25, (now - smoothNanos) / 1e9);
        smoothNanos = now;
        if (Double.isNaN(smoothClock) || Math.abs(target - smoothClock) > 5) smoothClock = target;
        else {
            smoothClock += dt;
            smoothClock += (target - smoothClock) * Math.min(1, dt * 0.5);
        }
        return smoothClock;
    }

    /** The surface (top of motion-blocking blocks, not leaves) at a column, or the fallback where it isn't loaded. */
    static double surface(Minecraft mc, double x, double z, double fallback) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        long key = (long) bx << 32 | (bz & 0xFFFFFFFFL);
        if (mc.level.hasChunkAt(new net.minecraft.core.BlockPos(bx, 0, bz))) {
            int h = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            // an empty or not yet filled chunk reads as the bottom of the world: not a surface
            if (h > mc.level.getMinY()) {
                if (SURFACES.size() > 50_000) SURFACES.clear();
                SURFACES.put(key, h);
                return h;
            }
        }
        // a column we saw before keeps its height while its chunk is away (a deep's tint and mist blinked off when
        // its centre column read as unloaded for a moment: AdventurAgent)
        Integer seen = SURFACES.get(key);
        return seen != null ? seen : fallback;
    }

    static final java.util.Map<Long, Integer> SURFACES = new java.util.HashMap<>();

    /**
     * The ground grid of a deep (cached by key): G x G surface heights covering the square centre +- reach (unloaded
     * columns take the fallback). A drifting deep's grid lies on a fixed world lattice (MOVING_STEP), so as the deep
     * moves the samples stay put and the mist's ground doesn't jump (Baughn saw it snap a few times a second when the
     * grid moved with the deep); it's remade when the deep crosses a lattice line. A static one is remade while it
     * has unloaded columns.
     */
    static Grid grid(Minecraft mc, int key, double cx, double cz, double reach, float fallback, boolean moving) {
        long now = mc.level.getGameTime();
        double step = moving ? MOVING_STEP : 2 * reach / (G - 1);
        double ox = moving ? Math.floor((cx - reach) / step) * step : cx - reach;
        double oz = moving ? Math.floor((cz - reach) / step) * step : cz - reach;
        Grid g = grids.get(key);
        if (g != null && g.ox() == ox && g.oz() == oz && g.step() == step && (g.complete() || moving && now - g.made() < GRID_TICKS)) {
            // (a complete grid's clock is only for the eviction of unused grids; an incomplete one is redone after GRID_TICKS)
            if (g.complete()) grids.put(key, g = new Grid(g.ox(), g.oz(), g.step(), g.h(), g.min(), g.max(), true, now));
            return g;
        }
        float[] h = new float[G * G];
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        boolean complete = true;
        for (int j = 0; j < G; j++)
            for (int i = 0; i < G; i++) {
                double y = surface(mc, ox + i * step, oz + j * step, Double.NaN);
                if (Double.isNaN(y)) {
                    complete = false;
                    y = fallback;
                }
                h[j * G + i] = (float) y;
                min = Math.min(min, (float) y);
                max = Math.max(max, (float) y);
            }
        g = new Grid(ox, oz, step, h, min, max, complete, now);
        grids.put(key, g);
        return g;
    }

    static void packGrid(float[] d, int base, Grid g, double fy) {
        float[] h = g.h();
        for (int i = 0; i < G * G; i++) d[base + i] = (float) (h[i] - fy);
    }
}

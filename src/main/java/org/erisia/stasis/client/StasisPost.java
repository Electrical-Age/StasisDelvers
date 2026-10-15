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
 * that reads the depth buffer. It learns where the fields are from a 1024x2 texture of packed floats (slot i at
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
    /** Data texture size: 1024 x 2 (GL 3.3 only promises 1024-wide textures). */
    static final int WIDTH = 1024, ROWS = 2, SLOTS = WIDTH * ROWS;
    /** Drifting deeps: at most MAX_DEEPS (stasis.fsh MAX_DEEPS), DEEP_STRIDE slots each from DEEP_BASE. */
    static final int DEEP_BASE = 1024, DEEP_STRIDE = 8, MAX_DEEPS = 24;

    /** On the render thread (texture creation asserts it): the first frame. */
    static void init() {
        texture = new DynamicTexture(() -> "stasisdelvers field data", WIDTH, ROWS, true);
        Minecraft.getInstance().getTextureManager().register(DATA, texture);
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
        d[29] = (float) ((mc.level.getGameTime() % 24000L) / 20.0);
        // eddy time (Eddies.time), with the partial tick so the mist drifts smoothly
        d[30] = ((mc.level.getGameTime() % 1048576L) + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20f;
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
            d[o + 3] = (float) f.radius();
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
            float t = d[30];
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
                int bx = (int) Math.floor(dp.x()), bz = (int) Math.floor(dp.z());
                double surface = mc.level.hasChunkAt(new net.minecraft.core.BlockPos(bx, 0, bz))
                    ? mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) : cam.y - 10;
                int o = DEEP_BASE + DEEP_STRIDE * nd++;
                d[o] = (float) (dp.x() - fx);
                d[o + 1] = (float) (dp.z() - fz);
                d[o + 2] = dp.radius();
                d[o + 3] = dp.strength();
                d[o + 4] = (float) (surface - fy);
                d[o + 5] = dp.id() & 0xFFFFFF;
            }
        }
        d[31] = nd;
        // static deeps near the camera (StaticDeeps): centre - floor(camera), radius, shape id; at most 4
        int ns = 0;
        if (Stasis.stops(mc.level) && ClientFields.deepSeed() != 0) {
            for (var sd : org.erisia.stasis.StaticDeeps.near((float) cam.x, (float) cam.y, (float) cam.z, 260, ClientFields.deepSeed())) {
                if (ns == 4) break;
                double dist = Math.sqrt(Math.pow(sd.x() - cam.x, 2) + Math.pow(sd.y() - cam.y, 2) + Math.pow(sd.z() - cam.z, 2));
                if (dist - sd.radius() * 1.5 > 200) continue;
                int o = 220 + 5 * ns++;
                d[o] = (float) (sd.x() - fx);
                d[o + 1] = (float) (sd.y() - fy);
                d[o + 2] = (float) (sd.z() - fz);
                d[o + 3] = sd.radius();
                d[o + 4] = sd.id() & 0xFFFFFF;
            }
        }
        d[218] = ns;
        // lightning bolts (BoltRenderer), projected to the screen
        Matrix4f fwd = new Matrix4f(camState.projectionMatrix).mul(camState.viewRotationMatrix);
        d[256] = BoltRenderer.fill(d, 260, 150, fwd, cam, 1.0);
        var img = texture.getPixels();
        if (img == null) return;
        for (int i = 0; i < SLOTS; i++) img.setPixelABGR(i % WIDTH, i / WIDTH, Float.floatToRawIntBits(d[i]));
        texture.upload();
    }
}

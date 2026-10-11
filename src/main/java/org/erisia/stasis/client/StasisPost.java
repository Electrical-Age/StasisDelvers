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
 * that reads the depth buffer. It learns where the fields are from a 128x1 texture of packed floats, refreshed every
 * frame (the layout is in shaders/post/stasis.fsh). Requested through the player's active post effects while the
 * player is in a dimension where time stops.
 */
final class StasisPost {
    private StasisPost() {
    }

    static final Identifier EFFECT = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "stasis");
    /** Post chains look textures up at textures/effect/&lt;path&gt;.png. */
    static final Identifier DATA = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "textures/effect/fielddata.png");
    static DynamicTexture texture;

    /** On the render thread (texture creation asserts it): the first frame. */
    static void init() {
        texture = new DynamicTexture(() -> "stasisdelvers field data", 128, 1, true);
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
        float[] d = new float[128];
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
            if (n == 16) break;
            int o = 32 + 6 * n++;
            d[o] = (float) (f.x() - fx);
            d[o + 1] = (float) (f.y() - fy);
            d[o + 2] = (float) (f.z() - fz);
            d[o + 3] = (float) f.radius();
            d[o + 4] = (float) f.collapse();
            d[o + 5] = (float) f.failing();
        }
        d[24] = n;
        var img = texture.getPixels();
        if (img == null) return;
        for (int i = 0; i < 128; i++) img.setPixelABGR(i, 0, Float.floatToRawIntBits(d[i]));
        texture.upload();
    }
}

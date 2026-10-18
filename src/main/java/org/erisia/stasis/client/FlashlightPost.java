package org.erisia.stasis.client;

import mods.eln.item.electricalitem.LampItem;
import mods.eln.generic.ElnItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import org.erisia.stasis.StasisDelvers;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * EA flashlight beams drawn per pixel by a post effect (assets/stasisdelvers/post_effect/flashlight.json), so they
 * follow the view at once (Baughn: EA's light blocks along the beam lag). The server then only lights the holder's
 * own block (LampItem.clientBeam, set in StasisDelvers). Ordered before the stasis effect, so in the Grey the beam
 * lights a grey world.
 * <ul>
 * <li>Your own beam in first person starts at the camera: everything you see is in its line of sight, so it needs no
 * shadows.</li>
 * <li>Others' beams (and yours in third person) start at the eyes, and end where the look ray first hits a block, so
 * they don't shine through walls in front of them.</li>
 * <li>With an Iris shaderpack in use the beam is off (shaderpacks light the held item themselves).</li>
 * </ul>
 */
public final class FlashlightPost {
    private FlashlightPost() {
    }

    public static final Identifier EFFECT = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "flashlight");
    static final Identifier DATA = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "textures/effect/beamdata.png");
    static final int MAX = 5;
    /** Tuning (later: client config). Beam length = light level x LENGTH_PER_LIGHT blocks. */
    // (Baughn 2026-10-18: half as bright, covering most of the screen; near walls no longer cut a dark hole: the near
    // cut, which keeps the hand unlit, now fades in from 0.14 to 0.26 m instead of a hard 0.9: a player stands 0.3 from
    // a wall at the closest)
    static final float STRENGTH = 4.5F, LENGTH_PER_LIGHT = 2.0F, HALF_ANGLE_DEG = 50.0F, NEAR = 0.14F;
    static final float[] TINT = {1.0F, 0.93F, 0.78F};
    static final double SEE_RANGE = 64.0;

    static DynamicTexture texture;
    /** Beams found at the last tick (holders and their light). */
    static final List<Player> holders = new ArrayList<>();
    static final List<Integer> lights = new ArrayList<>();

    /** The light of a lit EA flashlight in either hand, else 0. */
    static int beamLight(Player p) {
        int best = 0;
        for (ItemStack s : List.of(p.getMainHandItem(), p.getOffhandItem()))
            if (s.getItem() instanceof ElnItem i && i.descriptor instanceof LampItem lamp) best = Math.max(best, lamp.beamLight(s));
        return best;
    }

    static boolean shaderPackInUse() {
        if (!net.neoforged.fml.ModList.get().isLoaded("iris")) return false;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isShaderPackInUse").invoke(inst);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        holders.clear();
        lights.clear();
        if (mc.player == null || mc.level == null) return;
        if (!shaderPackInUse())
            for (Player p : mc.level.players()) {
                if (holders.size() == MAX) break;
                if (p.isSpectator() || p.distanceToSqr(mc.player) > SEE_RANGE * SEE_RANGE) continue;
                int l = beamLight(p);
                if (l > 0) {
                    holders.add(p);
                    lights.add(l);
                }
            }
        List<Identifier> effects = mc.player.getActivePostEffects();
        boolean want = texture != null && !holders.isEmpty();
        if (want != effects.contains(EFFECT)) {
            List<Identifier> l = new ArrayList<>(effects);
            if (want) l.add(0, EFFECT);   // before the stasis effect: the Grey stays grey in the beam
            else l.remove(EFFECT);
            mc.player.setActivePostEffects(l);
        }
    }

    static void frame(FrameGraphSetupEvent e) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (texture == null) {
            texture = new DynamicTexture(() -> "stasisdelvers beam data", 128, 1, true);
            mc.getTextureManager().register(DATA, texture);
        }
        var cam = e.getCameraState();
        float pt = cam.cameraEntityPartialTicks;
        float[] d = new float[128];
        new Matrix4f(cam.projectionMatrix).mul(cam.viewRotationMatrix).invert().get(d, 0);
        d[17] = STRENGTH;
        d[18] = TINT[0];
        d[19] = TINT[1];
        d[20] = TINT[2];
        d[21] = NEAR;
        float cosHalf = (float) Math.cos(Math.toRadians(HALF_ANGLE_DEG));
        int n = 0;
        for (int i = 0; i < holders.size(); i++) {
            Player p = holders.get(i);
            float len = lights.get(i) * LENGTH_PER_LIGHT, cut = len;
            Vec3 org, dir;
            if (p == mc.getCameraEntity() && cam.isFirstPerson) {
                org = cam.pos;
                dir = Vec3.directionFromRotation(cam.xRot, cam.yRot);
            } else {
                org = p.getEyePosition(pt);
                dir = p.getViewVector(pt);
                var hit = mc.level.clip(new ClipContext(org, org.add(dir.scale(len)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
                // ends just past the first block hit (no light through that wall); the falloff stays the full beam's
                if (hit.getType() != HitResult.Type.MISS) cut = (float) Math.min(len, hit.getLocation().distanceTo(org) + 1.0);
            }
            int o = 24 + 9 * n++;
            d[o] = (float) (org.x - cam.pos.x);
            d[o + 1] = (float) (org.y - cam.pos.y);
            d[o + 2] = (float) (org.z - cam.pos.z);
            d[o + 3] = (float) dir.x;
            d[o + 4] = (float) dir.y;
            d[o + 5] = (float) dir.z;
            d[o + 6] = len;
            d[o + 7] = cosHalf;
            d[o + 8] = cut;
        }
        d[16] = n;
        var img = texture.getPixels();
        if (img == null) return;
        for (int i = 0; i < 128; i++) img.setPixelABGR(i, 0, Float.floatToRawIntBits(d[i]));
        texture.upload();
    }
}

package org.erisia.stasis.client;

import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.sounds.SoundSource;
import org.erisia.stasis.ClientFields;
import org.erisia.stasis.Field;
import org.erisia.stasis.StasisNet;
import org.erisia.stasis.StasisRegistry;
import org.erisia.stasis.mixin.client.ChannelAccessor;
import org.erisia.stasis.mixin.client.SoundManagerAccessor;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTEfx;

/**
 * A tower's lightning makes no sound: it makes an anti-sound (Baughn 2026-10-17, FAILURE-PLAN E). At each crack
 * near the player every other sound cuts out (a sub-thump as it goes), stays silent for the flash, then returns, the
 * lows first and the highs last. Inside a failing field all sound is dulled (quieter, low-passed, a slight pitch
 * sag) as the field weakens. Music and UI sounds are never touched.
 */
public final class AntiSound {
    private AntiSound() {}

    /** Implemented by SoundEngineMixin. */
    public interface Engine {
        Map<SoundInstance, ChannelAccess.ChannelHandle> stasis$channels();
    }

    static final double CUT = 0.17, LOWS = 0.45, HIGHS = 1.1;   // seconds: silence, then lows and highs back

    static long crackAt = Long.MIN_VALUE;
    static double strain;            // eased: the failing of the field the player is in
    static long lastFrame;
    static float vol = 1, hf = 1, sag = 1;
    static float appliedVol = 1, appliedHf = 1;
    static int filter = -1;          // the EFX low-pass, -2 if EFX isn't available

    public static boolean affects(SoundSource s) {
        return s != SoundSource.MASTER && s != SoundSource.MUSIC && s != SoundSource.UI;
    }

    public static float pitch() {
        return sag;
    }

    static void init() {
        var prev = StasisNet.boltSink;
        StasisNet.boltSink = b -> {
            prev.accept(b);
            crack(b);
        };
    }

    static void crack(StasisNet.Bolt b) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var p = ears(mc);
        double d = Math.min(p.distanceToSqr(b.fx(), b.fy(), b.fz()), p.distanceToSqr(b.tx(), b.ty(), b.tz()));
        long now = System.nanoTime();
        if (d > 64 * 64) return;
        // in a burst, the sound gets a moment back between cuts (else a burst is one long silence)
        // (crackAt starts at Long.MIN_VALUE: now - crackAt would overflow and hold every crack back)
        if (crackAt != Long.MIN_VALUE && (now - crackAt) / 1e9 < CUT + 0.25) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(StasisRegistry.CRACK_THUMP.get(), 1.0F, 0.25F));
        crackAt = now;
    }

    /** Where the sound is heard (the camera, which a spectator or a detached camera can take away from the player). */
    static net.minecraft.world.phys.Vec3 ears(Minecraft mc) {
        return mc.getSoundManager().getListenerTransform().position();
    }

    static double smooth(double a, double b, double t) {
        double x = Math.max(0, Math.min(1, (t - a) / (b - a)));
        return x * x * (3 - 2 * x);
    }

    /** Every frame: the envelope, applied through the per-category gain and a low-pass on every playing channel. */
    static void frame() {
        var mc = Minecraft.getInstance();
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.25, (now - lastFrame) / 1e9);
        lastFrame = now;
        double s = 0;
        if (mc.player != null && mc.level != null) {
            var p = ears(mc);
            for (Field f : ClientFields.fields()) {
                double dx = p.x - f.x(), dy = p.y - f.y(), dz = p.z - f.z();
                if (dx * dx + dy * dy + dz * dz < f.radius() * f.radius()) s = Math.max(s, f.failing());
            }
        }
        strain += (s - strain) * Math.min(1, dt / 0.6);
        double t = (now - crackAt) / 1e9;
        double low = 1, high = 1;
        if (t >= 0 && t < HIGHS + 0.1) {
            low = 0.02 + 0.98 * smooth(CUT, CUT + LOWS, t);
            high = 0.03 + 0.97 * smooth(CUT, CUT + HIGHS, t);
        }
        vol = (float) (low * (1 - 0.4 * strain));
        hf = (float) (high * (1 - 0.75 * strain));
        sag = (float) (1 - 0.06 * strain);
        if (Math.abs(vol - appliedVol) > 0.005 || (vol == 1) != (appliedVol == 1)) {
            appliedVol = vol;
            for (SoundSource src : SoundSource.values())
                if (affects(src)) mc.getSoundManager().updateCategoryVolume(src, vol);
        }
        if (hf < 0.995 || appliedHf < 0.995) filter(mc);
    }

    /** Sets the low-pass on every affected channel (and lifts it once the sound is clear again). */
    static void filter(Minecraft mc) {
        if (filter == -2) return;
        float h = hf;
        boolean clear = h >= 0.995;
        appliedHf = clear ? 1 : h;
        if (!(((SoundManagerAccessor) mc.getSoundManager()).stasis$engine() instanceof Engine e)) return;
        for (var en : e.stasis$channels().entrySet()) {
            if (!affects(en.getKey().getSource())) continue;
            en.getValue().execute(ch -> {
                try {
                    if (filter == -1) {
                        filter = EXTEfx.alGenFilters();
                        EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
                    }
                    if (filter < 0) return;
                    int src = ((ChannelAccessor) ch).stasis$source();
                    if (clear) AL10.alSourcei(src, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
                    else {
                        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, 1.0F);
                        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, h);
                        AL10.alSourcei(src, EXTEfx.AL_DIRECT_FILTER, filter);
                    }
                } catch (Throwable x) {
                    filter = -2;   // no EFX: volume only
                }
            });
        }
    }
}

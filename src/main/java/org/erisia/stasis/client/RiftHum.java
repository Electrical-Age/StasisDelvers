package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.erisia.stasis.ClientFields;
import org.erisia.stasis.Deeps;
import org.erisia.stasis.Pockets;
import org.erisia.stasis.Stasis;
import org.erisia.stasis.StasisRegistry;

/**
 * The hum of a strata pocket about to turn rift (rift stage 2): the second warning, for players looking the other
 * way. A low hum from the nearest loud violet pocket within ~30 blocks, rising in volume and pitch through its 20 s
 * warning, cut when it turns rift. Client-only: the pockets come from the seed and the clock, like everything else.
 */
public final class RiftHum {
    private RiftHum() {
    }

    static Hum playing;
    static float target, progress;
    static double sx, sy, sz;

    static void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.level.getGameTime() % 5 == 0) scan(mc);
        if (target > 0.01f && (playing == null || playing.isStopped())) {
            playing = new Hum();
            mc.getSoundManager().play(playing);
        }
    }

    /** The loudest violet pocket within 2 pockets of the player (each pocket by its centre). */
    static void scan(Minecraft mc) {
        int seed = ClientFields.deepSeed();
        target = 0;
        if (seed == 0 || !Stasis.stops(mc.level)) return;
        double t = Double.isNaN(StasisPost.smoothClock) ? Deeps.time(mc.level) : StasisPost.smoothClock;
        var p = mc.player.position();
        int px = Math.floorDiv((int) Math.floor(p.x), Pockets.POCKET), py = Math.floorDiv((int) Math.floor(p.y), Pockets.POCKET),
            pz = Math.floorDiv((int) Math.floor(p.z), Pockets.POCKET);
        for (int i = px - 2; i <= px + 2; i++)
            for (int j = py - 2; j <= py + 2; j++)
                for (int k = pz - 2; k <= pz + 2; k++) {
                    int cx = i * Pockets.POCKET + 6, cy = j * Pockets.POCKET + 6, cz = k * Pockets.POCKET + 6;
                    var d = Pockets.strataAt(seed, cx, cy, cz);
                    if (d == null) continue;
                    var w = Pockets.weather(d.id(), i, j, k, t);
                    if (w.state() != Pockets.State.VIOLET) continue;
                    float f = (float) (1 - w.untilNext() / Pockets.VIOLET);
                    double dist = Math.sqrt((cx - p.x) * (cx - p.x) + (cy - p.y) * (cy - p.y) + (cz - p.z) * (cz - p.z));
                    float loud = (0.25f + 0.75f * f) * (float) Math.max(0, 1 - dist / 32);
                    if (loud > target) {
                        target = loud;
                        progress = f;
                        sx = cx;
                        sy = cy;
                        sz = cz;
                    }
                }
    }

    static final class Hum extends AbstractTickableSoundInstance {
        Hum() {
            super(StasisRegistry.RIFT_HUM.get(), SoundSource.AMBIENT, RandomSource.create());
            looping = true;
            volume = 0.01f;
            attenuation = SoundInstance.Attenuation.NONE;
            x = sx;
            y = sy;
            z = sz;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            volume += (Math.max(0.0001f, target) - volume) * 0.15f;
            pitch = 0.8f + 0.45f * progress;
            x = sx;
            y = sy;
            z = sz;
            if (target <= 0.01f && volume < 0.02f) stop();
        }
    }
}

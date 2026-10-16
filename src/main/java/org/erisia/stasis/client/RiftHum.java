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

    static Wind wind;

    static void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // a storm's wind: rising through the warning, full in the storm (stage 3)
        float w = 0;
        if (Stasis.stops(mc.level) && ClientFields.deepSeed() != 0) {
            double t = Double.isNaN(StasisPost.smoothClock) ? Deeps.realTime(mc.level) : StasisPost.smoothClock;
            double[] ov = org.erisia.stasis.Storms.clientOverrideForClient();
            w = (float) Math.max(0.85 * org.erisia.stasis.Storms.intensity(ClientFields.deepSeed(), ov, t),
                0.12 * org.erisia.stasis.Storms.warning(ClientFields.deepSeed(), ov, t));
        }
        windTarget = w;
        if (w > 0.01f && (wind == null || wind.isStopped())) {
            wind = new Wind();
            mc.getSoundManager().play(wind);
        }
        if (mc.level.getGameTime() % 5 == 0) scan(mc);
        // each pocket's warning is its own hum (one hum carried from pocket to pocket and ran 34 s: AdventurAgent)
        if (playing != null && !playing.isStopped() && target > 0.01f && (playing.px != sx || playing.py != sy || playing.pz != sz)) {
            mc.getSoundManager().stop(playing);
            playing = null;
        }
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
        double t = Double.isNaN(StasisPost.smoothClock) ? Deeps.realTime(mc.level) : StasisPost.smoothClock;   // (pockets keep real time)
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
                    // (a gentler falloff: at 27 blocks a linear one left ~4% and the hum seemed to stop early, AdventurAgent)
                    float loud = (0.3f + 0.7f * f) * (float) Math.sqrt(Math.max(0, 1 - dist / 40));
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
            x = px = sx;
            y = py = sy;
            z = pz = sz;
        }

        final double px, py, pz;

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            volume += (Math.max(0.0001f, target) - volume) * 0.15f;
            pitch = 0.8f + 0.45f * progress;
            if (target <= 0.01f && volume < 0.02f) stop();
        }
    }

    static float windTarget;

    static final class Wind extends AbstractTickableSoundInstance {
        Wind() {
            super(StasisRegistry.STORM_WIND.get(), SoundSource.WEATHER, RandomSource.create());
            looping = true;
            volume = 0.01f;
            attenuation = SoundInstance.Attenuation.NONE;
            relative = true;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            volume += (Math.max(0.0001f, windTarget) - volume) * 0.02f;
            pitch = 0.85f + 0.3f * windTarget;
            if (windTarget <= 0.01f && volume < 0.02f) stop();
        }
    }
}

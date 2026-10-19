package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.erisia.stasis.Spread;

/**
 * During the End event (the spread's endEvent: from the first pillar's stage until the flash) the End's fog is pushed
 * out to the render distance, eased over 2 s each way, so the island breaking up and the shards' flight can be seen
 * (AdventurAgent: the End's fog hid the show past ~40 blocks). Only the atmospheric fog, and only ever raised.
 */
public final class EndEventFog {
    private EndEventFog() {
    }

    static final float STEP = 1 / 40f;
    private static float k, prev;

    static void tick(ClientTickEvent.Post e) {
        var lvl = Minecraft.getInstance().level;
        float target = lvl != null && lvl.dimension() == Level.END && Spread.client.endEvent() ? 1 : 0;
        prev = k;
        k += Mth.clamp(target - k, -STEP, STEP);
    }

    static void fog(ViewportEvent.RenderFog e) {
        if (k <= 0 && prev <= 0 || e.getType() != FogType.ATMOSPHERIC) return;
        var lvl = Minecraft.getInstance().level;
        if (lvl == null || lvl.dimension() != Level.END) return;
        float f = Mth.lerp((float) e.getPartialTick(), prev, k);
        f = f * f * (3 - 2 * f);
        var d = e.getFogData();
        d.environmentalStart = Math.max(d.environmentalStart, Mth.lerp(f, d.environmentalStart, d.renderDistanceStart));
        d.environmentalEnd = Math.max(d.environmentalEnd, Mth.lerp(f, d.environmentalEnd, d.renderDistanceEnd));
    }
}

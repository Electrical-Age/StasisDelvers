package org.erisia.stasis.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.util.RandomSource;
import org.erisia.stasis.Stasis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rain and snow hang still in stopped land (Baughn 2026-10-17: falling rain made a frozen remnant look not very
 * stopped): a column whose middle is stopped is drawn at time 0, each drop where the column's own offset puts it.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
    private static final String EXTRACT = "extractRenderState(Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V";

    @WrapOperation(method = EXTRACT, require = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;createRainColumnInstance(Lnet/minecraft/util/RandomSource;JIIIIIF)Lnet/minecraft/client/renderer/WeatherEffectRenderer$ColumnInstance;"))
    private WeatherEffectRenderer.ColumnInstance stasis$stillRain(WeatherEffectRenderer self, RandomSource random, long ticks, int x, int bottomY,
                                                                   int topY, int z, int light, float partial,
                                                                   Operation<WeatherEffectRenderer.ColumnInstance> original) {
        boolean still = stasis$stopped(x, (bottomY + topY) >> 1, z);
        return original.call(self, random, still ? 0L : ticks, x, bottomY, topY, z, light, still ? 0f : partial);
    }

    @WrapOperation(method = EXTRACT, require = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;createSnowColumnInstance(Lnet/minecraft/util/RandomSource;JIIIIIF)Lnet/minecraft/client/renderer/WeatherEffectRenderer$ColumnInstance;"))
    private WeatherEffectRenderer.ColumnInstance stasis$stillSnow(WeatherEffectRenderer self, RandomSource random, long ticks, int x, int bottomY,
                                                                   int topY, int z, int light, float partial,
                                                                   Operation<WeatherEffectRenderer.ColumnInstance> original) {
        boolean still = stasis$stopped(x, (bottomY + topY) >> 1, z);
        return original.call(self, random, still ? 0L : ticks, x, bottomY, topY, z, light, still ? 0f : partial);
    }

    private static boolean stasis$stopped(int x, int y, int z) {
        var level = Minecraft.getInstance().level;
        return level != null && Stasis.stops(level) && Stasis.rateAt(level, x, y, z) <= 0;
    }
}

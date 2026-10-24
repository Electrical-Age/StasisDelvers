package org.erisia.stasis.compat.dh;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;

import java.util.function.Supplier;

/**
 * Distant Horizons (optional; only loaded when it is): its LODs past the vanilla render distance have their own colour
 * and depth textures and projection, so in vanilla's depth they read as sky, and the stopped-land grey and the mist
 * followed the camera's column there (Nero). The Stasis passes read DH's depth where vanilla's is sky and DH drew
 * (StasisPost, stasis.fsh): the ray from vanilla's matrices, the distance along it from DH's depth and planes.
 */
public final class DhDepth {
    private DhDepth() {
    }

    /** DH's near and far planes this frame (blocks), NaN before its first render pass. */
    static volatile float near = Float.NaN, far = Float.NaN;

    public static void init() {
        DhApi.events.bind(DhApiBeforeRenderPassEvent.class, new DhApiBeforeRenderPassEvent() {
            @Override
            public void beforeRender(DhApiEventParam<DhApiRenderParam> p) {
                if (p.value == null) return;
                near = p.value.nearClipPlane;
                far = p.value.farClipPlane;
            }
        });
    }

    static GpuTextureView view(Supplier<DhApiResult<IDhApiBlazeTextureWrapper>> get) {
        try {
            if (DhApi.Delayed.renderProxy == null) return null;
            var r = get.get();
            if (r == null || !r.success || r.payload == null) return null;
            if (r.payload.getWrappedMcObject() instanceof Object[] o && o.length > 1 && o[1] instanceof GpuTextureView v) return v;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** DH's depth texture now (its size follows the window), or null. */
    public static GpuTextureView depth() {
        return view(() -> DhApi.Delayed.renderProxy.getDhDepthTextureBlazeWrapper());
    }

    /** DH's colour texture now (alpha 0 where it drew nothing), or null. */
    public static GpuTextureView color() {
        return view(() -> DhApi.Delayed.renderProxy.getDhColorTextureBlazeWrapper());
    }

    /**
     * The slots for stasis.fsh: [1700] DH on, [1701] its near plane, [1702] its far plane (blocks), [1703] its depth
     * convention (0: 0..1 forward, 1: -1..1 forward, 2: 0..1 reversed, 3: -1..1 reversed). The shader takes the ray from
     * vanilla's matrices and only the distance along it from DH's depth: DH's own matrices, read as DH's fade pass
     * reads them, put the picture's halves on the wrong sides (AdventurAgent saw a seam down the middle).
     */
    public static void fill(float[] d) {
        float n = near, f = far;
        if (Float.isNaN(n) || Float.isNaN(f) || f <= n || depth() == null || color() == null) return;
        try {
            var proxy = DhApi.Delayed.renderProxy;
            boolean reversed = proxy.getDepthDirection() == com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection.REVERSE_Z;
            boolean neg = proxy.getDepthRange() == EDhApiDepthRange.NEG_ONE_TO_POS_ONE;
            d[1700] = 1;
            d[1701] = n;
            d[1702] = f;
            d[1703] = (reversed ? 2 : 0) + (neg ? 1 : 0);
        } catch (Throwable t) {
            d[1700] = 0;
        }
    }
}

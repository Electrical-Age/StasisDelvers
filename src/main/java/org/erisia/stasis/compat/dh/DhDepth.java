package org.erisia.stasis.compat.dh;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import org.joml.Matrix4f;

import java.util.function.Supplier;

/**
 * Distant Horizons (optional; only loaded when it is): its LODs past the vanilla render distance have their own colour
 * and depth textures and projection, so in vanilla's depth they read as sky, and the stopped-land grey and the mist
 * followed the camera's column there (Nero). The Stasis passes read DH's depth where vanilla's is sky and DH drew
 * (StasisPost, stasis.fsh), with the matrices built as DH's own vanilla-fade pass builds them.
 */
public final class DhDepth {
    private DhDepth() {
    }

    /** inverse(DH projection * DH model-view) this frame (camera-relative), or null before DH's first render pass. */
    static volatile Matrix4f inverse;

    public static void init() {
        DhApi.events.bind(DhApiBeforeRenderPassEvent.class, new DhApiBeforeRenderPassEvent() {
            @Override
            public void beforeRender(DhApiEventParam<DhApiRenderParam> p) {
                if (p.value == null) return;
                // (DH's own math and layout, as its BlazeVanillaFadeRenderer: no guessing at its matrix conventions)
                var m = new com.seibel.distanthorizons.core.util.math.DhMat4f(p.value.dhProjectionMatrix);
                m.multiply(p.value.dhModelViewMatrix);
                m.invert();
                inverse = m.createJomlMatrix();
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
     * The slots for stasis.fsh: [1700] DH on, [1702] depth range -1..1 (1) or 0..1 (0), [1703..1718] inverse(DH
     * projection * DH model-view), column-major. A pixel is DH's where its colour has alpha (as DH's own fade pass).
     */
    public static void fill(float[] d) {
        Matrix4f inv = inverse;
        if (inv == null || depth() == null || color() == null) return;
        try {
            d[1700] = 1;
            d[1702] = DhApi.Delayed.renderProxy.getDepthRange() == EDhApiDepthRange.NEG_ONE_TO_POS_ONE ? 1 : 0;
            inv.get(d, 1703);
        } catch (Throwable t) {
            d[1700] = 0;
        }
    }
}

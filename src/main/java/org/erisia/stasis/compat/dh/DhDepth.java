package org.erisia.stasis.compat.dh;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import org.joml.Matrix4f;

/**
 * Distant Horizons (optional; only loaded when it is): its LODs past the vanilla render distance have their own depth
 * texture and projection, so in vanilla's depth they read as sky, and the stopped-land grey and the mist followed the
 * camera's column there (Nero). The Stasis pass reads DH's depth where vanilla's is sky (StasisPost, stasis.fsh).
 */
public final class DhDepth {
    private DhDepth() {
    }

    /** DH's projection this frame, column-major (JOML), or null before its first render pass. */
    static volatile Matrix4f projection;

    public static void init() {
        DhApi.events.bind(DhApiBeforeRenderPassEvent.class, new DhApiBeforeRenderPassEvent() {
            @Override
            public void beforeRender(DhApiEventParam<DhApiRenderParam> p) {
                if (p.value != null) projection = joml(p.value.dhProjectionMatrix);
            }
        });
    }

    /**
     * A DhApiMat4f as JOML. Its fields are row-major or column-major by name, which the API doesn't say: a perspective
     * matrix has -1 in row 3, column 2; whichever reading puts it there is the right one.
     */
    static Matrix4f joml(DhApiMat4f m) {
        // reading 1: mRC (row R, column C); JOML's constructor takes columns
        Matrix4f a = new Matrix4f(m.m00, m.m10, m.m20, m.m30, m.m01, m.m11, m.m21, m.m31, m.m02, m.m12, m.m22, m.m32, m.m03, m.m13, m.m23, m.m33);
        return Math.abs(a.m23() + 1) < 1e-3 ? a : a.transpose();
    }

    /** DH's depth texture now (its size follows the window), or null. */
    public static GpuTextureView depth() {
        try {
            var proxy = DhApi.Delayed.renderProxy;
            if (proxy == null) return null;
            var r = proxy.getDhDepthTextureBlazeWrapper();
            if (r == null || !r.success || r.payload == null) return null;
            if (r.payload.getWrappedMcObject() instanceof Object[] o && o.length > 1 && o[1] instanceof GpuTextureView v) return v;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The slots for stasis.fsh: [1700] DH on, [1701] its far depth (what a pixel with no LOD holds), [1702] depth range
     * -1..1 (1) or 0..1 (0), [1703..1718] inverse(DH projection * view rotation), column-major.
     */
    public static void fill(float[] d, Matrix4f viewRotation) {
        Matrix4f p = projection;
        if (p == null || depth() == null) return;
        try {
            var proxy = DhApi.Delayed.renderProxy;
            d[1700] = 1;
            d[1701] = proxy.getDepthDirection().farDepth;
            d[1702] = proxy.getDepthRange() == EDhApiDepthRange.NEG_ONE_TO_POS_ONE ? 1 : 0;
            new Matrix4f(p).mul(viewRotation).invert().get(d, 1703);
        } catch (Throwable t) {
            d[1700] = 0;
        }
    }
}

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
    static volatile org.joml.Matrix4f inverseProjection;
    static volatile String lastProj, lastMv, lastMcProj;

    public static void init() {
        DhApi.events.bind(DhApiBeforeRenderPassEvent.class, new DhApiBeforeRenderPassEvent() {
            @Override
            public void beforeRender(DhApiEventParam<DhApiRenderParam> p) {
                if (p.value == null) return;
                near = p.value.nearClipPlane;
                far = p.value.farClipPlane;
                // its projection's inverse, with DH's own matrix class and conversion (the view distance of a depth comes
                // from this, not from near and far: AdventurAgent's lake read as farther than it is)
                lastProj = String.valueOf(new com.seibel.distanthorizons.core.util.math.DhMat4f(p.value.dhProjectionMatrix).createJomlMatrix());
                lastMv = String.valueOf(new com.seibel.distanthorizons.core.util.math.DhMat4f(p.value.dhModelViewMatrix).createJomlMatrix());
                lastMcProj = String.valueOf(new com.seibel.distanthorizons.core.util.math.DhMat4f(p.value.mcProjectionMatrix).createJomlMatrix());
                var m = new com.seibel.distanthorizons.core.util.math.DhMat4f(p.value.dhProjectionMatrix);
                m.invert();
                inverseProjection = m.createJomlMatrix();
            }
        });
    }

    /** DH's far plane (blocks), 0 before its first render pass. */
    public static float farBlocks() {
        float f = far;
        return Float.isNaN(f) ? 0 : f;
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
     * convention (0: 0..1 forward, 1: -1..1 forward, 2: 0..1 reversed, 3: -1..1 reversed), [1704] the debug picture.
     * The shader takes the ray from vanilla's matrices and only the distance along it from DH's depth (AdventurAgent:
     * continuous with vanilla's across the line where DH takes over; DH's own matrices weren't).
     */
    static int debugCheck;
    static boolean debug;
    /** The debug file's first word: "parts" (2: the verdict's parts), else the grid (1). */
    static int debugMode;

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
            var ip = inverseProjection;
            if (ip == null) {
                d[1700] = 0;
                return;
            }
            ip.get(d, 1706);   // [1706..1721]
            // (a debug picture while the file config/stasisdelvers-dhdebug exists: stasis.fsh)
            if ((debugCheck++ & 63) == 0) {
                var file = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("stasisdelvers-dhdebug");
                debug = java.nio.file.Files.exists(file);
                debugMode = debug && java.nio.file.Files.readString(file).trim().startsWith("parts") ? 2 : 1;
                // (the file may hold "x z": the client's spread verdict there, to the log, against the server's /stasis at)
                if (debug) try {
                    String[] xz = java.nio.file.Files.readString(file).trim().replace("parts", "").trim().split("\\s+");
                    if (xz.length >= 2) {
                        int x = Integer.parseInt(xz[0]), z = Integer.parseInt(xz[1]);
                        var st = org.erisia.stasis.Spread.client;
                        var map = st.map();
                        org.erisia.stasis.StasisDelvers.LOG.info("DH debug: near {} far {} depth {} {} dhProj {} dhMV {} mcProj {}", near, far,
                            DhApi.Delayed.renderProxy.getDepthDirection(), DhApi.Delayed.renderProxy.getDepthRange(), lastProj, lastMv, lastMcProj);
                        org.erisia.stasis.StasisDelvers.LOG.info("DH debug: client spread at {} {}: runs {}, rank1024 {}, P {} (slot {}), tiles {}, rateAt {}",
                            x, z, map == null || !org.erisia.stasis.SpreadMap.stilled(map, x, z, st.p()),
                            map == null ? "no map" : org.erisia.stasis.SpreadMap.rank1024(map, x, z), st.p(), d[197],
                            org.erisia.stasis.Spread.clientTiles.size(),
                            org.erisia.stasis.Stasis.rateAt(net.minecraft.client.Minecraft.getInstance().level, x, 63, z));
                    }
                } catch (Exception ignored) {
                }
            }
            d[1704] = debug ? debugMode : 0;
        } catch (Throwable t) {
            d[1700] = 0;
        }
    }
}

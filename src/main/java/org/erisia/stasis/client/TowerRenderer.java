package org.erisia.stasis.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.PrepareRenderBuffersEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.erisia.stasis.StasisDelvers;
import org.erisia.stasis.TowerBlockEntity;
import org.erisia.stasis.TowerStructure;
import org.erisia.stasis.towergen.Layout;
import org.erisia.stasis.towergen.Library;
import org.erisia.stasis.towergen.Mesh;
import org.erisia.stasis.towergen.MeshBuilder;
import org.erisia.stasis.towergen.TowerGen;
import org.erisia.stasis.towergen.VisualParams;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Draws the generated towers (P3d). The mesh of a built tower (status OK on the server) is generated here off-thread
 * from the synced layout, written once into a GPU vertex buffer, and drawn every frame after the opaque terrain with
 * our own pipeline (core/tower: vanilla block shader minus the texture; lightmap and fog). Two buffers per tower: the
 * full mesh and a far LOD without the lacing. Notes: notes/timestop/P3D-RENDER-API.md.
 */
public final class TowerRenderer {
    private TowerRenderer() {
    }

    public static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "pipeline/tower"))
        .withVertexShader(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "core/tower"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "core/tower"))
        .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_LIGHTMAP)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withCull(false)
        .build();

    /** Beyond this distance (blocks) the LOD without lacing is drawn. */
    static final double LOD_DIST = 48;
    static final int VERTEX_BYTES = 20;
    /** No drawn core: the girders reach the ground, as on towers.brage.info (the 1x1x2 core blocks stay visible: they are the console). */
    static final VisualParams VP = VisualParams.fromJson(org.erisia.stasis.towergen.Json.parse(
        "{\"drawCore\": false}"));

    private static final ExecutorService BUILD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stasis-towermesh");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static TowerGen gen;        // build thread only

    record Built(String key, ByteBuffer full, int nFull, ByteBuffer lod, int nLod, AABB box) {
    }

    static final class Entry {
        final BlockPos core;
        String key;                     // of the drawn mesh
        String pendingKey;
        CompletableFuture<Built> pending;
        GpuBuffer full, lod;
        int nFull, nLod;
        AABB box;                       // world space

        Entry(BlockPos core) {
            this.core = core;
        }

        void close() {
            if (full != null) full.close();
            if (lod != null) lod.close();
            full = lod = null;
        }
    }

    /** Client (render) thread only. */
    private static final Map<BlockPos, Entry> TOWERS = new HashMap<>();

    public static void registerPipelines(RegisterRenderPipelinesEvent e) {
        e.registerPipeline(PIPELINE);
    }

    /** A tower block entity got new data from the server. */
    public static void updated(TowerBlockEntity be) {
        TowerStructure s = be.structure;
        BlockPos core = be.getBlockPos().immutable();
        switch (s.status) {
            case OK -> {
                if (s.layout == null) return;
                String key = s.layout + "#" + s.seed;
                Entry e = TOWERS.computeIfAbsent(core, Entry::new);
                if (key.equals(e.pendingKey)) return;
                if (key.equals(e.key)) {
                    // back to the drawn layout (A, then B, then A again): B's build must not replace A when it lands
                    discard(e.pending);
                    e.pending = null;
                    e.pendingKey = null;
                    return;
                }
                Layout layout = s.layout;
                int seed = s.seed;
                // a build still in flight for an older layout: nobody will upload it, so free its buffers when it lands
                discard(e.pending);
                e.pendingKey = key;
                e.pending = CompletableFuture.supplyAsync(() -> build(key, layout, seed), BUILD);
            }
            case WAITING, BUILDING -> {
                // keep drawing the last tower until the new one is built
            }
            default -> drop(core);
        }
    }

    public static void removed(TowerBlockEntity be) {
        drop(be.getBlockPos());
    }

    private static void drop(BlockPos core) {
        Entry e = TOWERS.remove(core);
        if (e != null) {
            discard(e.pending);
            e.close();
        }
    }

    /** Bug reports: "pos key nFull nLod" per tower (client ticks run on the render thread, like the rest of this). */
    static java.util.List<String> summary() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Entry e : TOWERS.values())
            out.add(e.core.toShortString() + " " + e.key + " " + e.nFull + " " + e.nLod + (e.pending != null ? " pending=" + e.pendingKey : ""));
        return out;
    }

    public static void clear() {
        for (Entry e : TOWERS.values()) {
            discard(e.pending);
            e.close();
        }
        TOWERS.clear();
    }

    /** A build (or null) whose result will never be uploaded: free its native buffers once it finishes. */
    private static void discard(CompletableFuture<Built> pending) {
        if (pending != null) pending.thenAccept(TowerRenderer::free);
    }

    private static void free(Built b) {
        if (b == null) return;
        if (b.full != null) MemoryUtil.memFree(b.full);
        if (b.lod != null) MemoryUtil.memFree(b.lod);
    }

    static Built build(String key, Layout layout, int seed) {
        try {
            if (gen == null) gen = new TowerGen(Library.shared());
            gen.seed = seed;
            TowerGen.Result res = gen.build(layout, TowerStructure.Q);
            VisualParams vp = VP;
            Mesh m = new MeshBuilder(res.c, vp).build(res);
            int lodTris = 0;
            float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int t = 0; t < m.tris; t++) {
                if (m.part[t] != VisualParams.Part.LACING.ordinal()) lodTris++;
                for (int k = 0; k < 9; k++) {
                    float v = m.pos[9 * t + k];
                    lo[k % 3] = Math.min(lo[k % 3], v);
                    hi[k % 3] = Math.max(hi[k % 3], v);
                }
            }
            ByteBuffer full = MemoryUtil.memAlloc(Math.max(1, m.tris * 3 * VERTEX_BYTES));
            ByteBuffer lod = MemoryUtil.memAlloc(Math.max(1, lodTris * 3 * VERTEX_BYTES));
            VisualParams.Part[] parts = VisualParams.Part.values();
            for (int t = 0; t < m.tris; t++) {
                VisualParams.Part p = parts[m.part[t]];
                double[] c = vp.colour(p);
                boolean glow = p == VisualParams.Part.GLOW;
                double nx = m.nrm[3 * t], ny = m.nrm[3 * t + 1], nz = m.nrm[3 * t + 2];
                // vanilla-like directional shade: top 1.0, bottom 0.5, north/south 0.8, east/west 0.6
                double shade = glow ? 1.0 : 0.6 * nx * nx + 0.8 * nz * nz + (ny > 0 ? 1.0 : 0.5) * ny * ny;
                int r = clamp(c[0] * shade), g = clamp(c[1] * shade), b = clamp(c[2] * shade);
                short block = (short) (glow ? 240 : 0), sky = 240;
                for (int v = 0; v < 3; v++) {
                    put(full, m.pos, 9 * t + 3 * v, r, g, b, block, sky);
                    if (p != VisualParams.Part.LACING) put(lod, m.pos, 9 * t + 3 * v, r, g, b, block, sky);
                }
            }
            full.flip();
            lod.flip();
            return new Built(key, full, m.tris * 3, lod, lodTris * 3, new AABB(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]));
        } catch (RuntimeException e) {
            StasisDelvers.LOG.warn("Tower mesh failed for {}", layout, e);
            return null;
        }
    }

    private static void put(ByteBuffer bb, float[] pos, int o, int r, int g, int b, short block, short sky) {
        bb.putFloat(pos[o]).putFloat(pos[o + 1]).putFloat(pos[o + 2]);
        bb.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
        bb.putShort(block).putShort(sky);
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    /** The tower's origin in the world: the core's centre, on the ground (its bottom face; TowerStructure). */
    static Vec3 origin(BlockPos core) {
        return new Vec3(core.getX() + 0.5, core.getY(), core.getZ() + 0.5);
    }

    /** Uploads finished meshes (no render pass is open here). */
    public static void prepare(PrepareRenderBuffersEvent ev) {
        for (Entry e : TOWERS.values()) {
            if (e.pending == null || !e.pending.isDone()) continue;
            Built b = e.pending.getNow(null);
            String key = e.pendingKey;
            e.pending = null;
            e.pendingKey = null;
            if (b == null || !b.key().equals(key)) {
                free(b);
                continue;
            }
            e.close();
            var device = RenderSystem.getDevice();
            if (b.nFull() > 0) e.full = device.createBuffer(() -> "stasis tower " + e.core.toShortString(), GpuBuffer.USAGE_VERTEX, b.full());
            if (b.nLod() > 0) e.lod = device.createBuffer(() -> "stasis tower lod " + e.core.toShortString(), GpuBuffer.USAGE_VERTEX, b.lod());
            e.nFull = b.nFull();
            e.nLod = b.nLod();
            e.box = b.box().move(origin(e.core)).inflate(1);
            e.key = b.key();
            free(b);
        }
    }

    public static void draw(RenderLevelStageEvent.AfterOpaqueBlocks ev) {
        if (TOWERS.isEmpty()) return;
        var cam = ev.getLevelRenderState().cameraRenderState;
        Vec3 cp = cam.pos;
        double view = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        RenderPass pass = null;
        for (Entry e : TOWERS.values()) {
            if (e.full == null || e.box == null) continue;
            if (!cam.cullFrustum.isVisible(e.box)) continue;
            double dist = Math.sqrt(e.box.distanceToSqr(cp));
            if (dist > view) continue;
            boolean far = dist > LOD_DIST && e.lod != null;
            if (pass == null) {
                pass = ev.getRenderPass();
                pass.pushDebugGroup(() -> "stasis towers");
                pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            }
            Vec3 o = origin(e.core);
            var dt = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(), new Vector4f(1, 1, 1, 1),
                new Vector3f((float) (o.x - cp.x), (float) (o.y - cp.y), (float) (o.z - cp.z)), new Matrix4f());
            pass.setUniform("DynamicTransforms", dt);
            pass.setVertexBuffer(0, (far ? e.lod : e.full).slice());
            pass.draw(far ? e.nLod : e.nFull, 1, 0, 0);
        }
        if (pass != null) pass.popDebugGroup();
    }
}

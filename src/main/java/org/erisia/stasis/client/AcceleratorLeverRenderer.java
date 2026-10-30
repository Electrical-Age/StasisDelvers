package org.erisia.stasis.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.erisia.stasis.AcceleratorLevers;
import org.erisia.stasis.ChronalAcceleratorBlock;
import org.erisia.stasis.TemporalAcceleratorBlock;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The accelerators' lever (Baughn: in-world controls, with an animated lever): a dark base plate on the face last
 * clicked, and a handle that swings from -45° at the lowest setting to +45° at 10x, easing to a new setting over about
 * a third of a second (AcceleratorLevers sets both).
 */
public class AcceleratorLeverRenderer<T extends BlockEntity & AcceleratorLevers.Lever> implements BlockEntityRenderer<T, AcceleratorLeverRenderer.State> {
    static final Identifier BASE = Identifier.withDefaultNamespace("textures/block/polished_blackstone.png");
    static final Identifier ROD = Identifier.withDefaultNamespace("textures/block/iron_block.png");
    static final Identifier KNOB = Identifier.withDefaultNamespace("textures/block/gold_block.png");

    public static class State extends BlockEntityRenderState {
        int face;
        float angle;
    }

    public AcceleratorLeverRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    /** The handle's shown angle per lever (degrees) and when it was last drawn: eased towards the setting's. */
    static final java.util.Map<BlockPos, float[]> SHOWN = new java.util.concurrent.ConcurrentHashMap<>();

    static float target(BlockState st) {
        if (st.getBlock() instanceof TemporalAcceleratorBlock) return -45 + 90 * st.getValue(TemporalAcceleratorBlock.STEP) / 18f;
        if (st.getBlock() instanceof ChronalAcceleratorBlock) return -45 + 90 * st.getValue(ChronalAcceleratorBlock.STEP) / 18f;
        return 0;
    }

    @Override
    public void extractRenderState(T be, State state, float partialTicks, Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, cameraPosition, breakProgress);
        state.face = be.leverFace();
        // lit as the space in front of its face (inside the block, the light is nil: AdventurAgent saw a black lever)
        if (be.getLevel() != null)
            state.lightCoords = net.minecraft.util.LightCoordsUtil.getLightCoords(be.getLevel(),
                be.getBlockPos().relative(net.minecraft.core.Direction.from3DDataValue(state.face)));
        float want = target(be.getBlockState());
        long now = System.nanoTime();
        float[] s = SHOWN.computeIfAbsent(be.getBlockPos().immutable(), k -> new float[]{want, now});
        float dt = Math.min(0.25f, (now - s[1]) / 1e9f);
        s[0] += (want - s[0]) * Math.min(1, dt * 12);
        s[1] = now;
        if (SHOWN.size() > 512) SHOWN.clear();
        state.angle = s[0];
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);
        switch (s.face) {   // (the lever is built on the top face; turned onto the face it's on)
            case 0 -> pose.rotateDegrees(Axis.XP, 180);
            case 2 -> pose.rotateDegrees(Axis.XP, -90);
            case 3 -> pose.rotateDegrees(Axis.XP, 90);
            case 4 -> pose.rotateDegrees(Axis.ZP, 90);
            case 5 -> pose.rotateDegrees(Axis.ZP, -90);
            default -> {
            }
        }
        pose.translate(-0.5f, -0.5f, -0.5f);
        float a = (float) Math.toRadians(s.angle);
        Vector3f pivot = new Vector3f(0.5f, 1.02f, 0.5f);
        Vector3f tip = new Vector3f(pivot).add((float) Math.sin(a) * 0.42f, (float) Math.cos(a) * 0.42f, 0);
        out.submitCustomGeometry(pose, RenderTypes.entitySolid(BASE), (p, vc) ->
            QuernRenderer.box(p, vc, new Vector3f(0.5f, 1.0f, 0.5f), new Vector3f(0.5f, 1.07f, 0.5f), 0.32f, s.lightCoords));
        out.submitCustomGeometry(pose, RenderTypes.entitySolid(ROD), (p, vc) -> QuernRenderer.box(p, vc, pivot, tip, 0.06f, s.lightCoords));
        Vector3f dir = new Vector3f(tip).sub(pivot).normalize();
        out.submitCustomGeometry(pose, RenderTypes.entitySolid(KNOB), (p, vc) ->
            QuernRenderer.box(p, vc, new Vector3f(tip).sub(new Vector3f(dir).mul(0.05f)), new Vector3f(tip).add(new Vector3f(dir).mul(0.05f)), 0.11f, s.lightCoords));
        pose.popPose();
    }
}

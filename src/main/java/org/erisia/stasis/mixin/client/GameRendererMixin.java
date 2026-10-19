package org.erisia.stasis.mixin.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.erisia.stasis.client.StasisPost;
import org.erisia.stasis.client.SuitArm;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Keeps a suited player's first-person hand (and what it holds) in colour in stopped land. Vanilla 26.3 draws the
 * hand pass in render3dHud, inside renderLevel, i.e. before applyPostEffects; with any post effect active it also
 * copies the hand's depth into the main depth buffer (integrate_depth), so the desaturation saw the hand as a surface
 * a few decimetres in front of the camera, in stopped land, and greyed it. There is no clean mask for the hand in
 * the post shader (that depth is indistinguishable from a wall right in front of the player), so instead: when the
 * stasis effect is applied and the player wears a suit, skip the hand pass there and run it right after the post
 * chain, with the same projection, fog and lighting.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private GameRenderState gameRenderState;
    @Shadow @Final private RenderTarget hud3DTarget;
    @Shadow @Final private Projection hudProjection;
    @Shadow @Final private ProjectionMatrixBuffer hud3dProjectionMatrixBuffer;
    @Shadow @Final private FogRenderer fogRenderer;
    @Shadow @Final private List<PostChain> appliedPostEffects;

    @Shadow
    private void renderItemInHand(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView) {
        throw new AssertionError();
    }

    /** The hand pass waits for the post chain this frame. */
    @Unique private boolean stasis$lateHand;
    /** True while the late pass runs (lets renderItemInHand through). */
    @Unique private boolean stasis$drawingLate;
    /**
     * Set once the late pass's hook has run: this mixin config is optional (a mod may have changed GameRenderer.render),
     * and without the late pass the hand must not be skipped, or it would vanish.
     */
    @Unique private boolean stasis$lateHookLive;

    @Inject(method = "render3dHud", at = @At("HEAD"))
    private void stasis$decide(CameraRenderState cameraState, PlayerRenderState playerState, OptionsRenderState optionsState,
                               boolean consistentDepthRequired, CallbackInfo ci) {
        stasis$lateHand = false;
        if (!SuitArm.localPlayerSuited()) return;
        for (PostChain chain : appliedPostEffects)
            if (chain.id().equals(StasisPost.EFFECT)) {
                stasis$lateHand = true;
                return;
            }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void stasis$skipEarly(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView, CallbackInfo ci) {
        if (stasis$lateHand && stasis$lateHookLive && !stasis$drawingLate) ci.cancel();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
    private void stasis$lateHand(CallbackInfo ci) {
        stasis$lateHookLive = true;
        if (!stasis$lateHand) return;
        stasis$lateHand = false;
        var levelState = gameRenderState.levelRenderState;
        // as render3dHud left it before the hand: the hud projection (hudProjection still holds this frame's
        // perspective), world fog, a cleared hud depth target; lighting is still the level's (ITEMS_3D comes later)
        RenderSystem.setProjectionMatrix(hud3dProjectionMatrixBuffer.getBuffer(hudProjection), ProjectionType.PERSPECTIVE);
        RenderSystem.setShaderFog(fogRenderer.getBuffer(FogRenderer.FogMode.WORLD));
        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(hud3DTarget.getDepthTexture(), 0.0);
        stasis$drawingLate = true;
        try {
            renderItemInHand(levelState.cameraRenderState, levelState.playerRenderState, hud3DTarget.getDepthTextureView());
        } finally {
            stasis$drawingLate = false;
            RenderSystem.setShaderFog(fogRenderer.getBuffer(FogRenderer.FogMode.NONE));
        }
    }
}

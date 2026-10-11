package org.erisia.stasis.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.erisia.stasis.Statue;
import org.erisia.stasis.StasisDelvers;

/** A frozen player: the player model in stone grey, facing the way they faced. */
public class StatueRenderer extends EntityRenderer<Statue, HumanoidRenderState> {
    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "textures/entity/statue.png");
    private final HumanoidModel<HumanoidRenderState> model;

    public StatueRenderer(EntityRendererProvider.Context context) {
        super(context);
        model = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER));
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    @Override
    public void extractRenderState(Statue statue, HumanoidRenderState state, float partialTicks) {
        super.extractRenderState(statue, state, partialTicks);
        state.bodyRot = statue.getYRot();
        state.yRot = 0;
    }

    @Override
    public void submit(HumanoidRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.rotate(Axis.YP.rotationDegrees(180.0F - state.bodyRot));
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.501F, 0.0F);
        collector.submitModel(model, state, poseStack, TEXTURE, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }
}

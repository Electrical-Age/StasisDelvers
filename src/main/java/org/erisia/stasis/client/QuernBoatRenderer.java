package org.erisia.stasis.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.object.boat.BoatModel;
import net.minecraft.client.model.object.boat.RaftModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.AbstractBoatRenderer;
import net.minecraft.client.renderer.entity.AbstractMinecartRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.erisia.stasis.QuernVehicles;
import org.erisia.stasis.StasisRegistry;

/**
 * The Quern Boat (λ-Nero 2026-11-09): its wood's plain boat (or raft) with the rift quern block standing where a chest
 * boat's chest sits, at the stern.
 */
public class QuernBoatRenderer extends AbstractBoatRenderer {
    /** The render state, with the quern block's model. */
    public static class State extends BoatRenderState {
        public final BlockModelRenderState quern = new BlockModelRenderState();
    }

    private final EntityModel<BoatRenderState> model;
    private final BlockModelResolver blocks;

    public QuernBoatRenderer(EntityRendererProvider.Context context, String wood) {
        this(context, new ModelLayerLocation(Identifier.withDefaultNamespace("boat/" + wood), "main"), QuernVehicles.raft(wood));
    }

    private QuernBoatRenderer(EntityRendererProvider.Context context, ModelLayerLocation layer, boolean raft) {
        super(context, layer.model().withPath(p -> "textures/entity/" + p + ".png"));
        model = raft ? new RaftModel(context.bakeLayer(layer)) : new BoatModel(context.bakeLayer(layer));
        blocks = context.getBlockModelResolver();
    }

    @Override
    protected EntityModel<BoatRenderState> model() {
        return model;
    }

    @Override
    public BoatRenderState createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(AbstractBoat entity, BoatRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        if (state instanceof State s)
            blocks.update(s.quern, StasisRegistry.QUERN.get().defaultBlockState(), AbstractMinecartRenderer.BLOCK_DISPLAY_CONTEXT);
    }

    /** Called inside the boat model's frame (AbstractBoatRenderer.submit: flipped, turned 90 degrees): the quern at the stern. */
    @Override
    protected void submitTypeAdditions(BoatRenderState state, PoseStack pose, SubmitNodeCollector out, int light) {
        if (!(state instanceof State s) || s.quern.isEmpty()) return;
        pose.pushPose();
        // (undo the model's flip, so the block stands upright; the stern is +x in the model's frame)
        pose.scale(-1.0f, -1.0f, 1.0f);
        pose.translate(-0.55f, -0.1f, 0.0f);
        pose.scale(0.6f, 0.6f, 0.6f);
        pose.translate(-0.5f, 0.0f, -0.5f);
        s.quern.submit(pose, out, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
        pose.popPose();
    }
}

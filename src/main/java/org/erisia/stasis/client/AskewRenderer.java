package org.erisia.stasis.client;

import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.enderman.EndermanModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.state.EndermanRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.erisia.stasis.RiftAskew;

/**
 * The Askew: the enderman model with its joints bent the wrong way and its head turned too far (Baughn: "limbs quite
 * askew"), each one differently (its id seeds the angles), with a slow drift so it never quite settles. Purple eyes as
 * an enderman's; in stopped land the post shader draws them too (PossessedLook).
 */
public class AskewRenderer extends MobRenderer<RiftAskew, AskewRenderer.State, AskewRenderer.Model> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/enderman/enderman.png");

    public static class State extends EndermanRenderState {
        int seed;
    }

    public static class Model extends EndermanModel<State> {
        public Model(ModelPart root) {
            super(root);
        }

        @Override
        public void setupAnim(State s) {
            super.setupAnim(s);
            java.util.Random r = new java.util.Random(s.seed * 31L + 7);
            float t = s.ageInTicks * 0.02f, drift = (float) Math.sin(t + s.seed) * 0.08f;
            head.yRot += (r.nextBoolean() ? 1 : -1) * (1.6f + r.nextFloat() * 0.8f);   // (turned too far: 90-130°)
            head.zRot += (r.nextFloat() - 0.5f) * 1.2f + drift;
            hat.yRot = head.yRot;
            hat.zRot = head.zRot;
            rightArm.xRot += -2.2f - r.nextFloat() * 0.8f;   // (bent back, up behind it)
            rightArm.zRot += 0.4f + r.nextFloat() * 0.4f + drift;
            leftArm.xRot += 0.8f + r.nextFloat() * 0.6f;
            leftArm.zRot += -1.1f - r.nextFloat() * 0.5f - drift;
            rightLeg.zRot += 0.25f + r.nextFloat() * 0.2f;
            leftLeg.xRot += -0.35f - r.nextFloat() * 0.25f;
            body.zRot += (r.nextFloat() - 0.5f) * 0.3f;
        }
    }

    public AskewRenderer(EntityRendererProvider.Context context) {
        super(context, new Model(context.bakeLayer(ModelLayers.ENDERMAN)), 0.5f);
        addLayer(new EyesLayer<>(this) {
            @Override
            public net.minecraft.client.renderer.rendertype.RenderType renderType() {
                return RenderTypes.eyes(Identifier.withDefaultNamespace("textures/entity/enderman/enderman_eyes.png"));
            }
        });
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(RiftAskew entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.seed = entity.getId();
    }

    @Override
    public Identifier getTextureLocation(State state) {
        return TEXTURE;
    }
}

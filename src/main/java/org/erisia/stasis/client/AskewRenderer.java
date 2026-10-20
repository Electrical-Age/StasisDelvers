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
 * The Askew: the enderman model with its joints bent the wrong way and its head lolled over (Baughn: "limbs quite
 * askew"; arms above the head looked like a dance), one pose with small variations per entity (its id), and a slow
 * drift so it never quite settles. Purple eyes as
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

        /**
         * One pose, with a little per-entity variation (Baughn: arms above the head read as a strange dance): arms hanging
         * but bent back behind it, too far apart; the neck broken, the head lolled over and turned; the body twisted on
         * its hips; one leg turned back.
         */
        @Override
        public void setupAnim(State s) {
            super.setupAnim(s);
            java.util.Random r = new java.util.Random(s.seed * 31L + 7);
            float side = r.nextBoolean() ? 1 : -1, v = 0.15f;
            float drift = (float) Math.sin(s.ageInTicks * 0.02f + s.seed) * 0.05f;
            head.zRot += side * (1.25f + r.nextFloat() * v) + drift;    // (lolled over, about 75 degrees)
            head.yRot += -side * (0.9f + r.nextFloat() * v);            // (and turned away from the tilt)
            hat.zRot = head.zRot;
            hat.yRot = head.yRot;
            body.yRot += side * (0.35f + r.nextFloat() * v);            // (twisted on its hips)
            rightArm.xRot += 0.55f + r.nextFloat() * v;                 // (both arms hanging, bent back behind it)
            leftArm.xRot += 0.4f + r.nextFloat() * v;
            rightArm.zRot += 0.22f + r.nextFloat() * 0.08f + drift;    // (and held a little too far out)
            leftArm.zRot += -0.3f - r.nextFloat() * 0.08f;
            rightArm.yRot += side * 0.6f;                               // (one turned the wrong way round)
            (side > 0 ? leftLeg : rightLeg).xRot += 0.45f + r.nextFloat() * v;   // (one leg turned back)
            (side > 0 ? rightLeg : leftLeg).zRot += side * 0.12f;
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

package org.erisia.stasis.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import org.erisia.stasis.PossessedStatue;

/** A possessed statue: its owner, skin and worn armour (the eyes and the haze come from PossessedLook and StasisPost). */
public class PossessedStatueRenderer extends HumanoidMobRenderer<PossessedStatue, PossessedStatueRenderer.State, HumanoidModel<PossessedStatueRenderer.State>> {
    public static class State extends HumanoidRenderState {
        Identifier skin = DefaultPlayerSkin.getDefaultTexture();
    }

    public PossessedStatueRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
        addLayer(new HumanoidArmorLayer<>(this, ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), HumanoidModel::new),
            context.getEquipmentRenderer()));
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(PossessedStatue entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.skin = StatueRenderer.skin(entity.syncedOwnerId(), entity.syncedOwnerName());
    }

    @Override
    public Identifier getTextureLocation(State state) {
        return state.skin;
    }
}

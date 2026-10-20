package org.erisia.stasis.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.erisia.stasis.Statue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A frozen player: the player model in the player's own skin, facing the way they faced. It stands in stopped land,
 * so the stasis post effect shows it nearly grey (Baughn: a greyscale version of the player's texture).
 */
public class StatueRenderer extends EntityRenderer<Statue, StatueRenderer.State> {
    public static class State extends HumanoidRenderState {
        Identifier skin = DefaultPlayerSkin.getDefaultTexture();
        boolean fallen;
    }

    private final HumanoidModel<HumanoidRenderState> model;
    private static final Map<UUID, Supplier<PlayerSkin>> SKINS = new HashMap<>();

    public StatueRenderer(EntityRendererProvider.Context context) {
        super(context);
        model = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER));
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    static Identifier skin(Statue s) {
        return skin(s.syncedOwnerId(), s.syncedOwnerName());
    }

    /** The skin of the player with this id (string) and name; the default skin if the id is no UUID. */
    static Identifier skin(String ownerId, String ownerName) {
        UUID id;
        try {
            id = UUID.fromString(ownerId);
        } catch (IllegalArgumentException e) {
            return DefaultPlayerSkin.getDefaultTexture();
        }
        var info = Minecraft.getInstance().getConnection() == null ? null : Minecraft.getInstance().getConnection().getPlayerInfo(id);
        if (info != null) return info.getSkin().body().texturePath();
        String name = ownerName.isEmpty() ? "statue" : ownerName;
        Supplier<PlayerSkin> lookup = SKINS.computeIfAbsent(id,
            u -> Minecraft.getInstance().getSkinManager().createLookup(new GameProfile(u, name), false));
        return lookup.get().body().texturePath();
    }

    @Override
    public void extractRenderState(Statue statue, State state, float partialTicks) {
        super.extractRenderState(statue, state, partialTicks);
        state.bodyRot = statue.getYRot();
        state.yRot = 0;
        state.skin = skin(statue);
        state.fallen = statue.fallen();
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.rotate(Axis.YP.rotationDegrees(180.0F - state.bodyRot));
        if (state.fallen) {
            // remains: on its back, as a sleeping player lies (LivingEntityRenderer's sleep rotations), raised off the ground,
            // and centred on the entity so the (square) hitbox covers it (AdventurAgent: only the feet were clickable)
            poseStack.translate(0.9F, 0.26F, 0.0F);
            poseStack.rotate(Axis.ZP.rotationDegrees(90.0F));
            poseStack.rotate(Axis.YP.rotationDegrees(270.0F));
        }
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.501F, 0.0F);
        collector.submitModel(model, state, poseStack, state.skin, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }
}

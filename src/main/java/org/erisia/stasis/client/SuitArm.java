package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import org.erisia.stasis.StasisDelvers;
import org.erisia.stasis.TimeSuit;
import org.jspecify.annotations.Nullable;

/**
 * The first-person arm of a suited player: the suit's sleeve (the chestplate's armour layer on the armour arm, as in
 * third person; player armour is always the wide arm, so slim and wide skins get the same sleeve) and a glove
 * (textures/entity/glove/&lt;asset&gt;.png, from script/gen_assets.py) over the hand, instead of the skin arm. Any
 * {@link TimeSuit} chestplate with an equipment asset of ours counts (the Delver suit and the stasis E-Coal armour).
 * In stopped land the whole hand pass is drawn after the desaturation (mixin/GameRendererMixin), so it keeps its colour.
 */
public final class SuitArm {
    private SuitArm() {
    }

    private static ModelPart sleeves, gloves;

    /** The suit's equipment asset name (e.g. "delver") if the chest slot holds a suit of ours, else null. */
    static @Nullable String suitAsset(ItemStack chest) {
        if (!(chest.getItem() instanceof TimeSuit)) return null;
        Equippable eq = chest.get(DataComponents.EQUIPPABLE);
        if (eq == null || eq.assetId().isEmpty()) return null;
        Identifier id = eq.assetId().get().identifier();
        return id.getNamespace().equals(StasisDelvers.MODID) ? id.getPath() : null;
    }

    /** Whether the local player's first-person hand should be drawn as the suit (and so after the post effect). */
    public static boolean localPlayerSuited() {
        var player = Minecraft.getInstance().player;
        return player != null && suitAsset(player.getItemBySlot(EquipmentSlot.CHEST)) != null;
    }

    private static void build() {
        // the arm, a little bigger than the skin's (the vanilla armour deformation 1.0 was far too big in first person)
        MeshDefinition m = new MeshDefinition();
        PartDefinition r = m.getRoot();
        CubeDeformation armour = new CubeDeformation(0.3F);   // (the armour layer's 1.0 made a huge hand in first person: λ-Nero)
        r.addOrReplaceChild("right", CubeListBuilder.create().texOffs(40, 16).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, armour), PartPose.ZERO);
        r.addOrReplaceChild("left", CubeListBuilder.create().texOffs(40, 16).mirror().addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, armour), PartPose.ZERO);
        sleeves = LayerDefinition.create(m, 64, 32).bakeRoot();
        // the glove over the bottom third of the arm (the hand), a little bigger than the sleeve so it sits on top;
        // a cuff band above it, bigger again
        MeshDefinition g = new MeshDefinition();
        PartDefinition gr = g.getRoot();
        CubeDeformation cuff = new CubeDeformation(0.55F), hand = new CubeDeformation(0.4F);
        gr.addOrReplaceChild("right", CubeListBuilder.create()
            .texOffs(0, 0).addBox(-3.0F, 5.0F, -2.0F, 4.0F, 1.0F, 4.0F, cuff)
            .texOffs(0, 5).addBox(-3.0F, 6.0F, -2.0F, 4.0F, 4.0F, 4.0F, hand), PartPose.ZERO);
        gr.addOrReplaceChild("left", CubeListBuilder.create().mirror()
            .texOffs(0, 0).addBox(-1.0F, 5.0F, -2.0F, 4.0F, 1.0F, 4.0F, cuff)
            .texOffs(0, 5).addBox(-1.0F, 6.0F, -2.0F, 4.0F, 4.0F, 4.0F, hand), PartPose.ZERO);
        gloves = LayerDefinition.create(g, 32, 16).bakeRoot();
    }

    static void renderArm(RenderArmEvent e) {
        var avatar = e.getPlayerRenderState().avatarRenderState;
        if (avatar == null) return;
        String asset = suitAsset(avatar.chestEquipment);
        if (asset == null) return;
        e.setCanceled(true);
        if (sleeves == null) build();
        String side = e.getArm() == HumanoidArm.RIGHT ? "right" : "left";
        ModelPart sleeve = sleeves.getChild(side), glove = gloves.getChild(side);
        // the pose vanilla gives the skin arm (AvatarRenderer.renderHand): the model's rest pose, tilted 0.1 rad out
        ModelPart arm = e.getArmPart();
        arm.resetPose();
        arm.zRot = e.getArm() == HumanoidArm.RIGHT ? 0.1F : -0.1F;
        PartPose pose = arm.storePose();
        sleeve.loadPose(pose);
        glove.loadPose(pose);
        var out = e.getSubmitNodeCollector();
        Identifier sleeveTex = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "textures/entity/equipment/humanoid/" + asset + ".png");
        Identifier gloveTex = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "textures/entity/glove/" + asset + ".png");
        out.submitModelPart(sleeve, e.getPoseStack(), RenderTypes.armorCutoutNoCull(sleeveTex), e.getLightCoords(), OverlayTexture.NO_OVERLAY, null);
        out.submitModelPart(glove, e.getPoseStack(), RenderTypes.armorCutoutNoCull(gloveTex), e.getLightCoords(), OverlayTexture.NO_OVERLAY, null);
    }
}

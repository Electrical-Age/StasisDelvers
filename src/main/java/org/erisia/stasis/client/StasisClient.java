package org.erisia.stasis.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.erisia.stasis.StasisDelvers;
import org.erisia.stasis.StasisRegistry;

/** Client side: statue model, shield geometry, the desaturation post effect, the freeze warning and the suit arm. */
@Mod(value = StasisDelvers.MODID, dist = Dist.CLIENT)
public final class StasisClient {
    public StasisClient(IEventBus modBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> e.registerEntityRenderer(StasisRegistry.STATUE.get(), StatueRenderer::new));
        // the shield is drawn by the post effect now (so the desaturation can't grey it); ShieldRenderer is kept for
        // a possible later geometry version (falling cells)
        NeoForge.EVENT_BUS.addListener(FrameGraphSetupEvent.class, StasisPost::frame);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, StasisPost::tick);
        // a suited player's first-person arm is the suit (sleeve and glove); drawn after the desaturation in stopped
        // land (mixin/client/GameRendererMixin)
        NeoForge.EVENT_BUS.addListener(RenderArmEvent.class, SuitArm::renderArm);
    }
}

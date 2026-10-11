package org.erisia.stasis.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.erisia.stasis.StasisDelvers;
import org.erisia.stasis.StasisRegistry;

/** Client side: statue model, shield geometry, the desaturation post effect and the freeze warning. */
@Mod(value = StasisDelvers.MODID, dist = Dist.CLIENT)
public final class StasisClient {
    public StasisClient(IEventBus modBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> e.registerEntityRenderer(StasisRegistry.STATUE.get(), StatueRenderer::new));
        NeoForge.EVENT_BUS.addListener(SubmitCustomGeometryEvent.class, ShieldRenderer::submit);
        NeoForge.EVENT_BUS.addListener(FrameGraphSetupEvent.class, StasisPost::frame);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, StasisPost::tick);
    }
}

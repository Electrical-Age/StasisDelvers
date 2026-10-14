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
        // flames' light in chunk meshes (smooth lighting) depends on where they are
        org.erisia.stasis.Flames.clientResolver = g -> g instanceof org.erisia.stasis.mixin.client.RenderSectionRegionAccessor r ? r.stasis$level() : null;
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, StasisPost::tick);
        // flames: recheck gutters once their chunk (and its light) is in
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> org.erisia.stasis.Flames.clientTick(net.minecraft.client.Minecraft.getInstance().level));
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkEvent.Load.class, e -> {
            if (e.getLevel() instanceof net.minecraft.world.level.Level l && l.isClientSide()) org.erisia.stasis.Flames.clientChunkLoad(l, e.getChunk().getPos());
        });
        // EA flashlight beams, per pixel (Baughn)
        NeoForge.EVENT_BUS.addListener(FrameGraphSetupEvent.class, FlashlightPost::frame);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, FlashlightPost::tick);
        // a suited player's first-person arm is the suit (sleeve and glove); drawn after the desaturation in stopped
        // land (mixin/client/GameRendererMixin)
        NeoForge.EVENT_BUS.addListener(RenderArmEvent.class, SuitArm::renderArm);
        // the generated towers (P3d): meshes built off-thread, kept in GPU buffers, drawn after opaque terrain
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent.class, TowerRenderer::registerPipelines);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.PrepareRenderBuffersEvent.class, TowerRenderer::prepare);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterOpaqueBlocks.class, TowerRenderer::draw);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.LevelEvent.Unload.class, e -> {
            if (e.getLevel().isClientSide()) {
                TowerRenderer.clear();
                org.erisia.stasis.LocalTime.Client.clear();
            }
        });
        // in stopped land the background music is the melancholic/eldritch subset (Baughn)
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.SelectMusicEvent.class, e -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var m = e.getMusic();
            if (mc.level == null || mc.player == null || m == null || !org.erisia.stasis.Stasis.stops(mc.level)) return;
            // only out in the grey; inside a field the music is the world's own (Baughn)
            if (org.erisia.stasis.Stasis.isRunning(mc.level, mc.player.blockPosition())) return;
            var key = m.sound().unwrapKey().map(k -> k.identifier()).orElse(null);
            if (key == null || !key.getNamespace().equals("minecraft")) return;
            String p = key.getPath();
            if (p.equals("music.game") || p.equals("music.creative") || p.startsWith("music.overworld.") || p.startsWith("music.nether."))
                e.setMusic(new net.minecraft.sounds.Music(org.erisia.stasis.StasisRegistry.MUSIC_STOPPED, m.minDelay(), m.maxDelay(), false));
        });
        // how to use the tower blocks (Baughn: no indication otherwise)
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent.class, e -> {
            var it = e.getItemStack().getItem();
            String key = it == org.erisia.stasis.StasisRegistry.TOWER_ITEM.get() ? "tooltip.stasisdelvers.stasis_tower"
                : it == org.erisia.stasis.StasisRegistry.TOWER_SEGMENT_ITEM.get() ? "tooltip.stasisdelvers.stasis_tower_segment" : null;
            if (key == null) return;
            for (int i = 0; i < 3; i++)
                e.getToolTip().add(net.minecraft.network.chat.Component.translatable(key + "." + i).withStyle(net.minecraft.ChatFormatting.GRAY));
        });
        org.erisia.stasis.TowerClientHooks.onUpdate = TowerRenderer::updated;
        org.erisia.stasis.TowerClientHooks.onRemove = TowerRenderer::removed;
    }
}

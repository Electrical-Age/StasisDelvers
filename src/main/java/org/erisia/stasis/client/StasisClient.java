package org.erisia.stasis.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.erisia.stasis.StasisDelvers;
import org.erisia.stasis.StasisRegistry;

/** Client side: statue model, the desaturation post effect, the freeze warning, the suit arm and the
 *  bug-report key. */
@Mod(value = StasisDelvers.MODID, dist = Dist.CLIENT)
public final class StasisClient {
    private static long lastDig;
    public StasisClient(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        // the bug-report key (F7) and its client config (notes/bugreport/SPEC.md)
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, org.erisia.stasis.client.report.ReportConfig.SPEC);
        org.erisia.stasis.client.report.BugReport.init(modBus);
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent.class, e -> e.registerAbove(
            net.neoforged.neoforge.client.gui.VanillaGuiLayers.HOTBAR, net.minecraft.resources.Identifier.fromNamespaceAndPath("stasisdelvers", "suit_gauge"),
            SuitGauge::render));
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> e.registerEntityRenderer(StasisRegistry.STATUE.get(), StatueRenderer::new));
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> e.registerBlockEntityRenderer(StasisRegistry.QUERN_ENTITY.get(), QuernRenderer::new));
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> {
            e.registerBlockEntityRenderer(StasisRegistry.TEMPORAL_ACCELERATOR_ENTITY.get(), AcceleratorLeverRenderer::new);
            e.registerBlockEntityRenderer(StasisRegistry.CHRONAL_ACCELERATOR_ENTITY.get(), AcceleratorLeverRenderer::new);
        });
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> e.registerEntityRenderer(StasisRegistry.RIFT_ASKEW.get(),
            AskewRenderer::new));
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> e.registerEntityRenderer(StasisRegistry.POSSESSED_STATUE.get(),
            PossessedStatueRenderer::new));
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> e.registerEntityRenderer(StasisRegistry.RIFT_PUPPETEER.get(),
            net.minecraft.client.renderer.entity.NoopRenderer::new));
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, PossessedLook::haze);
        // (Static has no model: it draws itself as particles, RiftStatic.clientStatic)
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> e.registerEntityRenderer(StasisRegistry.RIFT_STATIC.get(),
            net.minecraft.client.renderer.entity.NoopRenderer::new));
        // the shield is drawn by the post effect (so the desaturation can't grey it)
        NeoForge.EVENT_BUS.addListener(FrameGraphSetupEvent.class, StasisPost::frame);
        // flames' light in chunk meshes (smooth lighting) depends on where they are
        org.erisia.stasis.Flames.clientResolver = g -> g instanceof net.minecraft.client.renderer.chunk.RenderSectionRegion r ? r.level : null;
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, StasisPost::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre.class, FrozenEntities::tick);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, RiftHum::tick);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, DeepWarning::tick);   // (deeps seen and heard from outside)
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent.class, DeepWarning::wall);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, StillEdge::tick);   // (the stilled land's edge, Baughn)
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent.class, StillEdge::draw);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, EndBarrierFx::tick);   // spreading mode's End wall
        // the End event: the End's fog pushed out so the island's breaking up can be seen (AdventurAgent)
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, EndEventFog::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ViewportEvent.RenderFog.class, EndEventFog::fog);
        // the End rift (EndRift): in the End after hard mode its static core stands where the island was
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            var lvl = net.minecraft.client.Minecraft.getInstance().level;
            if (lvl != null && lvl.dimension() == net.minecraft.world.level.Level.END && org.erisia.stasis.Spread.client.hard()
                && org.erisia.stasis.ClientFields.deepSeed() != 0)
                org.erisia.stasis.StaticDeeps.addEndRift(org.erisia.stasis.ClientFields.deepSeed());
        });
        // flames: recheck gutters once their chunk (and its light) is in
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> org.erisia.stasis.Flames.clientTick(net.minecraft.client.Minecraft.getInstance().level));
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkEvent.Load.class, e -> {
            if (e.getLevel() instanceof net.minecraft.world.level.Level l && l.isClientSide()) org.erisia.stasis.Flames.clientChunkLoad(l, e.getChunk().getPos());
        });
        // lightning from the shield to the tower (visual only; drawn by the post shader from BoltRenderer.fill)
        BoltRenderer.init();
        AntiSound.init();
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre.class, e -> AntiSound.frame());
        ConsoleScreen.hook();
        // EA flashlight beams, per pixel (Baughn)
        NeoForge.EVENT_BUS.addListener(FrameGraphSetupEvent.class, FlashlightPost::frame);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, FlashlightPost::tick);
        // a suited player's first-person arm is the suit (sleeve and glove); drawn after the desaturation in stopped
        // land (mixin/client/GameRendererMixin)
        NeoForge.EVENT_BUS.addListener(RenderArmEvent.class, SuitArm::renderArm);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered.class, e -> {
            // digging stopped water/lava with a shovel (the crosshair ignores fluids): one block per click
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (!e.isAttack() || mc.player == null || !org.erisia.stasis.StilledLiquids.clientDig(mc.player)) return;
            e.setCanceled(true);
            if (mc.level.getGameTime() - lastDig < 5) return;
            lastDig = mc.level.getGameTime();
            e.setSwingHand(true);
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new org.erisia.stasis.StasisNet.DigStilled());
        });
        // ... and holding the button keeps digging, a block every 5 ticks (Baughn: on the ocean it took one only; the key
        // event fires per click, and vanilla's held attack goes to blocks the crosshair hits, not the fluid)
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.gui.screen() != null || !mc.options.keyAttack.isDown()) return;
            if (mc.level.getGameTime() - lastDig < 5 || !org.erisia.stasis.StilledLiquids.clientDig(mc.player)) return;
            lastDig = mc.level.getGameTime();
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new org.erisia.stasis.StasisNet.DigStilled());
        });
        // the generated towers (P3d): meshes built off-thread, kept in GPU buffers, drawn after opaque terrain
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent.class, TowerRenderer::registerPipelines);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.PrepareRenderBuffersEvent.class, TowerRenderer::prepare);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterOpaqueBlocks.class, TowerRenderer::draw);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.LevelEvent.Unload.class, e -> {
            if (e.getLevel().isClientSide()) {
                TowerRenderer.clear();
                Remnants.clear();
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
                : it == org.erisia.stasis.StasisRegistry.TOWER_SEGMENT_ITEM.get() ? "tooltip.stasisdelvers.stasis_tower_segment"
                : it == org.erisia.stasis.StasisRegistry.LANCE_ITEM.get() ? "tooltip.stasisdelvers.lance_core" : null;
            if (key == null) return;
            for (int i = 0; i < 3; i++)
                e.getToolTip().add(net.minecraft.network.chat.Component.translatable(key + "." + i).withStyle(net.minecraft.ChatFormatting.GRAY));
        });
        // still textures in stopped land (Baughn: lava, water and campfires kept animating)
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterSpriteSourcesEvent.class, FrozenSprites::registerSource);
        // stilled water looks like the water it froze from (λ-Nero: it looked like ice): the biome's water tint
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.BlockTintSources.class, e ->
            e.register(java.util.List.of(net.minecraft.client.color.block.BlockTintSources.water()), org.erisia.stasis.StasisRegistry.STILLED_WATER_BLOCK.get()));
        modBus.addListener(net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent.class, FrozenSprites::stitched);
        org.erisia.stasis.ClientFields.onChange = FrozenSprites::fieldsChanged;
        org.erisia.stasis.Rooms.clientOnChange = FrozenSprites::roomsChanged;
        // a lance's breach step: re-mesh round the rift (Lance, FrozenSprites.riftStrengths)
        org.erisia.stasis.StasisNet.riftStrengths = FrozenSprites::riftStrengths;
        // spreading mode: re-mesh and relight only where P or a new rank tile changed the answer; forget it all on leaving
        org.erisia.stasis.Spread.clientListener = FrozenSprites.SPREAD;
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class,
            e -> {
                org.erisia.stasis.Spread.clientClear();
                org.erisia.stasis.StaticDeeps.clearEndRifts();
            });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> FrozenSprites.tick(net.minecraft.client.Minecraft.getInstance()));
        org.erisia.stasis.TowerClientHooks.onUpdate = be -> {
            TowerRenderer.updated(be);
            Remnants.updated(be);
        };
        org.erisia.stasis.TowerClientHooks.onRemove = be -> {
            TowerRenderer.removed(be);
            Remnants.removed(be);
        };
    }
}

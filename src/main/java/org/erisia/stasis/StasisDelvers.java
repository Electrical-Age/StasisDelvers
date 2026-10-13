package org.erisia.stasis;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stasis Delvers: time stands still outside fields kept running by Electrical Age power (Baughn's design). */
@Mod(StasisDelvers.MODID)
public final class StasisDelvers {
    public static final String MODID = "stasisdelvers";
    public static final Logger LOG = LoggerFactory.getLogger("stasisdelvers");

    public StasisDelvers(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SYNCED, StasisConfig.SPEC);
        modBus.addListener(ModConfigEvent.Loading.class, e -> StasisConfig.refresh());
        modBus.addListener(ModConfigEvent.Reloading.class, e -> StasisConfig.refresh());
        NeoForge.EVENT_BUS.addListener(StasisEvents::entityTick);
        NeoForge.EVENT_BUS.addListener(StasisEvents::spawnCheck);
        NeoForge.EVENT_BUS.addListener(StasisEvents::levelUnload);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> StasisCommands.register(e.getDispatcher()));
        StasisRegistry.register(modBus);
        StasisNet.register(modBus);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post.class, Freezing::tick);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, Freezing::grace);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent.class, Freezing::grace);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, Freezing::grace);
        NeoForge.EVENT_BUS.addListener(Freezing::logout);
        NeoForge.EVENT_BUS.addListener(Freezing::respawnPosition);
        NeoForge.EVENT_BUS.addListener(StasisEvents::pickup);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, PlayerRates::tick);
        NeoForge.EVENT_BUS.addListener(PlayerRates::logout);
        NeoForge.EVENT_BUS.addListener(Freezing::onBreakSpeed);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, NodeFreezer::tick);
        org.erisia.stasis.gametest.StasisGameTests.register(modBus);
    }
}

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
        // (only our synced config: the client config's Loading came first on clients and crashed refresh, AdventurAgent)
        modBus.addListener(ModConfigEvent.Loading.class, e -> {
            if (e.getConfig().getSpec() == StasisConfig.SPEC) StasisConfig.refresh();
        });
        modBus.addListener(ModConfigEvent.Reloading.class, e -> {
            if (e.getConfig().getSpec() == StasisConfig.SPEC) StasisConfig.refresh();
        });
        NeoForge.EVENT_BUS.addListener(StasisEvents::entityTick);
        NeoForge.EVENT_BUS.addListener(StasisEvents::spawnCheck);
        NeoForge.EVENT_BUS.addListener(StasisEvents::finalizeSpawn);
        NeoForge.EVENT_BUS.addListener(StasisEvents::keepFriendlyInside);
        NeoForge.EVENT_BUS.addListener(FieldSleep::canSleep);
        NeoForge.EVENT_BUS.addListener(FieldSleep::canContinue);
        NeoForge.EVENT_BUS.addListener(FieldSleep::tick);
        NeoForge.EVENT_BUS.addListener(FieldSleep::noWorldSkip);
        NeoForge.EVENT_BUS.addListener(StasisEvents::levelLoad);
        NeoForge.EVENT_BUS.addListener(StasisEvents::levelUnload);
        NeoForge.EVENT_BUS.addListener(NewChunkGrace::chunkLoad);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> StasisCommands.register(e.getDispatcher()));
        StasisRegistry.register(modBus);
        EndSites.init();
        RuinedCottages.register(modBus);
        StasisNet.register(modBus);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post.class, Freezing::tick);
        // (the starter suit first: a new player in stopped land is protected by it at once)
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, StarterKit::login);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, Freezing::grace);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent.class, Freezing::grace);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, Freezing::grace);
        NeoForge.EVENT_BUS.addListener(Freezing::logout);
        NeoForge.EVENT_BUS.addListener(Freezing::respawnPosition);
        NeoForge.EVENT_BUS.addListener(StasisEvents::pickup);
        NeoForge.EVENT_BUS.addListener(StasisEvents::itemJoin);
        NeoForge.EVENT_BUS.addListener(StasisEvents::incomingDamage);
        NeoForge.EVENT_BUS.addListener(StasisEvents::deathLog);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, LocalTime::tick);
        NeoForge.EVENT_BUS.addListener(SpawnCottage::serverStarted);
        NeoForge.EVENT_BUS.addListener(Spread::serverStarted);
        NeoForge.EVENT_BUS.addListener(Spread::serverStopped);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, Spread::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, SpawnMove::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, FrontWarnings::tick);
        // spreading mode's hard mode (the dragon's death) and the End's wall
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, HardMode::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, HardMode::death);
        NeoForge.EVENT_BUS.addListener(HardMode::travel);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post.class, HardMode::playerTick);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, HardMode::login);
        NeoForge.EVENT_BUS.addListener(HardMode::serverStopped);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, EndRift::tick);
        NeoForge.EVENT_BUS.addListener(EndRift::entityLeave);
        NeoForge.EVENT_BUS.addListener(EndRift::entityJoin);
        NeoForge.EVENT_BUS.addListener(EndRift::serverStopped);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post.class, EndBarrier::tick);
        NeoForge.EVENT_BUS.addListener(EndBarrier::logout);
        // (after Spread::tick: the clients hear this tick's P)
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, SpreadSync::tick);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, SpreadSync::join);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent.class, SpreadSync::join);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, SpreadSync::join);
        NeoForge.EVENT_BUS.addListener(SpreadSync::leave);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, PlayerRates::tick);
        NeoForge.EVENT_BUS.addListener(PlayerRates::logout);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, PlayerRates::miningClock);
        NeoForge.EVENT_BUS.addListener(PlayerRates::attackClock);
        NeoForge.EVENT_BUS.addListener(Freezing::onBreakSpeed);
        NeoForge.EVENT_BUS.addListener(StilledLiquids::placeAgainst);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, NodeFreezer::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, Flames::tick);
        NeoForge.EVENT_BUS.addListener(Flames::chunkLoad);
        NeoForge.EVENT_BUS.addListener(Crystals::chunkLoad);
        NeoForge.EVENT_BUS.addListener(StilledLiquids::use);
        NeoForge.EVENT_BUS.addListener(StilledLiquids::useOnBlock);
        // HIGHEST: the mixin this replaced ran before every listener; another mod's listener must not run the held
        // item's use for a non-op under spawn protection before we cancel it
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, SpawnUse::useOn);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, Gates::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, HardRifts::tick);
        NeoForge.EVENT_BUS.addListener(HardRifts::chunkLoad);
        NeoForge.EVENT_BUS.addListener(HardRifts::serverStopped);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, StormCrystals::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, GateRoom::fallDebris);
        NeoForge.EVENT_BUS.addListener(Crystals::chunkUnload);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, Crystals::tick);
        NeoForge.EVENT_BUS.addListener(Flames::watch);
        // clients draw flashlight beams (client/FlashlightPost): EA lights only the holder's block, not a trail
        mods.eln.item.electricalitem.LampItem.clientBeam = e -> true;
        NodeFreezer.install();
        org.erisia.stasis.gametest.StasisGameTests.register(modBus);
    }
}

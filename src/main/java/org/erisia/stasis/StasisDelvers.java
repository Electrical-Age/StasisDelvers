package org.erisia.stasis;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
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
        org.erisia.stasis.gametest.StasisGameTests.register(modBus);
    }
}

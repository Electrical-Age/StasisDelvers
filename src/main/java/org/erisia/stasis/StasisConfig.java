package org.erisia.stasis;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Synced config (server side, sent to clients). Values are cached on load and reload. */
public final class StasisConfig {
    private StasisConfig() {
    }

    static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue ENABLED;
    static final ModConfigSpec.ConfigValue<List<? extends String>> STOPPED_DIMENSIONS;
    static final ModConfigSpec.IntValue SPAWN_RADIUS;
    static final ModConfigSpec.IntValue FREEZE_TICKS;
    static final ModConfigSpec.IntValue GRACE_TICKS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Stop time outside fields. Off: everything runs as in vanilla.").define("enabled", true);
        STOPPED_DIMENSIONS = b.comment("Dimensions where time is stopped outside fields.")
            .defineListAllowEmpty("stoppedDimensions", List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                () -> "minecraft:overworld", o -> o instanceof String s && Identifier.tryParse(s) != null);
        SPAWN_RADIUS = b.comment("Chunks around world spawn that always run, in each direction (1 = 3x3 chunks). -1: none.")
            .defineInRange("spawnRadiusChunks", 1, -1, 64);
        FREEZE_TICKS = b.comment("Ticks a player without a suit lasts in stopped land before freezing into a statue.")
            .defineInRange("freezeTicks", 100, 20, 1200);
        GRACE_TICKS = b.comment("Ticks after login, respawn or a dimension change during which nobody freezes.")
            .defineInRange("graceTicks", 200, 0, 2400);
        SPEC = b.build();
    }

    // cached values (read on every tick check)
    static volatile boolean enabled = true;
    static volatile Set<ResourceKey<Level>> stopped = Set.of(Level.OVERWORLD, Level.NETHER, Level.END);
    static volatile int spawnRadius = 1;
    static volatile int freezeTicks = 100;
    static volatile int graceTicks = 200;

    /** The game test server sets this: its spawn lies among the test structures, which must start out stopped. */
    static final boolean NO_SPAWN_AREA = Boolean.getBoolean("stasisdelvers.noSpawnArea");

    static {
        if (NO_SPAWN_AREA) spawnRadius = -1;
    }

    static void refresh() {
        enabled = ENABLED.get();
        stopped = STOPPED_DIMENSIONS.get().stream()
            .map(s -> ResourceKey.create(Registries.DIMENSION, Identifier.parse(s))).collect(Collectors.toUnmodifiableSet());
        spawnRadius = NO_SPAWN_AREA ? -1 : SPAWN_RADIUS.get();
        freezeTicks = FREEZE_TICKS.get();
        graceTicks = GRACE_TICKS.get();
    }
}

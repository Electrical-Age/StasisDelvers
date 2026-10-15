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
    static final ModConfigSpec.BooleanValue SPAWN_AREA;
    static final ModConfigSpec.IntValue SPAWN_RADIUS;
    static final ModConfigSpec.IntValue FREEZE_TICKS;
    static final ModConfigSpec.IntValue GRACE_TICKS;
    static final ModConfigSpec.DoubleValue TOWER_DRAW_K;
    static final ModConfigSpec.DoubleValue TOWER_STORE_C;
    static final ModConfigSpec.IntValue TOWER_MAX_SEGMENTS;
    static final ModConfigSpec.DoubleValue TOWER_FIELD_RATE;
    static final ModConfigSpec.DoubleValue GUTTER_CHANCE;
    static final ModConfigSpec.BooleanValue DEBUG_MESSAGES;
    static final ModConfigSpec.IntValue GUTTER_MIN_TICKS;
    static final ModConfigSpec.IntValue GUTTER_MAX_TICKS;
    static final ModConfigSpec.ConfigValue<List<? extends String>> STRATA_SHAPES;
    static final ModConfigSpec.BooleanValue RIFT_ORES;
    static final ModConfigSpec.DoubleValue RIFT_ORE_RATE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Stop time outside fields. Off: everything runs as in vanilla.").define("enabled", true);
        STOPPED_DIMENSIONS = b.comment("Dimensions where time is stopped outside fields.")
            .defineListAllowEmpty("stoppedDimensions", List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                () -> "minecraft:overworld", o -> o instanceof String s && Identifier.tryParse(s) != null);
        SPAWN_AREA = b.comment("Does time always run around world spawn (spawnRadiusChunks)? Off by default (Baughn): the plan",
                "is a spawn structure with a solar-powered tower. Without it, new players start in stopped land.")
            .define("spawnArea", false);
        SPAWN_RADIUS = b.comment("Chunks around world spawn that always run, in each direction (1 = 3x3 chunks). -1: none.")
            .defineInRange("spawnRadiusChunks", 1, -1, 64);
        FREEZE_TICKS = b.comment("Ticks a player without a suit lasts in stopped land before freezing into a statue.")
            .defineInRange("freezeTicks", 100, 60, 1200);
        GRACE_TICKS = b.comment("Ticks after login, respawn or a dimension change during which nobody freezes.")
            .defineInRange("graceTicks", 200, 100, 2400);
        TOWER_DRAW_K = b.comment("Tower draw P = k * r^1.5 W (r = field radius). Default: r 48 draws about 590 W (Baughn "
            + "2026-10-14: twice the radius at half the power of the old k 10).")
            .defineInRange("towerDrawK", 1.77, 0.0, 1e6);
        TOWER_STORE_C = b.comment("Tower store E = c * r^2 J. Default (Baughn 2026-10-14: a quarter of the old 1225, so the "
            + "doubled radius keeps the old energy): a full store runs an r 48 field for about 20 minutes.")
            .defineInRange("towerStoreC", 306.25, 1.0, 1e9);
        TOWER_MAX_SEGMENTS = b.comment("Most segments a tower counts (radius = 4 * (segments + 2)).")
            .defineInRange("towerMaxSegments", 30, 0, 254);
        TOWER_FIELD_RATE = b.comment("Tick rate a tower's field starts at, as a share of 20 TPS (0.6 = 12 TPS). Blocks inside and the",
                "clients of players inside run at it. Set at tower start; /stasis field rate changes a running field.")
            .defineInRange("towerFieldRate", 0.6, 0.05, 1.0);
        DEBUG_MESSAGES = b.comment("Action-bar messages when a deep holds a player or a rift takes one (debugging; off: Baughn wants",
                "no messages about weather, deeps and rifts: fear of the unknown).")
            .define("debugMessages", false);
        GUTTER_CHANCE = b.comment("Chance that a random tick makes a flame (block tag stasisdelvers:flames) gutter to light 4-7.",
                "A block gets a random tick about every 68 s at randomTickSpeed 3, so 0.25 is about once in 4.5 minutes.")
            .defineInRange("gutterChance", 0.25, 0.0, 1.0);
        GUTTER_MIN_TICKS = b.comment("Shortest gutter, in ticks.").defineInRange("gutterMinTicks", 100, 1, 72000);
        GUTTER_MAX_TICKS = b.comment("Longest gutter, in ticks.").defineInRange("gutterMaxTicks", 600, 1, 72000);
        STRATA_SHAPES = b.comment("Strata shapes around static deeps' rifts, as name:weight (saddle, staircase, splash, pods); each",
                "rift picks one from its id. Worldgen only: changes reach newly generated chunks.")
            .defineListAllowEmpty("strataShapes", List.of("saddle:1", "staircase:1", "splash:1", "pods:1"), () -> "saddle:1", o -> o instanceof String);
        RIFT_ORES = b.comment("Ores in rift walls and strata, from the overworld ore tags, at any depth (Baughn).").define("riftOres", true);
        RIFT_ORE_RATE = b.comment("Share of rift wall and strata rock that becomes ore.").defineInRange("riftOreRate", 0.006, 0.0, 0.2);
        SPEC = b.build();
    }

    // cached values (read on every tick check)
    static volatile boolean enabled = true;
    static volatile Set<ResourceKey<Level>> stopped = Set.of(Level.OVERWORLD, Level.NETHER, Level.END);
    static volatile int spawnRadius = -1;
    static volatile int freezeTicks = 100;
    static volatile int graceTicks = 200;
    static volatile double towerDrawK = 1.77;
    static volatile double towerStoreC = 306.25;
    static volatile int towerMaxSegments = 30;
    static volatile double towerFieldRate = 0.6;
    static volatile double gutterChance = 0.25;
    static volatile boolean debugMessages = false;
    static volatile int gutterMinTicks = 100;
    static volatile int gutterMaxTicks = 600;
    static volatile boolean riftOres = true;
    static volatile double riftOreRate = 0.006;

    /** The game test server sets this: its spawn lies among the test structures, which must start out stopped. */
    static final boolean NO_SPAWN_AREA = Boolean.getBoolean("stasisdelvers.noSpawnArea");

    static {
        if (NO_SPAWN_AREA) spawnRadius = -1;
    }

    /** Tests: set the tower draw constant (returns the old one). */
    public static int freezeTicks() {
        return freezeTicks;
    }

    public static double testTowerDrawK(double k) {
        double old = towerDrawK;
        towerDrawK = k;
        return old;
    }

    static void refresh() {
        enabled = ENABLED.get();
        stopped = STOPPED_DIMENSIONS.get().stream()
            .map(s -> ResourceKey.create(Registries.DIMENSION, Identifier.parse(s))).collect(Collectors.toUnmodifiableSet());
        spawnRadius = NO_SPAWN_AREA || !SPAWN_AREA.get() ? -1 : SPAWN_RADIUS.get();
        freezeTicks = FREEZE_TICKS.get();
        graceTicks = GRACE_TICKS.get();
        towerDrawK = TOWER_DRAW_K.get();
        towerStoreC = TOWER_STORE_C.get();
        towerMaxSegments = TOWER_MAX_SEGMENTS.get();
        towerFieldRate = TOWER_FIELD_RATE.get();
        gutterChance = GUTTER_CHANCE.get();
        debugMessages = DEBUG_MESSAGES.get();
        gutterMinTicks = GUTTER_MIN_TICKS.get();
        gutterMaxTicks = GUTTER_MAX_TICKS.get();
        Strata.setWeights(STRATA_SHAPES.get());
        riftOres = RIFT_ORES.get();
        riftOreRate = RIFT_ORE_RATE.get();
    }
}

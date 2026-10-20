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
    static final ModConfigSpec.DoubleValue TOWER_BOOM_STRETCH;
    static final ModConfigSpec.DoubleValue TOWER_FIELD_RATE;
    static final ModConfigSpec.DoubleValue GUTTER_CHANCE;
    static final ModConfigSpec.BooleanValue DEBUG_MESSAGES;
    static final ModConfigSpec.IntValue GUTTER_MIN_TICKS;
    static final ModConfigSpec.IntValue GUTTER_MAX_TICKS;
    static final ModConfigSpec.ConfigValue<List<? extends String>> STRATA_SHAPES;
    static final ModConfigSpec.BooleanValue RIFT_ORES;
    static final ModConfigSpec.DoubleValue RIFT_ORE_RATE;
    static final ModConfigSpec.IntValue CRYSTAL_STAGE_SECONDS;
    static final ModConfigSpec.ConfigValue<List<? extends String>> RIFT_DESTINATIONS;
    static final ModConfigSpec.ConfigValue<List<? extends String>> RIFT_SCATTER;
    static final ModConfigSpec.DoubleValue RIFT_SCATTER_RATE;
    static final ModConfigSpec.BooleanValue END_PORTAL_RIFTS;
    static final ModConfigSpec.BooleanValue STORMS;
    static final ModConfigSpec.BooleanValue TIME_SCARS;
    static final ModConfigSpec.BooleanValue RUINS;
    static final ModConfigSpec.BooleanValue SKY_ISLANDS;
    static final ModConfigSpec.DoubleValue SKY_MESSY_CHANCE;
    static final ModConfigSpec.DoubleValue STORM_EVERY_HOURS;
    static final ModConfigSpec.IntValue STORM_MINUTES;
    static final ModConfigSpec.IntValue STORM_WARN_MINUTES;
    static final ModConfigSpec.DoubleValue DELVER_SUIT_MINUTES;
    static final ModConfigSpec.BooleanValue STARTER_SUIT;
    static final ModConfigSpec.ConfigValue<String> MODE;
    static final ModConfigSpec.DoubleValue SPREAD_HOURS;
    static final ModConfigSpec.DoubleValue HARD_SPREAD_HOURS;
    static final ModConfigSpec.DoubleValue SPREAD_CAP;
    static final ModConfigSpec.IntValue SPREAD_RADIUS;
    static final ModConfigSpec.DoubleValue SPREAD_CONNECTED_UNTIL;
    static final ModConfigSpec.BooleanValue END_BARRIER;
    static final ModConfigSpec.IntValue END_BARRIER_RADIUS;
    static final ModConfigSpec.DoubleValue HARD_MODE_TELEPORT_SECONDS;
    static final ModConfigSpec.IntValue STOPPED_UNBOUND_CAP;
    static final ModConfigSpec.BooleanValue FAUNA;
    static final ModConfigSpec.DoubleValue FAUNA_CHANCE, FAUNA_QUIET_MINUTES, FAUNA_STORM_QUIET_MINUTES;
    static final ModConfigSpec.IntValue FAUNA_PER_PLAYER, FAUNA_PER_PLAYER_STORM;
    static final ModConfigSpec.DoubleValue LANCE_HOLD_W, LANCE_BREACH_MIN, LANCE_BREACH_MAX, LANCE_BREACH_SECONDS, LANCE_RELAX_SECONDS,
        LANCE_GRACE_SECONDS, LANCE_STORM_DRAW, LANCE_STORE_SECONDS;

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
        TOWER_MAX_SEGMENTS = b.comment("Most segments a tower counts (radius = 8 * (segments + 2)).")
            .defineInRange("towerMaxSegments", 30, 0, 254);
        TOWER_BOOM_STRETCH = b.comment("How far a tower's booms stretch its field towards their side: that side reaches",
                "r * (1 + k * the longest boom's share of its allowed length). Baughn 2026-10-18: 0.5 (a full-length boom: 1.5 r).")
            .defineInRange("towerBoomStretch", 0.5, 0.0, 4.0);
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
        CRYSTAL_STAGE_SECONDS = b.comment("Seconds of strata weather (mist, violet or rift, outside fields) per rift crystal growth stage:",
                "small after one, cluster after four. A pocket is in weather 75% of the time.")
            .defineInRange("crystalStageSeconds", 1200, 10, 86400);
        RIFT_DESTINATIONS = b.comment("Where static rifts lead, as dimension:weight (Baughn: the End 2, the Nether 1). Packs can add",
                "dimensions. Gate rifts in stronghold portal rooms always lead to the End. Worldgen: new chunks only.")
            .defineListAllowEmpty("riftDestinations", List.of("minecraft:the_end:2", "minecraft:the_nether:1"), () -> "minecraft:the_end:1", o -> o instanceof String);
        RIFT_SCATTER = b.comment("Plain blocks of a destination scattered in its rifts' shell and strata, as dimension=block,block.",
                "Ores stay overworld. Worldgen: new chunks only.")
            .defineListAllowEmpty("riftScatter", List.of("minecraft:the_nether=minecraft:netherrack,minecraft:soul_sand"), () -> "minecraft:the_nether=minecraft:netherrack", o -> o instanceof String);
        RIFT_SCATTER_RATE = b.comment("About this share of a rift's shell and strata rock becomes its destination's scatter blocks.")
            .defineInRange("riftScatterRate", 0.05, 0.0, 0.4);
        END_PORTAL_RIFTS = b.comment("Stronghold portal rooms get a gate rift (to the End) instead of an End portal frame (Baughn).",
                "Worldgen: new strongholds only.")
            .define("endPortalRifts", true);
        TIME_SCARS = b.comment("Time scars around rifts: falls frozen mid-air, hanging gravel, arrow volleys (worldgen: new chunks).").define("timeScars", true);
        SKY_ISLANDS = b.comment("A rift in open air gets its strata as floating riftstone islands and shattered shards (Baughn; worldgen).").define("skyIslands", true);
        SKY_MESSY_CHANCE = b.comment("Share of sky rifts that are messy (wide floating slabs, broken towers, long frozen columns; Baughn),",
                "each to its own degree (interpolated). Worldgen: new chunks.").defineInRange("skyMessyChance", 0.1, 0.0, 1.0);
        RUINS = b.comment("Swallowed ruins in rift strata: vanilla structure pieces half sunk in the rock (worldgen: new chunks).").define("ruins", true);
        STORMS = b.comment("Rift storms (stage 3): the drifting weather speeds up 10x and storm mist and rifts sweep the land.").define("storms", true);
        STORM_EVERY_HOURS = b.comment("Mean hours (of game time at 20 TPS) between storms; each falls at a random point in its block of this",
                "length (Baughn: about an in-game month, ~10 h).").defineInRange("stormEveryHours", 10.0, 0.25, 1000.0);
        STORM_MINUTES = b.comment("A storm's length in minutes (Baughn: 20 for now).").defineInRange("stormMinutes", 20, 4, 600);
        STORM_WARN_MINUTES = b.comment("Minutes before a storm that its violet glow shows on the upwind horizon.").defineInRange("stormWarnMinutes", 60, 0, 6000);
        DELVER_SUIT_MINUTES = b.comment("Minutes a fully charged Delver suit lasts in stopped land (Baughn: 30). Its store scales with it",
                "(10 W per piece: 18 kJ at 30 minutes).").defineInRange("delverSuitMinutes", 30.0, 1.0, 600.0);
        STARTER_SUIT = b.comment("Give each player a full, charged Delver suit on their first join.").define("starterSuit", true);
        MODE = b.comment("Game mode (Baughn 2026-10-19; docs/spreading-mode.md). \"stopped\": time stands still outside fields from the",
                "start (the base mod). \"spreading\": the world starts running and the stillness spreads from the rifts over",
                "spreadHours of online time; no spawn cottage, no starter suit, no storms until hard mode (the dragon's death);",
                "the Nether runs until the spread is complete, the End until hard mode; then everything behaves as \"stopped\".")
            .define("mode", "stopped", o -> "stopped".equals(o) || "spreading".equals(o));
        SPREAD_HOURS = b.comment("Spreading mode: hours of online time (at least one player on) for the stillness to cover spreadCap",
                "of the land within spreadRadius.").defineInRange("spreadHours", 48.0, 0.01, 10000.0);
        HARD_SPREAD_HOURS = b.comment("Spreading mode: hours of online time after hard mode starts for the stillness to go from",
                "spreadCap to all of the land.").defineInRange("hardSpreadHours", 48.0, 0.01, 10000.0);
        SPREAD_CAP = b.comment("Spreading mode: the share of the land the stillness covers before hard mode.")
            .defineInRange("spreadCap", 0.8, 0.0, 1.0);
        SPREAD_RADIUS = b.comment("Spreading mode: radius in blocks around world spawn that the spread map covers (and keeps",
                "navigable). Land beyond it is stilled from the start. Read once, when the map is built (a world's first",
                "start in spreading mode).").defineInRange("spreadRadius", 8000, 64, 30000);
        SPREAD_CONNECTED_UNTIL = b.comment("Spreading mode: up to this coverage running land connects to spawn through running land (only",
                "high ground may stand as running islands); above it the stillness rises like water over the map's stability",
                "terrain and leaves high regions running on their own (Baughn 2026-10-19). Only read when a world's map is built.")
            .defineInRange("spreadConnectedUntil", SpreadMap.CONNECTED_UNTIL, 0.0, 1.0);
        END_BARRIER = b.comment("Spreading mode: a wall of stilled time round the End's main island keeps players on it (Nero: no outer",
                "End). Players are put back inside, as at a world border; creative and spectator players pass.").define("endBarrier", true);
        END_BARRIER_RADIUS = b.comment("Spreading mode: the End wall's radius in blocks round 0, 0 (about the main island's edge).")
            .defineInRange("endBarrierRadius", 140, 32, 4000);
        HARD_MODE_TELEPORT_SECONDS = b.comment("Spreading mode: seconds from the dragon's death (hard mode) to the flash that sends everyone in",
                "the End to their respawn point; the island's explosion plays meanwhile.").defineInRange("hardModeTeleportSeconds", 6.0, 0.0, 30.0);
        // the rift lance (Lance, LanceBlockEntity; docs/lance.md). Baughn 2026-10-19: holding a gate ~10 kW "to break even",
        // the breach more (3-5x by the rift's radius: a r9 stronghold gate the starter, a r60 wild rift the endgame)
        LANCE_HOLD_W = b.comment("Rift lance: watts to hold an open gate (any rift).").defineInRange("lanceHoldW", 10000.0, 0.0, 1e9);
        LANCE_BREACH_MIN = b.comment("Rift lance: the breach draws this many times the hold draw on the smallest rifts (r 9, stronghold gates)...")
            .defineInRange("lanceBreachMinFactor", 3.0, 0.0, 1000.0);
        LANCE_BREACH_MAX = b.comment("... rising linearly to this many times on the largest (r 60).").defineInRange("lanceBreachMaxFactor", 5.0, 0.0, 1000.0);
        LANCE_BREACH_SECONDS = b.comment("Rift lance: seconds the breach (8 steps from the whole rift to a quarter) takes on a r 9 rift;",
            "longer rifts take this x sqrt(r / 9) (r 60: about 2.6 x).").defineInRange("lanceBreachSeconds", 120.0, 1.0, 36000.0);
        LANCE_RELAX_SECONDS = b.comment("Rift lance: seconds an unpowered rift takes to relax back from a quarter to whole (r 9; x sqrt(r / 9)).")
            .defineInRange("lanceRelaxSeconds", 240.0, 1.0, 36000.0);
        LANCE_GRACE_SECONDS = b.comment("Rift lance: seconds without power (the klaxon) before the gate unlatches and starts relaxing.")
            .defineInRange("lanceGraceSeconds", 30.0, 0.0, 3600.0);
        LANCE_STORM_DRAW = b.comment("Rift lance: a storm over the rift multiplies the breach's draw by this (and the breach makes no progress).")
            .defineInRange("lanceStormDrawFactor", 2.0, 1.0, 100.0);
        LANCE_STORE_SECONDS = b.comment("Rift lance: the core's store holds this many seconds of its breach draw. After running dry it waits",
            "until full again, so a supply short of the draw can't hold a gate.").defineInRange("lanceStoreSeconds", 30.0, 1.0, 3600.0);
        STOPPED_UNBOUND_CAP = b.comment("Natural spawns of unbound mobs (endermen) in stopped land: at most this many unbound mobs",
            "within 64 blocks. Nothing else spawns there, so without it endermen fill the whole monster cap (AdventurAgent: 70",
            "around one player in 2 minutes).").defineInRange("stoppedLandEndermen", 2, 0, 1000);
        FAUNA = b.comment("Rift fauna: things time doesn't stop spawn near weather, rifts and storms (notes/fauna).").define("fauna", true);
        FAUNA_CHANCE = b.comment("Rift fauna: chance per spawn try at full dread (4 tries per roll; a roll every 45-90 s per player).")
            .defineInRange("faunaChance", 0.25, 0.0, 1.0);
        FAUNA_PER_PLAYER = b.comment("Rift fauna: at most this many within 64 blocks of a player...").defineInRange("faunaPerPlayer", 2, 0, 100);
        FAUNA_PER_PLAYER_STORM = b.comment("... and this many in a storm.").defineInRange("faunaPerPlayerStorm", 3, 0, 100);
        FAUNA_QUIET_MINUTES = b.comment("Rift fauna: minutes without new ones after an encounter (one within 8 blocks) or a freeze (±15 %)...")
            .defineInRange("faunaQuietMinutes", 12.5, 0.0, 1000.0);
        FAUNA_STORM_QUIET_MINUTES = b.comment("... and after an encounter in a storm.").defineInRange("faunaStormQuietMinutes", 2.5, 0.0, 1000.0);
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
    static volatile double towerBoomStretch = 0.5;
    static volatile double towerFieldRate = 0.6;
    static volatile double gutterChance = 0.25;
    static volatile boolean debugMessages = false;
    static volatile int gutterMinTicks = 100;
    static volatile int gutterMaxTicks = 600;
    static volatile boolean riftOres = true;
    static volatile double riftOreRate = 0.006;
    public static volatile int crystalStageSeconds = 1200;
    static volatile double riftScatterRate = 0.05;
    public static volatile boolean endPortalRifts = true;
    static volatile boolean storms = true;
    static volatile boolean timeScars = true;
    static volatile boolean ruins = true;
    static volatile boolean skyIslands = true;
    static volatile double skyMessyChance = 0.1;
    static volatile double stormEveryHours = 10;
    static volatile int stormMinutes = 20, stormWarnMinutes = 60;
    static volatile double delverSuitMinutes = 30;
    static volatile boolean starterSuit = true;
    /** mode = "spreading" (Spread). */
    static volatile boolean spreading = false;
    static volatile double spreadHours = 48, hardSpreadHours = 48, spreadCap = 0.8;
    static volatile int spreadRadius = 8000;
    static volatile double spreadConnectedUntil = SpreadMap.CONNECTED_UNTIL;
    static volatile boolean endBarrier = true;
    static volatile int endBarrierRadius = 140;
    static volatile double hardModeTeleportSeconds = 6;
    static volatile int stoppedUnboundCap = 2;
    static volatile boolean fauna = true;
    static volatile double faunaChance = 0.25, faunaQuietMinutes = 12.5, faunaStormQuietMinutes = 2.5;
    static volatile int faunaPerPlayer = 2, faunaPerPlayerStorm = 3;
    static volatile double lanceHoldW = 10000, lanceBreachMin = 3, lanceBreachMax = 5, lanceBreachSeconds = 120, lanceRelaxSeconds = 240,
        lanceGraceSeconds = 30, lanceStormDraw = 2, lanceStoreSeconds = 30;

    /** The game test server sets this: its spawn lies among the test structures, which must start out stopped. */
    static final boolean NO_SPAWN_AREA = Boolean.getBoolean("stasisdelvers.noSpawnArea");

    static {
        if (NO_SPAWN_AREA) spawnRadius = -1;
    }

    /** Is the game mode "spreading" (Spread; docs/spreading-mode.md)? */
    public static boolean spreading() {
        return spreading;
    }

    /** stoppedLandEndermen (GameTests). */
    public static int stoppedLandEndermenForTests() {
        return stoppedUnboundCap;
    }

    /** spreadHours (GameTests). */
    public static double spreadHoursForTests() {
        return spreadHours;
    }

    public static int freezeTicks() {
        return freezeTicks;
    }

    /** The cached (synced) values, for bug reports (client/report). */
    public static java.util.Map<String, Object> snapshot() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("stopped", stopped.stream().map(k -> k.identifier().toString()).sorted().toList());
        m.put("spawnRadius", spawnRadius);
        m.put("freezeTicks", freezeTicks);
        m.put("graceTicks", graceTicks);
        m.put("towerDrawK", towerDrawK);
        m.put("towerStoreC", towerStoreC);
        m.put("towerMaxSegments", towerMaxSegments);
        m.put("towerFieldRate", towerFieldRate);
        m.put("gutterChance", gutterChance);
        m.put("debugMessages", debugMessages);
        m.put("gutterMinTicks", gutterMinTicks);
        m.put("gutterMaxTicks", gutterMaxTicks);
        m.put("riftOres", riftOres);
        m.put("riftOreRate", riftOreRate);
        m.put("crystalStageSeconds", crystalStageSeconds);
        m.put("riftScatterRate", riftScatterRate);
        m.put("endPortalRifts", endPortalRifts);
        m.put("storms", storms);
        m.put("timeScars", timeScars);
        m.put("ruins", ruins);
        m.put("skyIslands", skyIslands);
        m.put("skyMessyChance", skyMessyChance);
        m.put("stormEveryHours", stormEveryHours);
        m.put("stormMinutes", stormMinutes);
        m.put("stormWarnMinutes", stormWarnMinutes);
        m.put("delverSuitMinutes", delverSuitMinutes);
        m.put("starterSuit", starterSuit);
        m.put("mode", spreading ? "spreading" : "stopped");
        m.put("spreadHours", spreadHours);
        m.put("hardSpreadHours", hardSpreadHours);
        m.put("spreadCap", spreadCap);
        m.put("spreadRadius", spreadRadius);
        m.put("spreadConnectedUntil", spreadConnectedUntil);
        m.put("endBarrier", endBarrier);
        m.put("endBarrierRadius", endBarrierRadius);
        m.put("hardModeTeleportSeconds", hardModeTeleportSeconds);
        m.put("lanceHoldW", lanceHoldW);
        m.put("lanceBreachFactor", lanceBreachMin + ".." + lanceBreachMax);
        m.put("lanceBreachSeconds", lanceBreachSeconds);
        m.put("lanceRelaxSeconds", lanceRelaxSeconds);
        m.put("lanceGraceSeconds", lanceGraceSeconds);
        m.put("lanceStormDrawFactor", lanceStormDraw);
        m.put("lanceStoreSeconds", lanceStoreSeconds);
        return m;
    }

    /** towerBoomStretch (GameTests). */
    public static double boomStretch() {
        return towerBoomStretch;
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
        towerBoomStretch = TOWER_BOOM_STRETCH.get();
        towerFieldRate = TOWER_FIELD_RATE.get();
        gutterChance = GUTTER_CHANCE.get();
        debugMessages = DEBUG_MESSAGES.get();
        gutterMinTicks = GUTTER_MIN_TICKS.get();
        gutterMaxTicks = GUTTER_MAX_TICKS.get();
        Strata.setWeights(STRATA_SHAPES.get());
        riftOres = RIFT_ORES.get();
        riftOreRate = RIFT_ORE_RATE.get();
        crystalStageSeconds = CRYSTAL_STAGE_SECONDS.get();
        Gates.configure(RIFT_DESTINATIONS.get(), RIFT_SCATTER.get());
        riftScatterRate = RIFT_SCATTER_RATE.get();
        endPortalRifts = END_PORTAL_RIFTS.get();
        spreading = "spreading".equals(MODE.get());
        // (spreading mode: no storms until hard mode, whose instant is the storm schedule's origin: Storms.origin)
        storms = STORMS.get();
        timeScars = TIME_SCARS.get();
        ruins = RUINS.get();
        skyIslands = SKY_ISLANDS.get();
        skyMessyChance = SKY_MESSY_CHANCE.get();
        stormEveryHours = STORM_EVERY_HOURS.get();
        stormMinutes = STORM_MINUTES.get();
        stormWarnMinutes = STORM_WARN_MINUTES.get();
        delverSuitMinutes = DELVER_SUIT_MINUTES.get();
        starterSuit = STARTER_SUIT.get();
        spreadHours = SPREAD_HOURS.get();
        hardSpreadHours = HARD_SPREAD_HOURS.get();
        spreadCap = SPREAD_CAP.get();
        spreadRadius = SPREAD_RADIUS.get();
        spreadConnectedUntil = SPREAD_CONNECTED_UNTIL.get();
        endBarrier = END_BARRIER.get();
        endBarrierRadius = END_BARRIER_RADIUS.get();
        hardModeTeleportSeconds = HARD_MODE_TELEPORT_SECONDS.get();
        stoppedUnboundCap = STOPPED_UNBOUND_CAP.get();
        fauna = FAUNA.get();
        faunaChance = FAUNA_CHANCE.get();
        faunaPerPlayer = FAUNA_PER_PLAYER.get();
        faunaPerPlayerStorm = FAUNA_PER_PLAYER_STORM.get();
        faunaQuietMinutes = FAUNA_QUIET_MINUTES.get();
        faunaStormQuietMinutes = FAUNA_STORM_QUIET_MINUTES.get();
        lanceHoldW = LANCE_HOLD_W.get();
        lanceBreachMin = LANCE_BREACH_MIN.get();
        lanceBreachMax = LANCE_BREACH_MAX.get();
        lanceBreachSeconds = LANCE_BREACH_SECONDS.get();
        lanceRelaxSeconds = LANCE_RELAX_SECONDS.get();
        lanceGraceSeconds = LANCE_GRACE_SECONDS.get();
        lanceStormDraw = LANCE_STORM_DRAW.get();
        lanceStoreSeconds = LANCE_STORE_SECONDS.get();
    }
}

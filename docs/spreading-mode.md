# Spreading mode

Paths are relative to `src/main/java/org/erisia/stasis/`. Design: `notes/timestop/spread-DESIGN.md` (with Argon's
review and Baughn's decisions of 2026-10-19). Step 1 is in: the server core (1a) and the client sync, shader and
drifting deeps over running land (1b); step 2 (the stronghold library desk, portal rooms recorded) and step 3 (front
warnings) too, step 4a (hard mode's entry: the dragon's death, the End before and after), 4b (the stronghold
rewrite) and 5 (the island's breaking up into the End rift, as the crystals fall and at the dragon's death).

## What it is
- Config `mode = "spreading"` (default `"stopped"`, the base mod). The world starts running; the stillness spreads
  from the rifts over `spreadHours` of online time to `spreadCap` (80 %) of the land within `spreadRadius` of spawn.
  Hard mode (the ender dragon's death, or `/stasis spread hardmode`) takes it on from where it stood to 100 % over
  `hardSpreadHours`.
- At 100 % the spread is **complete**: everything behaves as the stopped mode, spawn included (Baughn: on purpose).
- First join: one stability compass and nothing else (`StarterKit`, persisted flag `stasisdelvers_starter_compass`).
- Off in spreading mode: the spawn cottage (not marked done), the starter suit and flashlight, storms until hard mode
  (`Storms.origin` is NaN; from hard mode on the schedule counts from its instant, so tau doesn't jump), and the
  stronghold portal room's gate rift until hard mode (the room stays intact; hard mode tears it open: `HardRifts`).
- Strongholds (step 2): each library has a surveyor's desk (`MapDeskBlock`, maps to ruined cottages; the spawn
  cottage's desk is off with the cottage), and each portal room is a working End portal room, recorded for hard mode.
- The Nether runs until the spread is complete (`Stasis.stops` is false there), then follows the base rules. The End
  runs until hard mode's flash (below), then follows the base rules (stopped: the rift world of step 5).
- Drifting deeps (Baughn, 2026-10-19): a drifting deep stops time on running land it passes over wherever no field
  (or the spawn area) holds it, on both sides: `rateAt` and `rateAtSteady` are 0 there (block ticks, entities, EA
  nodes stop), at the threshold Freezing uses (`Deeps.at > Deeps.THRESHOLD` = 0.5). `rateAtLand` (flame light,
  meshing, map overlays) ignores them. Static deeps' outer zones don't stop running land; their rifts do.
- The world spawn moves once (Baughn 2026-10-19, Discord 1561784319549374485): when the stillness reaches the
  world spawn's column, it moves to a still-viable place near the peak of the nearest qualifying island, and never again (if the new spawn stills too, in
  hard mode, it stays). Players with their own bed or anchor keep it (`Freezing.respawnPosition`'s rules); players
  respawning at world spawn go to the new one, and the spawn area (`spawnRadius`) moves with it. Online players read
  "The world's spawn has moved to the highlands at X, Z: the old spawn has stilled."
- Players in such a deep: pending Baughn's answer, `Freezing.SPREAD_DEEP_HOLDS` (true) gives an unsuited player the
  Delver hold (no statue, released when the deep passes, at most 2400 ticks, no suit messages); false gives the
  normal freeze to a statue. Suited players drain at the deep rate (x2) as in stilled land.

## Classes
- `SpreadMap.java`: the map, pure (no Minecraft state). `build(seed, spawnX, spawnZ, radius, connectedUntil)`,
  algorithm 2, shape v2 "rising water" (Baughn 2026-10-19: v1's stilled regions were too scattered for bases):
  - 32-block cells (cell i covers blocks 32i..32i+31, global). Why 32: open corridors are at least 32 blocks wide
    (Argon), a quarter of the cells of a chunk grid, and every chunk lies in one bilinear patch per axis.
  - S per cell (`stability`): a smooth stability terrain. 2-octave fbm hills on a 1400-block lattice over a warped
    domain (features of 1-2 km), small detail for ragged shores, minus a dip round each static deep's rift (0.35 at its
    edge, gone 200 blocks out: the stillness starts at the rifts), plus a Gaussian raise round spawn (0.25, 800 blocks).
  - split = floor(`spreadConnectedUntil` x N) (config, default 0.5). The N - split cells of highest S take ranks
    split..N-1 in S order: the water rises over the terrain, the islands shrink to their high points, which still last.
  - The other cells take ranks 0..split-1 in the order of a 4-connected priority flood from spawn's cell over all
    cells (the frontier cell of highest S first, v1's growth), keeping only the low cells' order (the later reached, the
    lower). Every low cell was reached from a cell taken before it (high, or low of higher rank), so below the split
    every running low cell is connected to spawn through running cells; high ground may be a running island.
    Above the split nothing is promised (Baughn: EA teleporters, cheap suits). Spawn's cell runs until the split.
  - Sort keys are S exactly (float bits made sortable), then the cell index: deterministic, no ties.
  - Cells outside the radius are -1 (stilled from the start).
  - `nearestAtLeast(map, x, z, minRank, rings)`: the nearest cell centre at or above a rank, ring by ring (the compass).
  - `climb(map, x, z)`: steepest ascent over the 4 neighbours to a local maximum of the ranks, the island's peak (stills
    last). The compass points at climb(nearestAtLeast(...)) (Baughn: "the highest point of the nearest island that reaches
    90 %"); the spawn move searches outward from climb of its nearest qualifying cell.
  - `cellsAtLeast(map, x, z, minRank)`: every cell at or above a rank, nearest first (ties by index): the spawn move's
    candidates.
  - `rank1024(SpreadRanks, x, z)`: **the** lookup, bilinear between cell centres (32i+16) in integer arithmetic.
    A column is stilled iff it is below `P * SCALE` (`stilled`). `chunkMin1024`/`chunkMax1024` are exact.
  - `stilledSplit`: `stilled` in 32-bit integers (high and low 16 bits of r - P), the shader's arithmetic.
- `SpreadRanks.java`: cell ranks by global cell index. The server's is the `SpreadMap`; the client's a `SpreadTiles`.
- `SpreadTiles.java`: the client's map, 32x32-cell tiles (1024 blocks square) in a `ConcurrentHashMap` (mesh workers
  read it). A cell no tile covers is `MISSING` (`Integer.MAX_VALUE`): it runs at every P. `cut` (server),
  `tilesFor(x0, x1)`: the tiles block columns x0..x1 read (cell floor((x - 16) / 32) and the next).
- `SpreadSync.java` (server, main thread): the state to every player when it changes (P at most once a second),
  with `reset` on login, respawn, dimension change and a new map; the tiles round each overworld player out to
  `range` = (view distance + 1) x 16 + 32 blocks, nearest first, 4 a tick; `SpreadForget` for tiles more than one
  tile beyond. Payloads in networking.md.
- `Spread.java`: run-time state in static volatiles (`server`, `client`: map, N, P, hard, complete).
  - `active(level)`: spreading, overworld, not complete. `runs(level, x, z)`, `chunk(level, cx, cz)` (1/0/2),
    `holdsOff(level)` (the Nether and the End).
  - Clock: `tick` adds real seconds while a player is on, at most 1 s per tick (`tickSeconds`). `coverage`:
    `min(cap, cap t / H)`; in hard mode `cHard + (1 - cHard) min(1, (t - tHard) / H_hard)`, where cHard is the coverage
    at the hard-mode instant (saved as `hard_c`), so a dragon killed before the cap doesn't jump the front: the
    stillness goes on from where it stood. `progress` = floor(c N).
  - `update`: on a change of P relights flames in loaded chunks the front crossed and tells `SpreadSync`; on
    completion invalidates every level and gives safe players grace (`Freezing.graceIfStopped`). It doesn't bump
    `Stasis.version`: nothing on the server caches a P-dependent answer (the field index is fields only,
    `StaticDeeps`' section cache is geometry, and the version is read by client meshing only).
  - Client: `clientState`, `clientTile`, `clientForget` (StasisNet handlers) fill `client` (map: `clientTiles`) and
    bump `Stasis.version` (the mesh workers' per-section cache); `clientListener` (`FrozenSprites.SPREAD`) hears
    which rank range P crossed and which tiles came; `clientClear` on disconnect.
  - Life cycle: `levelLoad` (overworld) loads a saved map before chunks ask; `serverStarted` builds it on the
    world's first start in spreading mode (spawn pinned then; ~40 ms at 8 km, 0.6 s at the 30 km maximum);
    until then the overworld runs. `serverStopped` clears the state.
  - Test hooks (ship in the jar): `testInstall(map, p)`, `testReset()`.
- `Stasis.rateAt`: right after "not a stopped dimension", a running column returns 1 whatever the fields (0 in a
  rift, 0 in a drifting deep no field holds); stilled columns go on to the base rules. `sectionState` and
  `mayRunInColumn` have the same branch (by the land: no deeps). `Stasis.spreadRuns(level, x, z)` asks the column.
  Everything else (Freezing, NodeFreezer, Flames, spawns, crystals, sleep, liquids) goes through these.
- Client consumers that looked at fields rather than the rate now ask `spreadRuns` first: the sky clock
  (`LocalTime.Client.time`: the world's clock on running land, the grey's in a deep), beds (`FieldSleep`: vanilla's
  night, and no field clock skip, on running land), `AntiSound` (no failing-field sag there). Weather, particles,
  sounds, frozen entities and flames go through `rateAt` and so follow the client's tiles. A field's shield still
  keeps rain out on running land (`underShield` is the dome's shape, not a time test).
- `SpawnMove.java` (server thread, a `ServerTickEvent.Post` after `Spread.tick`): the world spawn's one move.
  - Trigger: active, a map, `Clock.spawnMove` still 0, the respawn dimension the overworld, and the spawn's column
    stilled at P with a rank of at least 0 (a spawn outside the map, stilled from the start, never moves).
  - Candidates: `cellsAtLeast` from the old spawn with minRank = max(ceil(0.9 N), P + 1) (the compass's 90 %, and
    above P so they still run), fixed when the search starts. Each cell tries 7 positions in the one chunk at its
    centre (32i + 16 is a chunk's first column): (0, 0), (8, 8), (8, 0), (0, 8), (12, 4), (4, 12), (15, 15).
  - A position first passes the generator's noise without loading anything: its own rank1024 >= minRank x SCALE,
    `WORLD_SURFACE_WG` = `OCEAN_FLOOR_WG` (dry), and the noise biome not `#is_ocean`, `#is_deep_ocean`, `#is_river`
    (ice counts as surface in both heightmaps). Then its chunk is loaded (generated if need be) and checked: y = the
    `MOTION_BLOCKING_NO_LEAVES` height, the block below solid (`SpawnCottage.solidGround`) and not leaves, logs or ice,
    no fluid and no collision at y and y + 1, `StaticDeeps.zone` 0 at y - 1..y + 1 (no rift, no deep's outer zone),
    the biome there not at sea, and `Stasis.spreadRuns`.
  - Budget: 16 cells and 2 chunk loads a tick, resuming at the same position; progress logged every 5 s. The cursor
    is memory only (a restart searches again). Found: `setRespawnData` (the vanilla default-spawn packet goes out),
    the move saved, logged, and broadcast in chat. None found: state 2 (no site), logged, not searched again.
  - In test mode (`Spread.testInstall`) it runs only while a test arms it (`testArm`): other spread tests install
    small maps on which the real spawn is outside (stilled), and must not move it. `testReset` disarms it.
- `FrontWarnings.java` (server, `ServerTickEvent.Post`, every 20 ticks): front warnings on the action bar.
  - The estimate: `Spread.secondsUntilStill(r1024, n, t, hard, tHard, hours, hardHours, cap)`, the online seconds until
    the player's column stills (Argon: time until this column stills, not distance to the front, so a dead-end cave
    whose mouth stills first is warned by its own column). The column stills at P = k = r1024 / SCALE + 1, coverage
    k / N, read back through the coverage formula. 0 once stilled; infinite above the cap before hard mode (P stands
    still at the 80 % plateau, so nothing is warned there); hard mode moves P again and the warnings come back.
    In hard mode the time to coverage k / N is read back through cHard's course. `/stasis spread status` uses the same
    estimate.
  - Who: overworld players in running spread land, not creative or spectator, where time runs now (`Stasis.isRunning`:
    in stilled land, a rift, a drifting deep or an eddy Freezing's messages own the action bar), and not inside a field
    or the spawn area (`Stasis.heldOff`: the spot keeps running when the column stills).
  - What: unsuited players once per threshold crossed: 10, 5, 2, 1 min ("The stillness reaches this spot in about N
    minutes", gold; several crossed at once say only the lowest), then a countdown each second under 30 s ("... in N s",
    red). Players with a working suit (`Suits.protectsWithoutDraw`) get one note at 30 s ("... your suit will take
    over"). A mark is forgotten when the estimate rises 1.5x past it (moving to higher ground), so a later approach
    warns again; and on leaving the overworld, logging out or the column stilling.
  - No client cue near the front (yet): the shader already draws stilled land grey.
- The overrun message ("The stillness has reached you: ...", `Spread.update` through `Freezing.graceIfStopped(..., true)`)
  goes to chat: on the action bar the grace countdown overwrote it at once. A field's loss keeps the action bar.
- `StasisCommands`: `/stasis spread status | set <coverage> | hardmode` (op). Status ends with the spawn move's
  state: not moved, searching (cells tried), moved (from, to), or no site found.
- `StabilityCompassItem.java` (`stasisdelvers:stability_compass`): points at the nearest place of temporal stability
  (rank / N, the coverage at which it stills) >= 90 %; past 90 % (hard mode) at rank >= P, so never into stilled land.
  The server searches (`nearestAtLeast`, cached per player until the player's cell or the threshold changes, asked
  every 20 ticks from `inventoryTick`) and stores an untracked `LODESTONE_TRACKER` target: the vanilla needle draws it,
  as the rift compass's. It spins outside spreading mode, off the overworld, when complete, or with no target. Use:
  "Temporal stability here: NN %" on the action bar. Recipe: a compass with amethyst shards N, E, S, W. Model: the
  vanilla compass models with a constant pale-amethyst tint (`items/stability_compass.json`).

- `mixin/StrongholdLibraryMixin`: `StrongholdPieces$Library.postProcess` TAIL, spreading mode only. One desk per
  library at the piece's local (6, 1, 13): on the floor against the back wall, facing the entry door, behind the last
  shelf row, off the tall library's ladder (x 10). A fixed spot, written only from the chunk it is in, so it is one desk
  whatever order the library's chunks generate in. It goes only into air or cobweb; under it, stone bricks if a cave
  left no floor (same column).
- `PendingGates.java`: SavedData `stasisdelvers:pending_gates` (per level): the portal rooms hard mode will turn into
  gate rooms and gate rifts (Baughn's decision 6: the full rift). `StrongholdPortalRoomMixin` queues each room (whole
  piece `BoundingBox` + centre) from the worldgen thread; `PendingGates.tick` (server thread) records it, deduplicated
  by centre (postProcess runs once per chunk the piece touches). The centre is the piece's local (5, 4, 10), the block
  above the portal's middle, the same one `Gates.portalRoom` takes, so hard mode can pass it on as it is; the
  orientation is kept too (`dir`; derived from box and centre for rooms recorded before it was, `orientationOf`).
  Only rooms generated before hard mode are recorded (`Spread.hardNow`, the worldgen threads' view; one queued in the
  moment before they saw it is activated as it is recorded). `endPortalRifts = false` turns recording off too (no gate
  rooms ever).

## Hard mode (step 4a)
- `HardMode.enter(server, cause)`: the one entry point, for the dragon's death and `/stasis spread hardmode`. Once only
  (the clock's `hard`); not outside spreading mode. At the instant:
  - `Spread.startHardClock`: `hard`, `hard_at` (online s), `hard_c` (the coverage then: the course goes on from it,
    so P doesn't jump), `hard_game` (game s: the storms' origin).
  - `Storms.clearOverrides`: test storm overrides go (they were ignored while storms were off; counted at once past
    the origin they would jump tau).
  - The End event begins (memory only): `HardMode.explodeEnd(end)` is called: a growl, explosions over the island
    growing denser, white flashes in each player's view, and `EndRift.finale` (step 5, below: the core heaves, shards
    fly out). `hardModeTeleportSeconds` (default 6) later comes the flash: everyone in the End but spectators
    is sent to their respawn point (`findRespawnPositionAndUseSpawnBlock(false)`, then `Freezing.safeRespawn`, the
    respawn rule: a bed or anchor in stopped land sends an unprotected player to world spawn), gets the advancement
    `stasisdelvers:breaking_it` "Good job breaking it, hero" (hidden, a challenge under "Free the End", end crystal
    icon, impossible trigger granted in code), and the short grace of a dimension change (`Freezing.grace`).
  - Also at the flash (`HardMode.endFight`) a dragon still alive (`/stasis spread hardmode`; AdventurAgent) is
    discarded (no death animation: no drops, no XP) and the fight is ended as a kill ends it (`dragonKilled`,
    `hasPreviouslyKilledDragon`, boss bar hidden and emptied; the AT opens those fields), or it would look for its dragon
    and spawn a new one. A dragon saved in a chunk not loaded then is dropped when it loads (`dragonJoin`).
  - Only at the flash does the End still (`State.endStill`, synced): stopped at the kill, its players would be statues
    (5 s) before the flash. `Spread.update` then invalidates and relights the End.
  - For 30 s after the kill (`END_WINDOW`) a player dying in the End is sent home the same way instead
    (`LivingDeathEvent`, highest priority, cancelled, health restored).
  - From the kill until the flash nothing hurts a player in the End (`HardMode.damage`/`shields`,
    `LivingIncomingDamageEvent`; damage tagged `bypasses_invulnerability`, `/kill`, still counts). The show is sounds,
    particles and block displays only (no `explode()` anywhere in it); AdventurAgent took 10.5 just before the flash,
    most likely from the dragon still alive after `/stasis spread hardmode`, or a fall.
  - A broadcast: "Something broke in the End. The stillness stirs again, and storms will rise from the rifts."
- `mixin/EnderDragonFightMixin` (spreading mode only): `setDragonKilled` keeps vanilla's bookkeeping (boss bar off,
  `dragonKilled`, `hasPreviouslyKilledDragon`) and calls `HardMode.enter`, but opens no exit portal, spawns no End
  gateway and places **no dragon egg** (the island is about to explode and nobody is left to take it; the vanilla
  "The Next Generation" advancement can't be had in this mode). `tryRespawn` is cancelled: no second dragon (it would
  build an exit portal). `LivingDeathEvent` isn't used: it comes 10 s before, at the start of the death animation.
- After hard mode no way leads into the End (`HardMode.endClosed`: `EntityTravelToDimensionEvent` to the End is
  cancelled for everything but creative and spectator players; a stronghold portal still stands until its chunk is
  rewritten, `HardRifts`).
  Only the lance gets through (`Lance.travelling`; docs/lance.md). A player who logs in in the End after the flash (a restart mid-event, a long
  absence) is sent home the same way, with the advancement, unless they came in by lance (`HardMode.evictsOnLogin`).
- A restart during the event: hard mode stays, the event is gone, the End is stilled at once. The End rift's build
  resumes from its saved progress (`end_rift`, below).

## The End's island breaks up into the End rift (step 5)
Baughn: the End island explodes into a gigantic rift of the "floating islands surrounding static" kind, with end stone
instead of ores; "the core island stays until the dragon is dead, but destruction starts as the end crystals are
broken: they're stabilizing the problem", end stone lifted off as entities and flown to its place in the rift's
islands. `EndRiftPlan.java` (pure) and `EndRift.java` (the server side).
- The plan (a function of the End's deep seed and its pillars, `EndSpikeFeature.getSpikesForLevel`):
  - The main island's columns within `ISLAND_RADIUS` (150) are cut into parts: one per pillar (the columns outside the
    core nearest that pillar's direction, edges wobbled by noise), and the core (radius `CORE_RADIUS` 60 ± 6: the
    pillars at 42, the fountain, the fight). Each part's columns are ordered chunk by chunk from the outside in.
  - Floating islands: lumpy, flat-topped, tapering below, end stone, some with obsidian (35 %) or a dead chorus flower
    on top (40 %), in a shell 62-160 blocks round the rift's centre (0, 64, 0), flattened (few straight above or below),
    at most `BLOCK_BUDGET` 60 000 blocks (about 60-90 islands), none within any static deep's zone (the End rift's or
    the End's own grid deeps). Each island belongs to the part of the sector it hangs over, or to the core's if any of
    its blocks below `ISLAND_TOP` (110) lies over a column of another part (it would meet ground still standing).
  - The End rift itself: `StaticDeeps.endRift(seed)`, a gate-like extra static deep (radius `END_RIFT_RADIUS` 40, core
    ~14 blocks, at (0.5, 64.5, 0.5); `isGate`: no strata, no puffs, leads to the End). It is registered per deep seed
    (`StaticDeeps.addEndRift`, add only) on the server from hard mode on (`EndRift.tick`, `finale`), and on the client
    while in the End with the spread's `hard` flag (`StasisClient`'s tick): no payload, both sides know hard mode and the
    End's seed. It only shows and acts once the End stills (the flash): `Stasis.stops` gates `inRift` and the post
    shader. `StaticDeeps.near` lists it first (the shader keeps four).
- Stages (before the dragon dies): a pillar's own crystal destroyed by any means (`EntityLeaveLevelEvent` with a
  destroying removal reason, inside that pillar's `getTopBoundingBox`; vanilla's pillar crystals show their bottom,
  a player's never do, so placed crystals are ignored) records that pillar once (`broken` bit) and queues its part:
  - Lift: its columns lose their end stone (only end stone: players' blocks, the arrival platform's obsidian, chests
    stay, floating; writes skip neighbour and shape updates so nothing pops), except where a rift island's block goes
    (`EndRiftPlan.isIsland`): islands already placed over a column lifted later stay, and island end stone where the old
    island stood stays put. With players in the End, each chunk is warned `WARN_TICKS` (30) before it goes with
    end-stone dust and a crumbling sound (as a chunk begins, the next two are warned), and a
    column within 2 blocks of a player's feet is put off with dust and "The ground crumbles under your feet!" until they
    step away or 3 s pass (then it goes). The stage starts with a boom
    and an action-bar line, "The island shudders: that crystal was holding part of it together."
  - Shards: with players watching, about 260 of the part's top end-stone blocks (320 in the air at most) fly as block
    displays from where they stood to an island block of the part, on an arc with a tumble: four segments of 12-22
    ticks, each a transformation interpolated by the client (the display itself stays put), seen from 320 blocks. When
    one lands its real block is set there (in air only) and the display goes.
  - Place: once the shards have landed (`FLIGHT_MAX` after the lift), the part's islands fill in, bottom up, chunk by
    chunk, into air only.
  - Pace: `STAGE_WRITES` 512 block writes a tick (plus 2 per column scanned) and `STAGE_PLACES` 96 island blocks a tick
    while players are in the End; `BUILD_WRITES` 4096 with nobody there.
- The finale (`EndRift.finale` from `explodeEnd`, the dragon's death): every part not yet lifted is queued. Until the
  flash the core heaves (4 shards a tick thrown from its surface to the core's islands, purely cosmetic, reverse-portal
  particles round the rift's centre) and nothing under the players goes; at the flash the shards are dropped
  (`EndRift.flash`) and from then on every remaining part is lifted (the core also loses the pillars' obsidian, the
  fountain's bedrock and torches, iron bars, fire, any portal block and the egg, and a pillar's own crystal still
  standing is discarded with its pillar) and every island placed, at 4096 writes
  a tick (512 if a creative visitor is there). A stage running at the kill goes on at its pace.
- Chunks: the work goes chunk by chunk; a chunk not loaded is force-loaded through the `stasisdelvers:end_rift` ticket
  controller (non-ticking), with the next one prefetched, and released when done (all released when the queue empties).
  Tickets saved with the level are dropped on load; the work forces again what it needs.
- The End event as players see it (`HardMode.endEventOn`): from the first stage (`EndRift.begun`: a pillar broken or
  the finale) until the flash. Meanwhile a survival or adventure player below the End's floor (y < 0, long before
  void damage) is rescued (`HardMode.voidRescue`, `PlayerTickEvent.Post`): put on the core island's top by the fountain
  (`heart`: the first solid column 6-30 blocks out, or the arrival platform), slow falling for 5 s, "The island gives
  way; you're thrown back to its heart." (AdventurAgent: a player in a crumbling sector fell and died.) Clients see
  the fog pushed out meanwhile (`State.endEvent`, below).
- After hard mode a pillar's crystal no longer starts a stage (the finale has them all). `/stasis spread endstage` too.
- Restart: the jobs come back from `end_rift` (pillars broken, parts lifted and placed, and per part the chunks done),
  so a part resumes from its next chunk; a chunk redone after a crash changes nothing (only the island's own blocks go,
  only air is filled). Shards are memory only: a saved one loading again is dropped (`EntityJoinLevelEvent`).
- Cost: the plan is built once per seed (~0.1-0.5 s, cached; built when the first player is in the End, not at the
  first crystal mid-fight); the island scan reads each column once; a vanilla island
  is a few hundred thousand block writes in all, most of them at the finale with nobody there (about a minute with
  chunk loads).
- Lab procedure (AdventurAgent; a lab server in spreading mode, a copy of a world whose dragon is alive, not the live
  world: none of this can be undone):
  1. Go to the End by its portal (or `/execute in minecraft:the_end run tp @s 100 50 0`), survival, and look over the
     island from the arrival platform. `/stasis spread endstatus`: no pillars broken, the plan's islands and blocks.
  2. Break one pillar's crystal with a bow: the boom and the action-bar line; that pillar's outer sector crumbles from
     its rim inwards (dust a moment ahead), shards arc up and out and land as end stone; then that sector's islands grow
     in. `endstatus` shows the lift and place jobs moving. Stand on the crumbling edge: the warning under your feet,
     about 3 s before it goes. Your own blocks there (place some) stay, floating.
  3. Without a fight: `/stasis spread endstage <0-9>` lifts that pillar's part as if its crystal broke (the pillars are
     numbered as `EndSpikeFeature` lists them). Do two or three; watch the frame rate and the server's tick time (F3,
     `/tick query`) while one runs.
  4. Restart the server mid-stage; `endstatus` afterwards: the job resumes from its next chunk; no hole is dug twice.
  5. The finale: kill the dragon (or `/stasis spread hardmode`). For 6 s the core heaves (shards flying out over the
     explosions), then the flash sends you home. As a spectator (`/gamemode spectator`, then
     `/execute in minecraft:the_end run tp @s 0 140 120`) watch the rest go and the islands form; `endstatus` until
     "(built)". Then the End is stilled: the rift's black eye hangs at the old island's middle, mist round it, the
     islands circling it, the wall (`EndBarrier`) still there. The log: "End rift: built".
  6. Check by eye: shards' arcs and tumble (smooth, not stepping), the dust warnings, the islands' look (lumpy, flat
     tops, obsidian flecks, dead chorus), the eye and mist in the stilled End.

## The strongholds at hard mode (step 4b)
Baughn's decision 6: "Carve the full rift. It's intended as a cataclysm." `HardRifts.java`.
- At the instant (`HardMode.enter` → `HardRifts.begin`): the rooms worldgen queued are recorded, then every recorded
  room is activated: its gate rift is registered at once (`Gates.register`: `rift_gates`, `StaticDeeps.setGates`,
  `GateRifts` to the level's players; it has to come before any block is written, since `isGate` gives the rift its
  destination, the End, for the shell's scatter). Players within 64 blocks get a chat line ("The ground heaves: ...");
  players the new rift stopped who stood in running time get the usual grace (`Freezing.graceIfStopped`, in chat).
  Nobody is teleported: the rift's core is ~3 blocks across and the grace is enough to step out.
- What is written: what the stopped mode generates for the room (the frame ring and portal cleared, `GateRoom.shatter`),
  plus the gate rift's carve (`DeepFeature.apply`: the hollow, shattered shell with its scatter, crevasses, the sinkhole
  to the surface when the room lies within ~40 blocks of it, dislocations, dripstone, sky shards). Gate rifts have no
  strata anywhere (`StaticDeeps.isGate` is skipped by `StrataMask`, `Pockets`, `Crystals`, `Ruins`), so none are
  written: strata rock there would be inert (no pockets, no crystal growth, no client mask).
- Per chunk, from the recorded box, orientation and centre (Argon's second option): `shatter` and the carve touch
  only the chunk's own columns, as at worldgen, so no chunk waits for its neighbours and the order doesn't matter. The
  chunks are those the carve reaches (`DeepFeature.reaches` without strata: ~37 blocks round the centre, ~30 chunks).
  Chunks loaded at the instant are queued at once; the others when they next load (`ChunkEvent.Load` → the server tick).
  The queue is worked `CHUNKS_PER_TICK` (2) a tick. A chunk within 16 blocks of the centre whose room part is still due
  waits until its entities are loaded (the shards and silverfish are entities; added earlier they could be lost).
  Such chunks get no new-chunk grace (`NewChunkGrace.exempt`, as the mixin's room chunks), or a chunk first generated
  after the instant would let the room's lava and debris settle.
- Progress is saved per room and chunk (`shattered`, `carved`): a restart resumes (the first hard-mode tick re-activates
  every room, idempotently, and queues their loaded chunks) and nothing is written twice.
- The writes: flags `UPDATE_CLIENTS | UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS`. `LevelChunk.setBlockState` keeps the light
  and the four live heightmaps up to date itself, and the chunk holder sends each tick's changes per section with the
  light, so no chunk is resent and no heightmap re-primed (the carve reads the live `WORLD_SURFACE`, the worldgen one's
  not-air rule). Block entities in the way are removed: EA's `NodeBlockEntity.setRemoved` then removes its node (and
  any orphan). Containers' contents are destroyed with them, not dropped (cheaper, and no item floods in a rift). EA's
  ghost blocks (multiblock parts) get `UPDATE_NEIGHBORS` too: their `affectNeighborsAfterRemoval` breaks the element.
  Players' builds in the carve go (intended). The shatter's lava ribbons flow where the land still runs.
- After the instant new portal rooms generate as in the stopped mode (`StrongholdPortalRoomMixin`: shattered at
  worldgen, gate registered, no carve, as there). A stronghold straddling the instant has its later chunks shattered
  at worldgen; the mixin notes them (`PendingGates.shatteredAtWorldgen`) and only their carve is done here.
- Lab check (a real world, not run yet): a lab server in spreading mode with a fresh world; `/locate structure
  minecraft:stronghold`, fly there and find the portal room (spectator), so its chunks generate and the room is
  recorded (the log: "Stronghold portal room at X, Y, Z recorded for hard mode"); put a chest, an EA cable and battery
  in the room and stand a survival player beside the portal; also note a second stronghold's coordinates without
  visiting it. `/stasis spread hardmode`: the log names the rooms; the near player gets the chat line and grace; within
  a second the room shatters (no frames, shards, debris, lava ribbons), the rift hollows its core and a sinkhole opens
  above if it is shallow; the EA nodes are gone (`/elnlab` or the node count in F7), no items dropped. Relog: nothing
  changes again. Then visit the second stronghold: it tears open as its chunks load. Restart mid-way (with chunks still
  queued): it resumes. Generate a new stronghold after hard mode (far `/locate`): a gate room at worldgen.

## The End's wall (λ-Nero)
- `EndBarrier`: a vertical cylinder of radius `endBarrierRadius` (default 140) round 0, 0, on while `endBarrier` (default
  true) in spreading mode before hard mode (`EndBarrier.active`, both sides: the server's and the client's spread state). It
  goes at hard mode (Baughn's decision 7 for the lance: lance arrivals land on the rift's islands and in the outer End).
  Creative and spectator players pass.
- It stops movement like a world border (AdventurAgent: the old server-only put-back rubber-banded, 1.4 blocks every
  0.4 s): `mixin/EndBarrierMixin` clamps `Entity.collide`'s result with `EndBarrier.clamp` on both sides, so the client
  predicts it (the config is synced) and the server's own move check (`handleMovePlayer` runs `player.move`) agrees.
  For a survival or adventure player, or a vehicle one steers, the horizontal end point is pulled radially back so the
  box touches the wall: walking in stops, pressing along it slides round it, someone already outside may move inward or
  along only. Not a collision `VoxelShape`: boxes are axis-aligned, and a staircase circle catches a sliding player at
  every inner corner.
- Fallback (`PlayerTickEvent.Post`, `check`): a player more than `SLACK` (2) blocks past it (a pearl, lag) is put back
  where they last stood on the ground at least a block inside, or, with none on record, on the surface 4 blocks inside
  the wall towards them, or on the obsidian arrival platform (100, 50, 0) if that column is void. "A wall of stilled
  time holds you to the island" on the action bar.
- Every way in lands inside: the End portal's arrival platform is at 100 blocks, and no End gateway ever appears in
  this mode (the mixin), so no player or pearl goes through one. The outer islands' return gateways are out of reach.
- Client: `client/EndBarrierFx`, particles on the cylinder within 20 blocks of it (pale dust over its face, black dust
  lines every 4 blocks round and 6 up). The field shield's look is in the post shader, which is off in a running End,
  so this is a modest stand-in: **AdventurAgent to check it by eye**.

## Persistent state
- Overworld SavedData `stasisdelvers:spread_map` (`algorithm`, `spawn_x`, `spawn_z`, `radius`, `ranks` int array),
  written once. A saved map is never rebuilt, whatever its algorithm: a world made with v1 keeps its v1 map (logged at
  load). A missing `algorithm` reads as 1 (DFU drops fields equal to their default, so v1 saves lack it). Delete
  `spread_map.dat` to rebuild with the current algorithm (the front then moves under existing bases); `stasisdelvers:spread_clock` (`seconds`, `hard`, `hard_at`, `hard_c` (coverage at the instant, -1 in older saves:
  the cap), `hard_game` (game seconds at the instant: the storms' origin, -1 unknown), and the spawn move: `spawn_move` 0 not moved / 1
  moved / 2 no site, `spawn_old_x`, `spawn_old_z`, `spawn_x`, `spawn_y`, `spawn_z`; all default 0 for older saves).
- SavedData `stasisdelvers:pending_gates`: a list of `{box: [minX, minY, minZ, maxX, maxY, maxZ], centre, dir,
  shattered: [chunk], carved: [chunk]}` (packed ChunkPos; `dir` derived and both lists empty in older saves).
- End SavedData `stasisdelvers:end_rift`: `broken`, `lifted`, `placed` (bits per pillar / part; the core is the last
  part), `finale`, `lift_done` and `place_done` (per part and chunk: part << 48 | cx << 24 | cz, 24 bits each); all
  default empty for older saves.
- Memory only: the End event (`HardMode`), the wall's last ground positions (`EndBarrier`), the stronghold rewrite's
  queue (`HardRifts`: rebuilt from the progress and chunk loads), the End rift's jobs (rebuilt from `end_rift`), its
  shards and forced chunks.

## Client / server split
- The client answers every column within its tiles exactly as the server (the same `rank1024` on the same integers),
  so stopped-water collision, frozen textures and entity freezing agree. Before the state arrives, while the server
  has no map, and for cells no tile covers, the client says "running".
- Drifting deeps are computed on both sides from the deep seed and game time (as in stopped mode); the client's game
  time can be a tick or so off, so at a moving deep's edge the two may disagree for a moment.
- `SpreadState` carries `endStill` and `stormOrigin` (protocol 12), so the client's End stops with the server's and its
  tau and storms (`Storms.clientOrigin`) match, and `endEvent` (protocol 13; `EndRift.crystalBroken` refreshes it).
- During the End event `client/EndEventFog` (`ViewportEvent.RenderFog`, atmospheric fog in the End only) eases the
  environmental fog's start and end out to the render distance's over 2 s, and back after the flash (AdventurAgent:
  the fog hid the show past ~40 blocks). Note: in 26.3 the End sets no fog distance (the defaults, a faint linear haze
  from 0 to 1024 blocks), so this only clears that haze; a hard cutoff near 40 blocks is the render-distance fog of
  the client's effective render distance (min of its own setting and the server's view-distance, 10-16 on the labs),
  which no fog setting can push past the chunks it has. **AdventurAgent to check by eye, and its render distance.**
- Re-meshing: `FrozenSprites.spreadMoved` (at most once a second) re-meshes and relights the loaded chunks whose
  [`chunkMin1024`, `chunkMax1024`] the new P crossed, and those a new tile covers that aren't wholly running. Near a
  player that is a handful of chunks a minute.
- The post shader: a 64x64-cell rank window and P in data slots 196-199, the same test in 32-bit integers
  (`stilledSplit`), and the drifting-deep test at block centres (client-rendering.md).
- Xaero's overlays use `rateAtLand`; the world map refreshes as P moves by 1/2000 of the map.

## Invariants and tests
- `SpreadMapTest`: ranks are a permutation of 0..N-1 (any split); build is deterministic; the ranks from the split up
  follow S and every low cell is at most as stable as the least high one; below the split, BFS from spawn's cell
  reaches every running low cell, and block by block (the bilinear) every running low cell's centre column; the compass
  search equals brute force; `cellsAtLeastAreOrdered` (the spawn move's candidates are exactly the cells >= minRank,
  by distance then index, the first at nearestAtLeast's distance); chunk extremes are exact; coverage formula, monotone and
  continuous across hard mode's entry (`hardModeBeforeTheCapGoesOnFromWhereItWas`), capped tick; `secondsUntilStillMatchesTheClock` (after the
  estimate the column is stilled, a moment before it isn't; infinite above the cap before hard mode, finite in it; 0
  once stilled) and `secondsUntilStillAtThePlateau` (worked numbers).
  `coherenceReport` logs, at 20/50/80/90/95 %, the running cells' components, those of >= 100 cells (~320 x 320
  blocks) and their share, the largest, and the shore share (running cells next to stilled land); it dumps the lab
  seed's map to `build/spread-lab-v2.ranks` for `tools/spread-render.py` (renders: `notes/spread-renders/v2-*.png`).
  Lab seed 3201319, 8 km: at 50 % 38 components, 11 big holding 99.6 %; at 80 % 38, 20 big holding 98.9 %, the
  largest 29 %; at 95 % 32, 17 big holding 97 %.
- `SpreadTilesTest`: tile lookup = map lookup on random maps (through the codec), uncovered cells run,
  `stilledSplit` = the exact sum at every offset and at P on each boundary. `NetCodecTest`: the three payloads.
- GameTests (isolated): `spread_rates` (at P = N/3, below the split); `stability_compass_target` (the target is the
  nearest cell >= 90 %, it spins in stopped mode); `spread_spawn_moves_once` (spawn stays while its column runs;
  once P passes its rank it moves to a running column of rank >= max(0.9 N, P + 1) on dry solid ground with air above;
  P past the new spawn's rank leaves it there; status says so); `stability_compass_first_join` (one compass, nothing else, not again); `spread_deep_stops_running_land` (rate 0 and no block tick in a drifting
  deep over running land, the land's rate 1, a field holds it off, an unsuited player is held, not a statue);
  `spread_front_warnings` (the clock set so P is just below the player's column: nothing at 20 min, "about 5 minutes"
  at 250 s once, "about 2 minutes" at 100 s, the countdown each second under 30 s, the marks forgotten on moving back,
  nothing in a field, nothing once stilled); `spread_tiles_follow_player` (the server's tile list follows the player); `spread_stronghold_rooms` (vanilla
  library and portal room pieces built chunk by chunk, each straddling four chunks: no desk in stopped mode, one desk
  per library in spreading mode, in rock and in a cave; the portal room keeps its 12 frames, and its portal when all
  12 eyes rolled, gets no shards, and is recorded once with its whole box although generated twice over);
  `spread_hard_mode_gate_rooms` (a recorded room in a block of rock, an EA source and a chest with diamonds in the
  rift's hollow, a running player at the centre: at hard mode the gate is registered at once, its core is rift, the
  player has the grace, the chunks are queued and not yet written; then (looked at each tick) every chunk is shattered and carved,
  no frames or portal, shards hang, the hollow is air, the EA node is gone, no diamonds dropped; a block put in the
  hollow survives the progress saved and read back and every chunk loading again; a room generated after the instant
  is shattered at worldgen, not recorded, and its gate registered).
- `spread_hard_mode_entry` (two players in the test End beyond 192 blocks of the centre, so its dragon fight stays
  asleep): the entry point enters once; P doesn't jump and status names the instant's coverage; the storms' origin is
  the instant, tau = t before it and storms are scheduled after it; the End runs until the flash; a death in the End
  before it is a teleport home with the advancement; until the flash damage is turned away (`shields`; `/kill` isn't),
  not after; at the flash the other is sent home with it and the End stills, a dragon spawned before the entry is gone
  with no XP orbs and the fight (if the test End has one) is over; then the End can't be entered.
  `end_event_void_rescue`: no rescue before any stage; a stage sets the End event (and the synced flag); a creative
  player isn't rescued; a survival one below the floor lands on solid ground by the fountain with slow falling. `spread_end_barrier`: past the wall with no ground on record, put just inside on the
  ground; after standing inside, put back there; creative passes; no wall in the stopped mode.
  `spread_end_barrier_stops`: a mock player walking and sprinting into the wall (`move`) ends at it, never past it, is
  never put back; pressing along it slides round; outside, no further out; creative passes. The mixin's suppression
  of the portal, gateway and egg needs a real dragon fight: not tested (by eye in the lab). Both switch the End rift
  off (`EndRift.testOff`): its finale would rebuild the test End.
- The End rift: `EndRiftPlanTest` (unit, jqwik): the plan is deterministic and within its budget; every island block
  is in the shell, in one part only and outside every static deep; the parts partition the island's columns (the core
  holds radius 50, nothing past 67 is core), each sector ordered from the outside in; an island of a pillar's part never
  hangs below `ISLAND_TOP` over a column of another part; every shard has a target. GameTests:
  `end_rift_lift_and_shards` (a sector's column loses only its end stone, a core column its obsidian too but not a
  chest; a shard is in the air midway, lands its block in air only and goes; the codec with defaults);
  `end_rift_crystal_stages` (paused: in the test End a pillar's crystal discarded or killed records its pillar once, a
  player's crystal or one away from the pillars doesn't, the command's path records another, the lifts are queued);
  `end_rift_stage_builds` (two stages carried out with nobody there, a restart after 25 slow ticks resuming from the
  saved chunks: the sectors hold no end stone but the islands', the islands stand, the core is untouched, no chunk
  stays forced); `end_rift_finale_builds` (the dragon's death, built in a stilled End as after the flash: everything
  lifted and placed, a pillar stump, its crystal and the fountain's bedrock gone, a player's block floating, a shard
  landing in stopped time, the rift's core registered). The test End is flat (bedrock and
  three end stone, no pillars): the tests use `getSpikesForLevel`'s positions and lay the flat ground back after.
  Not tested: the shards' look, the warnings with real players on the island, a real island's numbers (lab).
- Eyes of Ender: nothing here changes where strongholds are placed or `#eye_of_ender_located`; the portal room's
  blocks are vanilla's. The GameTest world has no strongholds, so this is not tested in a real one.

## Gotchas
- The spawn move is once only (Baughn): a spawn that stills again stays, and a `/setworldspawn` after the move is
  never moved. A `/setworldspawn` into stilled land within the map before the move triggers it at once.
- A running column's rift still stops (Baughn). Below the split a corridor could pass a rift's column; rifts still early
  (S dips there), and they are 3D, so there is usually a way round, but nothing guarantees it.
- Above the split (default 50 %) running regions can be cut off from spawn and from each other, on purpose.
- Spawn's own region can still at the split: the spawn raise is mild (on the lab seed spawn sits in a low area).
- Entering hard mode before the cap goes on from the coverage of that instant (`hard_c`). A save already in hard mode
  from before `hard_c` was kept reads it as the cap, as it then behaved.
- Changing `spreadRadius` or `spreadConnectedUntil` after the map is built changes nothing; delete `spread_map` to
  rebuild.
- `Deeps.at` is cached per tick by 256-block region (from `within`, a superset of `near`): it is asked per block in
  running land now. Off the server's main thread the deep test is skipped (it reads a heightmap); off-thread callers
  use `rateAtLand` anyway.

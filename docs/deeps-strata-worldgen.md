# Deeps, strata and worldgen

Paths are relative to `src/main/java/org/erisia/stasis/`; data paths to `src/main/resources/data/`.
For the runtime side of deeps (weather, rifts, storms), see rifts-storms.md.

## The deep seed
- Each stopped level has its own **deep seed**, `FieldData.deep_seed`: 24 bits in 1..0xFFFFFE.
- It is **random** (`new java.util.Random()` in `FieldData.deepSeed()`), **not derived from the world seed**.
  Two worlds with the same world seed get different rifts, and so does each dimension.
- It is created on the main thread at level load (`StasisEvents.levelLoad` calls `FieldData.get(level).deepSeed()`),
  then saved with the fields.
- Readers:
  - `Deeps.seed(level)`: the main thread reads `FieldData`; other threads read `FieldData.peekDeepSeed`.
  - The client reads `ClientFields.deepSeed()`, which arrives in `StasisNet.Fields`.
  - `DeepFeature.place` reads `peekDeepSeed`.
- Seed 0 means "not loaded". `DeepFeature.place` then quietly does nothing, and Deeps and Storms are off.
  Rift worldgen is a pure function of the seed only once `FieldData` is loaded for the level.
- GameTests (`stasisdelvers.noSpawnArea`) see seed 0 unless `Deeps.testDeeps` is set. `FieldData.testDeepSeed`
  pins a seed.

## Worldgen classes
- `DeepFeature.java`: feature `stasisdelvers:deep_dislocations` (`worldgen/feature`, `worldgen/placed_feature`).
  It is added to `#is_overworld` biomes by `stasisdelvers/neoforge/biome_modifier/deep_dislocations.json`.
  - It runs once per new chunk and writes only that chunk's columns. For each static deep within reach,
    `place` calls, in order:
    1. `carve`: the hollow rift, its shattered shell, crevasses, sinkholes, dripstone, dislocations and sky islands
    2. `strata`: riftstone, cavities, vents, roots and budding crystals (`budding`)
    3. `TimeScars.place` (config `timeScars`)
    4. `Ruins.place` (config `ruins`)
  - It then re-primes the chunk's heightmaps.
  - Writes go through a `Writer` (worldgen: `setBlock` flag 2) and the surface heightmap is a parameter, so one deep can
    be written into one existing chunk: `apply(level, writer, surface, deep, x0, z0, withStrata)` (hard mode's gate
    rifts, `HardRifts`, with the live `WORLD_SURFACE`). `reaches(deep, x0, z0, withStrata)` is the reach test.
- `Strata.java`: four shapes (`Shape`: SADDLE, STAIRCASE, SPLASH, PODS), weighted by config `strataShapes`
  (`setWeights`). `shapeOf`, `bounds`, `at(deep, x, y, z)` returns the strata class. SADDLE roots are cached
  (`roots`).
- Rift ores (Baughn; λ-Nero asked for ore in riftstone): `DeepFeature.ore` turns ~4 % of the shattered shell (in
  `carve`) and of the strata rock (in `strata`) into ore, in veins (Baughn, 2026-10-20: veins, 6-8 times the old 0.6 %
  scatter of single blocks; config `riftOres`, `riftOreShare`; new chunks only). Each 7-block cell may hold one vein, a
  capsule of radius 1-1.6 and length 1-4 along a random direction, ~3/4 filled; rare ores make smaller veins. It draws
  over the sources in `c:ores_in_ground/stone` and `/deepslate` (the pack's ores included; diamond and emerald at 0.15,
  gold, lapis and redstone at 0.5), then places each source's **riftstone ore** (`RiftOres.variantOf`), or the source
  itself when it has none.
  - `RiftOres.java` registers one riftstone ore per material from `RiftOreTable.java` (generated): coal, copper, iron,
    gold, redstone, lapis, diamond, emerald, tin (EnergizedPower), silver (Occultism), sal ammoniac (Theurgy). Each
    stands in for its stone and deepslate source: hardness 3/3 like every stone source, the sources' XP (redstone is a
    `RedStoneOreBlock`, so it lights up when touched).
  - Loot (`data/stasisdelvers/loot_table/blocks/riftstone_<m>_ore.json`): silk touch gives the riftstone ore, anything
    else runs the stone source's loot table (Fortune included). A pack mod's table loads only with that mod
    (`neoforge:mod_loaded`): a reference to a missing loot table stops the server.
  - Tags (block and item): the sources' own (`c:ores/<m>`, `minecraft:<m>_ores`, `c:ore_rates/*`, `needs_*_tool`),
    `mineable/pickaxe` and `c:ores_in_ground/riftstone`; never `c:ores_in_ground/stone|deepslate`, or the scatter would
    draw them as sources. Vanilla ores smelt per item, so their smelting and blasting recipes are copied; tin and
    silver smelt by `c:ores/<m>`, and EA's macerator uses tags. Not covered: Theurgy's liquefaction of coal ore (per
    item).
  - `tools/ores/gen_riftstone_ores.py` regenerates all of it (textures: the stone ore's pixels off stone's grey levels,
    over `riftstone.png`; models, blockstates, items, names, loot tables, tags, recipes, `RiftOreTable.java`) from the
    vanilla client jar, NeoForge and the pack's mods (default `/srv/minecraft-lab/spawn/alt-server/mods`). Rerun it
    when the pack gains an ore in those tags; it stops on a material it has no XP entry for. `--preview PNG` writes a
    contact sheet.
- `TimeScars.java`: water and lava falls stopped mid-fall, hanging gravel, arrow volleys; `cropAge` for
  `StructureTemplateMixin`. Chunks with scars get no new-chunk grace.
- `Ruins.java`: vanilla structure pieces (village houses, shipwrecks, fossils) stamped 1-2 per rift into the
  strata and cut by tilted noise. They keep vanilla loot. Not in gate rifts.
- `Gates.java`: each static rift leads somewhere by weight (config `riftDestinations`, default End 2 : Nether 1).
  - `scatter` puts the destination's blocks into the shell (config `riftScatter`).
  - Gate rifts replace End portals: `StrongholdPortalRoomMixin` → `GateRoom.shatter`. In stopped mode a gate rift has
    no worldgen of its own (no carve, no strata): the room is its terrain. In spreading mode the room stays a working
    End portal room until hard mode and is recorded in SavedData `pending_gates` (`PendingGates`) instead; at hard mode
    `HardRifts` writes the shattered room and the gate rift's carve (Baughn: the full rift, a cataclysm; still no
    strata) into the existing chunks, chunk by chunk (spreading-mode.md). Rooms generated after hard mode get the
    stopped mode's room at worldgen.
  - `Gates.register`: the server-thread registration (saved, published, sent); `Gates.tick` and `HardRifts` use it.
  - Gate centres are SavedData `rift_gates`, synced as `GateRifts`, and enter `StaticDeeps.setGates`.
- `GateRoom.java`: the stronghold portal room frozen mid-implosion: block-display shards and hanging debris
  (`fallDebris` ticks). Its chunks are exempt from the new-chunk grace (`NewChunkGrace.exempt`). `shatter` writes
  only inside the chunk box it is given, through a `DeepFeature.Writer` (so `HardRifts` can rebuild the piece from a
  recorded box and orientation and shatter a live chunk).
- `RuinedCottages.java`: structure type `ruined_cottage` (`worldgen/structure/ruined_cottage.json`,
  structure set ~1/km²). Templates come from `stasisdelvers/structure/ruined_cottage/*.nbt`.
  - The `RuinedCore` processor rewrites tower NBT: dead, `coreDrop` 0, `ruined`.
  - `dormant` returns true when there are no templates.
- `EndSites.java`: End towers and frozen airships replace end cities and ships
  (`src/main/resources/data/minecraft/tags/worldgen/biome/has_structure/end_city.json` is emptied). Without a template, a marker is placed.
- `MapDeskBlock.java`: the surveyor's desk. One map to an unclaimed ruined cottage per player, ever
  (player NBT `stasisdelvers_map_desk`; structure tag `on_cottage_maps`). In spreading mode one stands in each
  stronghold library (`StrongholdLibraryMixin`, a fixed spot written from its own chunk only).
- `StructureTemplateMixin`: crops in structure templates get random growth stages (`TimeScars.cropAge`).

## Persistent state
- `FieldData.deep_seed`; SavedData `rift_gates`; in spreading mode `pending_gates` (portal rooms for hard mode, and
  per chunk whether hard mode's rewrite shattered and carved it).
- Generated blocks are the only record of worldgen. Nothing else about strata or rifts is saved; it is all
  recomputed from the seed.
- `RuinedCottages` and `EndSites` keep their StructureStarts in their chunks (phase-2 swaps are possible).

## Client / server split
- The client recomputes strata (`client/StrataMask.java`, through `Strata.at`), static deeps and gates from the
  seed + `GateRifts`. What it draws must match the rock the server generated.

## Invariants and tests
- **Chunk-order independence:** every shape is a pure function of (deep, world position), and writes stay
  inside the chunk being generated. The one exception is `DeepFeature.carvable`, which reads fluids in
  neighbouring blocks, possibly in the next chunk; it refuses to carve next to fluid.
- Strata: `strata_shapes`, `strata_pockets`, `strata_rift_pocket`, `strata_mist_pocket`.
- Gates and rooms: `rift_gates`; spreading mode's library desk and recorded portal rooms: `spread_stronghold_rooms`;
  hard mode's rewrite of them: `spread_hard_mode_gate_rooms`. `HardRifts` writes into existing chunks with the same
  pure functions and the same chunk-local rule (each chunk only its own columns, any order). Ruined cottages: `ruined_cottage_core`, `ruined_cottage_templates`.
- End sites (registration only): `end_sites_replace_cities`.
- Crystals in worldgen: `rift_crystal_grades`; `rift_crystals_supported` (test rifts in the sky and near the ground,
  written chunk by chunk in a shuffled order: every crystal has its rock).
- **Every crystal has its rock** (ErisiAgent: clusters floated over a sky rift). `budding()` only says where a budding block
  belongs; `strata` writes one only where rock may go (not open sky off the islands, not the carved hollow), so a cavity
  bud goes only on a wall in the same chunk that really is a budding block. `DeepFeature.Supported` (the writer of `place`
  and `apply`) then takes away, last of all (after scars and ruins), any crystal it wrote that can't survive.
- Rift ores: `riftstone_ore_drops` (drops, silk touch, Fortune, tags, hardness against the vanilla sources),
  `riftstone_ore_scatter` (the draw, and a test rift's shell, give riftstone ores only).

## Gotchas
- Some config keys look worldgen-only but are also read at runtime. Changing them mid-world desyncs the game
  from rock that is already generated:
  - `strataShapes`: `Pockets.masked`, `Crystals`, `client/StrataMask`
  - `riftDestinations`: `client/StasisPost` (each eye's destination, data slots 200-203)
- `DeepFeature` only touches chunks generated after it existed. Old chunks keep their terrain. The one exception is
  hard mode's gate rifts (`HardRifts`), which are written into existing chunks on purpose.
- To give old chunks their rifts (Baughn 2026-10-19: no reset, "just the chunks with rifts in them"): `RegenRifts`.
  `/stasis regenrifts plan` writes `<world>/stasisdelvers-regen-plan.txt`, every generated overworld chunk that
  `DeepFeature.deepsAt` (the test `place` uses) reaches, read off the region files' location tables (no chunk loads);
  the spawn area and recorded portal rooms are `EXCLUDE`. Then, server stopped, `tools/regen-rift-chunks.py` (outside the
  repo, in `/var/lib/mclab/agent/tools`) deletes the planned chunks nobody settled (InhabitedTime, EA block entities, OPAC
  claims) and writes `stasisdelvers-regen-done.txt`; at the next overworld load `RegenRifts.applyDone` forgets those
  chunks' rift crystal buds and swept marks (`Crystals.Data`) and gutters, then renames the file. Kept chunks inside the
  plan (inhabited) keep their old terrain: expect cliffs on their borders with regenerated ones.
- Worldgen runs on worker threads. Use `peekDeepSeed` and pure functions there, never `FieldData.get`.

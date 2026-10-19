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
- `Strata.java`: four shapes (`Shape`: SADDLE, STAIRCASE, SPLASH, PODS), weighted by config `strataShapes`
  (`setWeights`). `shapeOf`, `bounds`, `at(deep, x, y, z)` returns the strata class. SADDLE roots are cached
  (`roots`).
- `TimeScars.java`: water and lava falls stopped mid-fall, hanging gravel, arrow volleys; `cropAge` for
  `StructureTemplateMixin`. Chunks with scars get no new-chunk grace.
- `Ruins.java`: vanilla structure pieces (village houses, shipwrecks, fossils) stamped 1-2 per rift into the
  strata and cut by tilted noise. They keep vanilla loot. Not in gate rifts.
- `Gates.java`: each static rift leads somewhere by weight (config `riftDestinations`, default End 2 : Nether 1).
  - `scatter` puts the destination's blocks into the shell (config `riftScatter`).
  - Gate rifts replace End portals: `StrongholdPortalRoomMixin` → `GateRoom.shatter`.
  - Gate centres are SavedData `rift_gates`, synced as `GateRifts`, and enter `StaticDeeps.setGates`.
- `GateRoom.java`: the stronghold portal room frozen mid-implosion: block-display shards and hanging debris
  (`fallDebris` ticks). Its chunks are exempt from the new-chunk grace (`NewChunkGrace.exempt`).
- `RuinedCottages.java`: structure type `ruined_cottage` (`worldgen/structure/ruined_cottage.json`,
  structure set ~1/km²). Templates come from `stasisdelvers/structure/ruined_cottage/*.nbt`.
  - The `RuinedCore` processor rewrites tower NBT: dead, `coreDrop` 0, `ruined`.
  - `dormant` returns true when there are no templates.
- `EndSites.java`: End towers and frozen airships replace end cities and ships
  (`src/main/resources/data/minecraft/tags/worldgen/biome/has_structure/end_city.json` is emptied). Without a template, a marker is placed.
- `MapDeskBlock.java`: the surveyor's desk. One map to an unclaimed ruined cottage per player, ever
  (player NBT `stasisdelvers_map_desk`; structure tag `on_cottage_maps`).
- `StructureTemplateMixin`: crops in structure templates get random growth stages (`TimeScars.cropAge`).

## Persistent state
- `FieldData.deep_seed`; SavedData `rift_gates`.
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
- Gates and rooms: `rift_gates`. Ruined cottages: `ruined_cottage_core`, `ruined_cottage_templates`.
- End sites (registration only): `end_sites_replace_cities`.
- Crystals in worldgen: `rift_crystal_grades`.

## Gotchas
- Some config keys look worldgen-only but are also read at runtime. Changing them mid-world desyncs the game
  from rock that is already generated:
  - `strataShapes`: `Pockets.masked`, `Crystals`, `client/StrataMask`
  - `riftDestinations`: `client/StasisPost` (each eye's destination, data slots 200-203)
- `DeepFeature` only touches chunks generated after it existed. Old chunks keep their terrain.
- Worldgen runs on worker threads. Use `peekDeepSeed` and pure functions there, never `FieldData.get`.

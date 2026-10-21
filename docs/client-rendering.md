# Client rendering: post chain, data texture, frozen visuals, tower meshes

Java paths are relative to `src/main/java/org/erisia/stasis/client/`. Asset paths are relative to
`src/main/resources/assets/stasisdelvers/`. Everything here is client-only; common code reaches it only
through hooks set in `StasisClient`.

## Classes
- `StasisClient.java` (`@Mod(dist = CLIENT)`): registers everything below and sets the common-code hooks.
- `StasisPost.java`: the main post effect `stasisdelvers:stasis`.
  - `tick` adds or removes the effect from the player's active post effects (`Stasis.stops(level)`, or `returnsOnly`:
    a running level with a lance's return rifts, spreading mode's Nether and End before they still; AdventurAgent).
    There slot 22 is on, the spawn rectangle (slots 25-28) covers the world so all the land runs, and only the return
    rifts go into the static-deep slots.
  - `frame` (`FrameGraphSetupEvent`) packs the data texture every frame.
- `StrataMask.java`: a 64³-cell strata mask (4-block cells, 256 blocks) built on a background thread when the
  camera enters another 32-block cell. It is a 512x512 texture (`stasisdelvers:stratamask`) with its corner in
  data slots 184-187.
- `FlashlightPost.java`: a second chain (`post_effect/flashlight.json`, `shaders/post/flashlight.fsh`).
  - Up to `MAX` = 5 beams, 9 slots each from 24, in its own data texture.
  - Inserted at index 0, *before* stasis, so the Grey stays grey in the beam.
  - Off when an Iris shaderpack is active.
- `BoltRenderer.java`: tower lightning. The server sends from, to and seed (`StasisNet.Bolt`); every client
  builds the same branched bolt, and the post shader draws it (slots 256-259 + 260 + 5i, at most 150 points).
- `Remnants.java`: frozen collapse remnants (`TowerBlockEntity.Remnant`), drawn as frozen shields with bolts.
- `TowerRenderer.java`: generated tower meshes (see towergen.md).
  - Pipeline `stasisdelvers:pipeline/tower`, shaders `shaders/core/tower.vsh` and `shaders/core/tower.fsh`.
  - Uploaded in `PrepareRenderBuffersEvent`, drawn in `RenderLevelStageEvent.AfterOpaqueBlocks`.
  - LOD beyond 48 blocks.
- `FrozenSprites.java` (+ common `FrozenTextures.java`): a sprite source (`assets/minecraft/atlases/blocks.json`)
  adds the first frame of every animated block texture as `stasisdelvers:frozen/<ns>/<path>`.
  - Chunk meshing swaps in those sprites in stopped land (`SectionCompilerMixin`, `FluidRendererMixin`).
  - `fieldsChanged` re-meshes the sections whose state changed. In spreading mode `spreadMoved` re-meshes (and relights
    flames in) only the loaded chunks whose [`chunkMin1024`, `chunkMax1024`] the new P crossed, or that a newly
    arrived rank tile covers and aren't wholly running; at most once a second (`FLUSH_TICKS`).
  - Meshing and flame light read `Stasis.rateAtLand` (no eddies, no drifting deeps): nothing re-meshes or relights as
    weather drifts by, and it never reads a heightmap off the main thread.
- `WaterLines.java`: streaks on frozen pre-crash water pointing at the nearest rift core (meshing only).
- `FrozenEntities.java`: holds entities still on the client in stopped land (players excepted).
- `AntiSound.java`: lightning silences other sounds, and failing fields low-pass them (via the AT on
  `SoundManager.soundEngine`, `Channel.source`, `SoundEngine.instanceToChannel`). `SoundEngineMixin` adds pitch sag.
- `RiftHum.java`, `LocalClock.java` (+ `LocalClockMark.java`, `ClientClock*Mixin`: the sky shows local time),
  `SuitGauge.java`, `SuitArm.java`, `StatueRenderer.java`, `ConsoleScreen.java`.
- `compat/xaero/` (`StoppedLand`, `MinimapHighlighter`, `WorldMapHighlighter`): the stopped-land overlay on
  Xaero's maps (mixins in `mixin/xaero/`). It reads `rateAtLand`; the world map's region hash changes with the
  fields, with P every 1/2000 of the map, and with the rank tiles.

## Post chain (`post_effect/stasis.json`), in order
1. `stasisdelvers:post/stasis_mist` → target `mist` (800x450): deep mist, low resolution.
2. `stasisdelvers:post/stasis_strata` → target `strata` (800x450): the strata pass, reading `stratamask`.
3. `stasisdelvers:post/stasis` → `swap`: bilateral upsample of mist and strata, desaturation of stopped land,
   freeze warning, shield hexes, eye, storm glow, bolts.
4. `minecraft:post/blit` → `minecraft:main`.
- Inputs: `minecraft:main` colour and depth, `stasisdelvers:fielddata` (1024x4 float, `textures/effect/fielddata.png`
  registered as a `DynamicTexture`), `stasisdelvers:mistnoise` (`textures/effect/mistnoise.png`), and for the full
  pass only `stasisdelvers:spreadranks` (64x64, `SpreadSampler`, see "Spreading mode" below).
- `stasis_mist.fsh` and `stasis_strata.fsh` **don't exist in the source tree**: `processResources` in
  `build.gradle` generates them from `shaders/post/stasis.fsh` by adding `#define MIST_PASS` or `STRATA_PASS`
  after `#version`. One file, three passes (`FULL_PASS` otherwise).

## Data texture slot layout
- 1024x5 float texture, 5120 slots; slot i is pixel (i % 1024, i / 1024). `WIDTH`/`ROWS` in `StasisPost`, and
  the size again in `post_effect/stasis.json`.
- **The authoritative layout is the comment at the top of `shaders/post/stasis.fsh`.** In brief:
  - 0-31: camera (inverse projection·view, floor and fract of the camera), stops-here, freeze warning, field
    count, spawn area, clocks, drifting-deep count
  - 168-219: static deeps' ground range, mist clocks, strata mask, pocket clocks, eyes (`ns` ≤ 4), render distance
  - 196-199: spreading mode: on (1 while `Spread.active`), P, the rank window's first cell x, z
  - 220 + 5i: static deep i; 240 + i: its strength (a lance's breach: 0.25..1, `StaticDeeps.strength`; the shape, mist
    envelope, eye width and strain follow)
  - 244-250: the nearest lance beam (`LanceBlockEntity`): boom tip, eye (each - floor(camera)), on
  - 256-259 + 260 + 5i: bolts
  - 1024 + 12i: drifting deep i (`DEEP_BASE`, `DEEP_STRIDE`, `MAX_DEEPS` = 24)
  - 1312-1319: storm; 1320 + 16j + i: a 16x16 ground grid
  - 1580-1597: eye NDCs, strain, inhale
  - 1600: possessed eye count (≤ 16); 1601 + 3i: eye i - floor(camera) (`PossessedLook.eyes`, up to 8 hosts within 48
    blocks; drawn purple after the desaturation, so they stay purple in the grey: Baughn)
  - 2048 + 64i: drifting-deep ground grids (`GROUND_BASE`); 3584 + 64i: static-deep ground grids
    (`STATIC_GROUND_BASE`)
  - 4096 + 18i (the 5th row): field i (`FIELD_BASE`, `FIELD_STRIDE`, `MAX_FIELDS` = 32): +0..9 centre, radius,
    collapse, failing, column, seed, age, remnant time; +10..13 the shield (amber, dropout, side, remnant eddy clock);
    +14..17 the stretch (e, s, w, n). Slots 32-161 and 1700-1803 (the old 13-field blocks) are free.
- Fields get slots nearest the camera first (`StasisPost.gap`: blocks to the shell), live before frozen remnants;
  fields whose centre chunk the client hasn't loaded are left out (Baughn: their shields drew through terrain that
  wasn't there). Rift querns made 13 too few (2026-10-21).
- Overlapping fields draw only the union's surface: a shell fragment inside another live, uncollapsing field is
  skipped (`insideOther` in `stasis.fsh`).
- `FIELD_BASE`/`FIELD_STRIDE`/`MAX_FIELDS` are duplicated in `stasis.fsh` (`const int`). Change both sides together.
- `DEEP_BASE`/`DEEP_STRIDE`/`MAX_DEEPS`/`GROUND_BASE` are duplicated in `stasis.fsh` (`const int ...`, and
  `STATIC_GROUND = 2048 + 64 * MAX_DEEPS`). Change both sides together.
- `StasisReport` decodes the texture for bug reports (`raw_format`; slots 196-199 as `spread_on_p_window`, and the
  client's spread state under `basics.spread`). Keep it in step too.

## Spreading mode in the shader (docs/spreading-mode.md)
- `StasisPost.spreadFrame` uploads a 64x64-cell rank window (`SPREAD_WINDOW`, 2048 blocks square) from the client's
  `SpreadTiles`, one rank per texel as int bits (`setPixelABGR`, decoded like the data slots; MISSING =
  `Integer.MAX_VALUE` where no tile came). Re-centred when the camera is 12 cells off centre, refilled when the
  tiles change (`SpreadTiles.generation`). Slot 196 is 0 outside spreading mode, off the overworld and once complete.
- `stasis.fsh spreadStilled` is `SpreadMap.stilledSplit`: the same bilinear in exact 32-bit integers (rank × 1024
  overflows an int for maps over ~2M cells). `SpreadTilesTest` checks the Java twin against the exact sum at every
  offset and boundary; the GLSL is the same arithmetic line by line, so per block column the shader's answer is the
  server's. The sky (depth 0) takes the camera's column.
- `run` (colour kept) = max(the fields and spawn area, spread-running land unless a drifting deep stops it).
  `deepStops` mirrors `Deeps.intensity` at the block centre (same ragged-edge noise, `> Deeps.THRESHOLD`); its height
  band uses the deep's ground grid instead of the column's heightmap, so the band's ends can differ by a block or two.
  The deep, static-zone and strain greys multiply by `1 - run` as before, so they show over running land only where it
  is stopped (a drifting deep), matching `rateAt`. The edge is per block, as the server's rule (no soft border).
- The End rift (step 5): no shader change. It is one more static deep (`StaticDeeps.endRift`, radius 40), which the
  client registers itself while in the End with the spread's `hard` flag (`StasisClient`'s tick: `addEndRift` for the
  End's deep seed, cleared on logout); `near` lists it first so the four-deep limit keeps it. It is drawn (mist, eye,
  strain) only once the End stills at the flash, by the usual `Stasis.stops` gate. Its ground grid is the islands' and
  the wall's surface heights, as any static deep's. The island's shards are vanilla block displays (interpolated
  transformations), nothing of ours. Not seen by eye yet (lab).
- Known gaps: a collapsing field on running land holds a deep off in `rateAt` (any field containing the block) but
  only its uncollapsed part does in the shader; a scene point whose deep is past `MAX_HITS` along the ray misses it.

## The rift lance (docs/lance.md)
- A breach's step changes a rift's strength (`GateRifts`): `FrozenSprites.riftStrengths` bumps `Stasis.version` and
  re-meshes the rift's sections (r x 1.4 + 28 round its centre) once per step, at most once a second (`FLUSH_TICKS`).
- The beam: `stasis.fsh` after the storm glow; the closest approach of the view ray (clamped at the scene) and the
  tip-eye segment, a thin core and a halo. `StasisPost` aims the eye's saccades at the tip while the beam is on.
- Not seen by eye yet (lab).

## Invariants
- Reversed-Z depth: the shaders reconstruct world positions accordingly (`stasis.fsh`, `flashlight.fsh`).
- Order: flashlight, then stasis, then the suited hand. `GameRendererMixin` skips the hand pass in
  `render3dHud` and redraws it after `applyPostEffects`, but only once its late hook has proven it runs
  (otherwise the hand would vanish).
- The shader mirrors Java: `Noise` (hash32/vnoise32/fbm32), `Field.collapseScore`, `Pockets` periods, the
  deep shapes. A change on one side needs the other.
- Frozen textures depend on `Stasis.sectionState` and `rateAtLand` from mesh worker threads, which read the
  lock-free `clientIndex`, `StaticDeeps.sectionMayHave` and the client's `SpreadTiles` (a `ConcurrentHashMap`).

## Tests
- `frozen_texture_math` (the `FrozenTextures` pure parts), `tower_shield_shows_drain`, `stretched_field_shape`.
  The rest is checked by eye: AdventurAgent and λ-Nero playtests and screenshots.

## Gotchas
- Sodium-like renderers bypass `SectionCompiler` and `FluidRenderer`, so frozen textures and water lines are lost.
  The cosmetic mixin config warns instead of crashing (mixins.md).
- Post chains are loaded by id. The data texture must exist before the chain is requested (`tick` waits for
  `texture != null`).
- `TowerRenderer` buffers are native (`MemoryUtil.memAlloc`). A superseded build must free them (fixed in 1e07087), as must a
  pending build dropped because the layout went back to the drawn one.

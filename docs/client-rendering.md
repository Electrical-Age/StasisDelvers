# Client rendering: post chain, data texture, frozen visuals, tower meshes

Java paths are relative to `src/main/java/org/erisia/stasis/client/`. Asset paths are relative to
`src/main/resources/assets/stasisdelvers/`. Everything here is client-only; common code reaches it only
through hooks set in `StasisClient`.

## Classes
- `StasisClient.java` (`@Mod(dist = CLIENT)`): registers everything below and sets the common-code hooks.
- `StasisPost.java`: the main post effect `stasisdelvers:stasis`.
  - `tick` adds or removes the effect from the player's active post effects (`Stasis.stops(level)`).
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
  - `fieldsChanged` re-meshes the sections whose state changed.
- `WaterLines.java`: streaks on frozen pre-crash water pointing at the nearest rift core (meshing only).
- `FrozenEntities.java`: holds entities still on the client in stopped land (players excepted).
- `AntiSound.java`: lightning silences other sounds, and failing fields low-pass them (via the AT on
  `SoundManager.soundEngine`, `Channel.source`, `SoundEngine.instanceToChannel`). `SoundEngineMixin` adds pitch sag.
- `RiftHum.java`, `LocalClock.java` (+ `LocalClockMark.java`, `ClientClock*Mixin`: the sky shows local time),
  `SuitGauge.java`, `SuitArm.java`, `StatueRenderer.java`, `ConsoleScreen.java`.
- `compat/xaero/` (`StoppedLand`, `MinimapHighlighter`, `WorldMapHighlighter`): the stopped-land overlay on
  Xaero's maps (mixins in `mixin/xaero/`).

## Post chain (`post_effect/stasis.json`), in order
1. `stasisdelvers:post/stasis_mist` → target `mist` (800x450): deep mist, low resolution.
2. `stasisdelvers:post/stasis_strata` → target `strata` (800x450): the strata pass, reading `stratamask`.
3. `stasisdelvers:post/stasis` → `swap`: bilateral upsample of mist and strata, desaturation of stopped land,
   freeze warning, shield hexes, eye, storm glow, bolts.
4. `minecraft:post/blit` → `minecraft:main`.
- Inputs: `minecraft:main` colour and depth, `stasisdelvers:fielddata` (1024x4 float, `textures/effect/fielddata.png`
  registered as a `DynamicTexture`), `stasisdelvers:mistnoise` (`textures/effect/mistnoise.png`).
- `stasis_mist.fsh` and `stasis_strata.fsh` **don't exist in the source tree**: `processResources` in
  `build.gradle` generates them from `shaders/post/stasis.fsh` by adding `#define MIST_PASS` or `STRATA_PASS`
  after `#version`. One file, three passes (`FULL_PASS` otherwise).

## Data texture slot layout
- 1024x4 float texture, 4096 slots; slot i is pixel (i % 1024, i / 1024). `WIDTH`/`ROWS` in `StasisPost`, and
  the size again in `post_effect/stasis.json`.
- **The authoritative layout is the comment at the top of `shaders/post/stasis.fsh`.** In brief:
  - 0-31: camera (inverse projection·view, floor and fract of the camera), stops-here, freeze warning, field
    count, spawn area, clocks, drifting-deep count
  - 32 + 10i: field i (centre, radius, collapse, failing, column, seed, age, remnant time)
  - 168-219: static deeps' ground range, mist clocks, strata mask, pocket clocks, eyes (`ns` ≤ 4), render distance
  - 220 + 5i: static deep i
  - 256-259 + 260 + 5i: bolts
  - 1024 + 12i: drifting deep i (`DEEP_BASE`, `DEEP_STRIDE`, `MAX_DEEPS` = 24)
  - 1312-1319: storm; 1320 + 16j + i: a 16x16 ground grid
  - 1580-1597: eye NDCs, strain, inhale
  - 1700 + 4i: field shields (`SHIELD_BASE`)
  - 1752 + 4i: field stretch (`STRETCH_BASE`)
  - 2048 + 64i: drifting-deep ground grids (`GROUND_BASE`); 3584 + 64i: static-deep ground grids
    (`STATIC_GROUND_BASE`)
- **MAX_FIELDS = 13 is a bare literal**, in `StasisPost` (2x `if (n == 13) break`) and in `stasis.fsh`
  (2x `i < 13`). The field block 32..161 and the shield and stretch blocks are sized for it. Raising it means
  moving slot blocks.
- `DEEP_BASE`/`DEEP_STRIDE`/`MAX_DEEPS`/`GROUND_BASE` are duplicated in `stasis.fsh` (`const int ...`, and
  `STATIC_GROUND = 2048 + 64 * MAX_DEEPS`). Change both sides together.
- `StasisReport` decodes the texture for bug reports (`raw_format`). Keep it in step too.

## Invariants
- Reversed-Z depth: the shaders reconstruct world positions accordingly (`stasis.fsh`, `flashlight.fsh`).
- Order: flashlight, then stasis, then the suited hand. `GameRendererMixin` skips the hand pass in
  `render3dHud` and redraws it after `applyPostEffects`, but only once its late hook has proven it runs
  (otherwise the hand would vanish).
- The shader mirrors Java: `Noise` (hash32/vnoise32/fbm32), `Field.collapseScore`, `Pockets` periods, the
  deep shapes. A change on one side needs the other.
- Frozen textures depend on `Stasis.sectionState` and `rateAt` from mesh worker threads, which read the
  lock-free `clientIndex` and `StaticDeeps.sectionMayHave`.

## Tests
- `frozen_texture_math` (the `FrozenTextures` pure parts), `tower_shield_shows_drain`, `stretched_field_shape`.
  The rest is checked by eye: AdventurAgent and λ-Nero playtests and screenshots.

## Gotchas
- Sodium-like renderers bypass `SectionCompiler` and `FluidRenderer`, so frozen textures and water lines are lost.
  The cosmetic mixin config warns instead of crashing (mixins.md).
- Post chains are loaded by id. The data texture must exist before the chain is requested (`tick` waits for
  `texture != null`).
- `TowerRenderer` buffers are native (`MemoryUtil.memAlloc`). A superseded build must free them (fixed in 1e07087).

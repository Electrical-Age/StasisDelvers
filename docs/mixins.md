# Mixins

There are 25 mixin classes in three configs, plus an access transformer. Mixin sources are in
`src/main/java/org/erisia/stasis/mixin/`; the configs are in `src/main/resources/`. All three configs are listed
in `src/main/templates/META-INF/neoforge.mods.toml` (`[[mixins]]`), along with the AT (`[[accessTransformers]]`).
The background audit (written before the cleanup, with 36 mixins) is
`/var/lib/mclab/agent/notes/quality/stasis-mixins.md`.

## Policy
- Use a NeoForge event when one exists, an access transformer for plain field or method access, and a mixin
  only when there is no other route.
- No `@Redirect` and no `@Overwrite`. Hooks are `@Inject`, MixinExtras `@WrapOperation` / `@WrapWithCondition`
  (these chain with other mods), or un-annotated merged methods (see the risks below).
- `src/main/resources/META-INF/accesstransformer.cfg` replaced the 8 accessor and invoker mixins and backs
  the event-based replacements. Each line has a comment saying who uses it.
  - Server: `ItemEntity.pickupDelay`, `StructurePiece.getWorldPos`, `Display.setTransformation`,
    `Display$BlockDisplay.get/setBlockState`, `ServerPlayerGameMode.gameTicks`, `LivingEntity.attackStrengthTicker`.
  - Client: `RenderSectionRegion.level`, `SoundManager.soundEngine`, `Channel.source`,
    `SoundEngine.instanceToChannel`, `ChatComponent.allMessages`, `Particle` fields.
- Moved to events in the cleanup:
  - the server mining clock (`PlayerRates.miningClock`)
  - the attack cooldown (`PlayerRates.attackClock`)
  - spawn-protection block use (`SpawnUse.useOn`, `UseItemOnBlockEvent` at `HIGHEST`: the mixin ran before
    every listener, so the event must too)

## Required config: `stasisdelvers.mixins.json` (gameplay; `required: true`, `defaultRequire: 1`)
If one of these can't apply, the game stops at startup, on purpose: without them time doesn't stop correctly.

| Mixin | Target | What | Why no event |
|---|---|---|---|
| `ServerLevelMixin` | `ServerLevel.isPositionTickingWithEntitiesLoaded` (RETURN), `tickBlock`/`tickFluid` (HEAD cancel), `tickChunk` (HEAD cancel + 2 `@WrapWithCondition` on `randomTick`), merged `createTick` x2 | Scheduled ticks wait or are rate-delayed (`Stasis.dueTick`); no random ticks, snow or ice in stopped land | No scheduled-tick or random-tick event exists |
| `LevelChunkMixin` | `LevelChunk.isTicking` (RETURN) | Block entities tick only on running ticks; `Stasis.exemptBlockEntity` is exempt | No block-entity-tick event |
| `LiquidBlockMixin` | `LiquidBlock.getCollisionShape` (RETURN) | Stopped water and lava are solid (both sides) | No collision hook for vanilla blocks |
| `BlockMixin` | `Block.stepOn` (HEAD) + merged `getLightEmission(state, getter, pos)` / `hasDynamicLightEmission` | Stopped lava burns like magma; flame light by position (`Flames`) | The light API has no hook for vanilla blocks |
| `BlockStateBaseMixin` | `BlockStateBase.isRandomlyTicking`, `randomTick` (HEAD) | Flames get random ticks so they can gutter | None |
| `LivingEntityMixin` | `LivingEntity.tick` (`updatingUsingItem`), `hurtServer` (`playHurtSound`), both `@WrapWithCondition` | Item use counts only on the player's running ticks; frozen mobs make no hurt sound | `LivingEntityUseItemEvent.Tick` would still fire `onUseTick` effects; no entity-specific sound event |
| `StrongholdPortalRoomMixin` | `StrongholdPieces$PortalRoom.postProcess` (TAIL) | The End portal becomes a gate rift in a shattered room (`GateRoom`, `Gates`) | Strongholds are code-built pieces; there is no data route |
| `StructureTemplateMixin` | `StructureTemplate.processBlockInfos` (RETURN) | Random crop ages in structures (`TimeScars.cropAge`) | A processor_list route covers only listed pools and conflicts with datapacks |
| `SpawnUseMixin` | `ServerGamePacketListenerImpl.handleUseItemOn` (`isUnderSpawnProtection`, `mayInteract`; `@WrapOperation`) | Lets EA and Stasis blocks be used under spawn protection | No spawn-protection event |
| `BucketItemMixin` | `BucketItem.emptyContents` (`Level.setBlock`) | Water poured in stopped land becomes stilled water | Events would mean reimplementing bucket placement |
| `EntityPushMixin` | `Entity.push(DDD)` (HEAD cancel) | A push moves a frozen entity one tick's worth, with no momentum | `LivingKnockBackEvent` covers knockback only |
| `PathfindingContextMixin` | `PathfindingContext.<init>` (RETURN), implements `StasisPathContext` | Remembers the mob's level for the next mixin | None |
| `WalkNodeEvaluatorMixin` | `WalkNodeEvaluator.getPathTypeStatic` (RETURN) | Stopped land counts as dangerous to path into | `getBlockPathType` applies only to our own blocks |

## Cosmetic config: `stasisdelvers.cosmetic.mixins.json` (client, `required: false`, `defaultRequire: 0`)
These only change how things look or sound. If another mod (Sodium-like renderers, shader, weather or sound
mods) has rewritten a target, only that effect is lost.

| Mixin | Target | Effect lost without it |
|---|---|---|
| `GameRendererMixin` | `render3dHud` (HEAD), `renderItemInHand` (HEAD cancel), `render` after `applyPostEffects` | The suited hand drawn in colour after the post chain |
| `ClientLevelMixin` | `doAnimateTick` (3 calls), `tickWeatherEffects` (particles, rain sounds) | No ambience, drips or rain sound in stopped land; muffled rain under a shield |
| `ClientClockManagerMixin` | `ClientClockManager.getInstance` | Marks the overworld clock (`LocalClockMark`) |
| `ClientClockInstanceMixin` | `ClientClockInstance.totalTicks`, `partialTick` | The local sky time (frozen, or the field's clock) |
| `ParticleGroupMixin` | `ParticleGroup.tickParticle` (`Particle.tick`) | Particles hold still, but keep ageing |
| `SectionCompilerMixin` | `SectionCompiler.compile` (`tesselateBlock`) | Still block textures (`FrozenSprites`) |
| `FluidRendererMixin` | `FluidRenderer.tesselate` (HEAD + `sprite()` wraps) | Still fluids and water lines |
| `SoundEngineMixin` | `SoundEngine.calculatePitch` (RETURN) | Pitch sag in a failing field |
| `WeatherEffectRendererMixin` | `extractRenderState` (rain and snow column instances) | Hanging rain and snow; shields keep weather out |
| `EntityRendererAgeMixin` | `EntityRenderer.extractRenderState` (TAIL) | Frozen idle animations. Not `RegisterRenderStateModifiersEvent`: it runs after subclasses derive state from `ageInTicks` (`PhantomRenderer` flapTime) |

- `mixin/client/CosmeticPlugin.java` (`IMixinConfigPlugin`) runs before any game class loads (ASM, Mixin and
  SLF4J only).
  - `shouldApplyMixin` always applies (fail open: the config is `required: false`, so Mixin skips a missing
    target itself). It warns when the target class is missing: a resource lookup, and on a miss Mixin's
    bytecode provider to confirm it.
  - `postApply` checks that each injector found its spot. For Mixin's own injectors, the target must call the
    handler. For MixinExtras wrappers, which are woven later, the wrapped INVOKE must still be in the method.
  - It logs one warning per mixin, naming the lost effect (`EFFECT` map).
- `GameRendererMixin` skips the hand only after its late hook has proven it runs (`stasis$lateHookLive`), so
  a half-applied mixin never makes the hand vanish.

## Xaero config: `stasisdelvers.xaero.mixins.json` (client, optional)
- `xaero/MinimapSessionMixin`, `xaero/WorldMapSessionMixin`: `@WrapOperation` on `HighlighterRegistry.end()`
  (`remap = false`, `require = 0`). They register `compat/xaero` highlighters.
- `xaero/XaeroPlugin.java` applies them only when Xaero's classes are present. Xaero is `compileOnly` from the
  Modrinth maven.

## Risks to keep in mind
- **Merged methods** (no annotation, so they behave like an overwrite of an interface default):
  `BlockMixin.getLightEmission`/`hasDynamicLightEmission` (every block that doesn't override it) and
  `ServerLevelMixin.createTick` x2. If another mod merges the same method, one wins silently.
- Hot paths: `LevelChunk.isTicking`, `getPathTypeStatic`, `getCollisionShape`, `isRandomlyTicking`,
  `getLightEmission`, `doAnimateTick`. Keep the handlers cheap: the column index, no allocation.
- `FluidRendererMixin` uses `allow = 5` on `sprite()`. A vanilla change that adds a call fails loudly; in the
  cosmetic config that means a warning.
- Modded light sources that override `getLightEmission` themselves skip the "no flame light in stopped land" rule.
- `compatibilityLevel` is `JAVA_25` in all three configs.

## Tests
- The behaviour behind the required mixins is covered by GameTests (time-stop.md, players-suits.md):
  `rate_scheduled_button`, `rate_random_ticks`, `furnace_burns_only_inside`, `stopped_fluids_are_solid`,
  `flames_*`, `poured_water_freezes`, `furnace_cart_pushes_frozen_cart`, `mobs_avoid_stopped_land`,
  `rate_player_eats_slowly`, `rift_gates`.
- Cosmetic mixins have no automated test; a missing hook shows as a startup warning.

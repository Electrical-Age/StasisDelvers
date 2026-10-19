# Spawn cottage

Paths are relative to `src/main/java/org/erisia/stasis/`; data paths to `src/main/resources/data/stasisdelvers/`.

## What it is
- When a world is new, Baughn's spawn build is placed near world spawn on flat, dry land: a cottage with a
  running stasis tower, EA wiring and a surveyor's desk. Its sides are blended into the terrain like a low hill.
- The player respawns there. The tower keeps it in a field, so new players don't start in stopped land.
  The fallback spawn area (`spawnArea`) is off by default.

## Classes and files
- `SpawnCottage.java` (not in spreading mode, see spreading-mode.md):
  - `serverStarted(ServerStartedEvent)`: overworld only. It returns if `FieldData.cottageDone()` or if the
    template is missing. It **marks the cottage before placing**, then calls `place`.
  - `place(level, template, around, eln)`:
    1. `findSite`: rings out to 160 blocks in steps of 8, good biomes first (`goodBiome`: plains, sunflower
       plains, meadow, `#is_forest`, `#is_taiga`), then any biome. The site must be dry, and its surface over
       the footprint and blend ring may vary by at most 3 blocks. Each pass has an 8 s wall-clock cap (the 60 s watchdog).
    2. Generates every chunk around the site plus a margin (`BLEND` + 32). Unloaded neighbours read as
       the bottom of the world.
    3. `placeInWorld`; clears terrain above the footprint and fills below it (dirt, then stone).
    4. `blend`: a 16-block ring, eased and smoothed, measured from the ground under trees.
    5. `placeNodes`: recreates the EA nodes from the eln data (`move` rebases positions).
    6. The spawn point: from the eln data's `spawn`, or a `DATA` structure block with metadata `spawn`.
    7. Every tower in the template is `locked`; the master gets `fillStore()`, so it starts on its first tick.
    8. `setRespawnData`.
  - `TEMPLATE` = `stasisdelvers:spawn_cottage` (`structure/spawn_cottage.nbt`, its ground included).
  - `ELN` = `spawn/spawn_cottage_eln.nbt`: EA nodes and ghosts, `spawn`, `ground`. Positions are relative to
    the template. EA keeps node data outside the chunks, so a template alone gives dead blocks.
- `MapDeskBlock.java`: the surveyor's desk inside the cottage, not craftable (deeps-strata-worldgen.md).
- `SpawnUse.java` + `SpawnUseMixin`: under vanilla spawn protection, EA and Stasis blocks (and
  `#stasisdelvers:usable_at_spawn`) can be used as if with an empty hand. Placing, breaking and item use stay
  blocked. `SpawnUse.useOn` handles NeoForge's `UseItemOnBlockEvent` at `EventPriority.HIGHEST`, so other mods'
  listeners (which skip cancelled events) can't run the held item's use first, as with the mixin it replaced.
- `StasisCommands`: `/stasis savetemplate <from> <to> <name>` writes a template from the world.

## Tools (outside the Java build)
- `tools/spawn/extract_eln.py`: pulls the EA nodes inside a box from a world's `electricalAgeWorld0.dat`
  into `spawn_cottage_eln.nbt`.
- `tools/spawn/clean_template.py`, `tools/spawn/analyse_template.py`,
  `tools/spawn/region_diff.py` (a placed site, or a world box, compared with the template).
- These scripts import `snbt` from `/var/lib/mclab/agent/tools` (`sys.path.insert`), which is outside the repo.

## Persistent state
- `FieldData.cottage` (the overworld's `stasisdelvers:fields`): placed, or attempted.
- After placement, everything is ordinary world state: blocks, EA nodes, the tower's block-entity NBT
  (`locked`), the level's respawn data.

## Invariants and tests
- At most one attempt per world. The flag is set before placement, so a failed or partial placement is
  **never retried**.
- The spawn tower runs from the first tick and its settings can't be changed (`locked`); feeding it is allowed.
- GameTest `spawn_cottage_places_a_running_tower` places the shipped template with its EA data and checks
  that the tower runs.

## Gotchas
- `findSite` is not deterministic under load: the 8 s cap depends on wall-clock time.
- Without `spawn_cottage.nbt` nothing happens and no flag is set. Adding the template later places it at the
  next server start.
- Placement runs inside `ServerStartedEvent`, on the server thread, and can take seconds (chunk generation).
- Template and eln data must be updated together: node positions are relative to the template's origin.

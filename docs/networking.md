# Networking (StasisNet)

All payloads are in `src/main/java/org/erisia/stasis/StasisNet.java`: records with a `TYPE`
(`stasisdelvers:<name>`) and a `StreamCodec`. They are registered in `StasisNet.register`
(`RegisterPayloadHandlersEvent`).

## Protocol version
- `e.registrar("16")` (16: `field_speeds`; 15: `gate_rifts` carries the lanced rifts' breach steps; 14: the lance's return rifts). Bump it whenever a payload is added, removed or changes its codec. Mismatched peers
  are then refused at login instead of failing mid-game.
  - 9 added `dig_stilled`.
  - 10 removed the unused `chunk_clocks`.
  - 11 added `spread_state`, `spread_tile`, `spread_forget` (spreading mode's client sync).
  - 12: `spread_state` carries the End's stillness (flag 16) and the storms' origin (a double, NaN before hard mode).
- The report channels sit under `r.optional()`, so a peer without them still connects; the bug report then
  says `no_channel`.
- `send(player, payload)` checks `connection.hasChannel(payload)`. Vanilla clients and GameTest mock players
  (EmbeddedChannel) simply get nothing.

## Server → client
| Payload | Id | Sent by | Client handler |
|---|---|---|---|
| `Fields(fields, deepSeed)` | `fields` | `sendFields(level)` from `FieldData.changed`; `sendFields(player)` on login, respawn and dimension change (`Freezing.grace`) | `ClientFields.deepSeed` + `ClientFields.set` |
| `GateRifts(seed, centres, returns, steps)` | `gate_rifts` | `sendGates` (with every per-player `sendFields`), `Gates` when a gate is added, `Lance.publishAndSend` when a return rift is made, wakes or sleeps, or a breach step changes (`steps`: `id << 4 \| step` for gates past step 0) | `StaticDeeps.setGates`, `setReturns`, `setStrengths`; `riftStrengths` (client: `FrozenSprites` re-meshes the changed rifts) |
| `StormOverride(active, start, end, folded)` | `storm_override` | `sendStorm` (`Storms.start/stop`), `sendGates` | `Storms.clientOverride` |
| `Freeze(progress)` | `freeze` | `Freezing.tick` on a change ≥ 0.01 | `StasisNet.clientFreeze` (shader slot 23) |
| `FieldClocks(map id → double[])` | `field_clocks` | `LocalTime.tick` every 40 ticks | `LocalTime.Client.fieldClocks` |
| `FieldSpeeds(map id → double)` | `field_speeds` | `Speeds.recount` each second, while a level has acceleration fields held below their rate (once more, empty, after) | `Speeds.clientAchieved` |
| `Gutters(map pos → light)` | `gutters` | `Flames` (on watch and on change) | `Flames.clientGutters` |
| `Bolt(from, to, seed)` | `bolt` | `TowerBlockEntity.bolt/boltOut/freeze` | `boltSink` → `client/BoltRenderer` + `client/AntiSound` |
| `SpreadState(reset, map, count, p, hard, complete, endStill, endEvent, stormOrigin)` | `spread_state` | `SpreadSync.tick`: to every player when the state changes (P at most once a second, `STATE_EVERY`; completion, hard mode, the End stilling, the End event, the storms' origin and P going back at once), and with `reset` on login, respawn, dimension change and a new map | `Spread.clientState` (resets drop the tiles; a P change tells `FrozenSprites` what to re-mesh; `stormOrigin` feeds `Storms.clientOrigin`; `endEvent` drives `client/EndEventFog`) |
| `SpreadTile(tx, tz, ranks)` | `spread_tile` | `SpreadSync.tiles`: the overworld player's tiles out to `range` (view distance + 1 chunk + 32 blocks), nearest first, 4 a tick | `Spread.clientTile` → `Spread.clientTiles` |
| `SpreadForget(keys)` | `spread_forget` | `SpreadSync.tiles`: tiles more than one tile beyond the needed ones | `Spread.clientForget` |
| `ConsoleState(pos, v, text)` | `console_state` | the console handlers below | `consoleSink` → `client/ConsoleScreen` |
| `ReportState(nonce, json)` | `report_state` (optional) | the `ReportQuery` handler | `reportSink` → `client/report/BugReport.reply` |

## Client → server
| Payload | Id | Handler | Guard |
|---|---|---|---|
| `DigStilled()` | `dig_stilled` | `StilledLiquids.dig` | the server raycasts from the player's own view and needs a digging tool in hand; the client repeats every 5 ticks while the button is held |
| `ConsoleQuery(pos)` | `console_query` | replies `consoleState` | `consoleTower`: within 8 blocks, master, inside the world border |
| `ConsoleFeed(pos)` | `console_feed` | `TowerBlockEntity.toggleFeed` | `consoleTower` |
| `ConsoleSet(pos, target, override)` | `console_set` | `TowerBlockEntity.setControls` (ignored when `locked`); logs OVERRIDE changes | `consoleTower` |
| `ReportQuery(nonce, look?, face)` | `report_query` (optional) | `ReportResponder.answer` | 1 per 10 s per player (`MIN_INTERVAL_MS`); look honoured within 8 blocks |

## Other synced state (not StasisNet)
- Tower block entities: the update tag is the full NBT (layout JSON, status, remnant), sent with
  `sendBlockUpdated`.
- The attachment `stasisdelvers:frozen` (`StasisRegistry.FROZEN`) is synced through NeoForge's attachment sync.
- Player tick rate: vanilla's ticking-state packet (`PlayerRates`).
- Config: `StasisConfig` is a SYNCED config.

## Size limits
- `ReportState.REPORT_MAX` = 64 KiB (`stringUtf8(REPORT_MAX)`); encoding more throws.
  `ReportResponder` drops sections until its JSON fits under `REPORT_MAX - 256`.
- `ConsoleState.text` uses the default string limit.
- `Fields` sends every field of the level each time. That is fine for tens of fields; the shader only draws
  13 (client-rendering.md).
- `SpreadTile` is always 1024 ranks (`SpreadTiles.TILE_CELLS`; encoding another size throws). Each is `rank + 1` as a
  VarInt (1-3 bytes for maps up to 30 km: ~3 KB a tile), or one value for a uniform tile (all -1 beyond the map's
  radius: a few bytes). A login sends 1-9 tiles depending on the view distance.

## Tests
- `src/test/java/org/erisia/stasis/NetCodecTest.java`: a round trip for every payload codec
  (`fieldsRoundTrip`, `gateRiftsRoundTrip`, `stormOverrideRoundTrips`, `consolePayloadsRoundTrip`,
  `digStilledRoundTrips`, `reportPayloadsRoundTrip`, `reportStateOverTheCapThrows`, `freezeRoundTrips`,
  `clockMapsRoundTrip`, `boltRoundTrips`, `spreadStateRoundTrips`, `spreadTileRoundTrips`, `spreadTileIsSmall`,
  `spreadTileRefusesAWrongSize`, `spreadForgetRoundTrips`).
  - `SpreadTilesTest.clientLookupIsTheServers`: tiles cut from a map, through the codec, answer like the map.
- GameTest `spread_tiles_follow_player`: the server's list of a (mock) player's tiles follows the player.
  - `sentFieldsAgreeOnBlocks`: a field after the wire codec gives the same `containsBlock` answers.
- GameTest `tower_console_access` calls `StasisNet.consoleTower` directly. The serverbound handlers
  themselves have no test.

## Gotchas
- The server's per-player tile list (`SpreadSync.Tracked.sent`) must stay the client's: tiles are only dropped by
  `SpreadForget` (never by the client on its own), and every reset (login, respawn, dimension change, a new map)
  clears both. A mock player's list fills though it gets nothing; that's what the GameTest reads.
- `FIELD` (the wire codec for `Field`) is separate from `Field.CODEC` (the save codec). A new `Field`
  component needs both, plus a protocol bump. `NetCodecTest` catches a missing wire field only if its
  generator varies it.
- `ReportResponder.LAST` (rate limit per UUID) is never pruned: one entry per player who ever reported.

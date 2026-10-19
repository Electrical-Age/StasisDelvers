# The rift lance

Design: `notes/rifts/LANCE-v2.md` (with Argon's review and Baughn's decisions of 2026-10-19, which override the earlier
text). Baughn: "The lance device will be able to open a portal to the End from any rift." Build order (Argon): travel
first. **L1 (built)**: gate state, travel, arrival pads, return rifts, the hard-mode guards, driven by a test command.
**L2 (built)**: the multiblock (core, mast, boom), EA power, the crystal focus, the breach stepping the radius down, the
latch, hold and relax, the effective radius everywhere (server and client), the beam, chunk tickets, the lance's base,
the held-off field, dormant return rifts. **Next (L3/L4)**: strata venting, the eye's twitching while stalled, the home
lance's tiny field at the pad.

Code: `Lance.java` (gate state, travel, the breach's rules and state machine, binding, orphans, dormancy),
`LanceBlock` / `LanceBlockEntity` (the core: survey, power, driving its gate, tickets, the held field, the client's
beam), `LanceTickets`, `RiftFocusItem`; hooks in `Freezing`, `HardMode`, `EndBarrier`, `StaticDeeps`, `Gates`,
`StasisNet`, `Stasis` (exempt, the yard), `client/StasisPost` + `stasis.fsh` (strength, beam), `client/FrozenSprites`.

## The multiblock (L2)
Kept simple, all in the tower's materials:
```
  mast top  boom (survey poles along the boom)    tip = the last pole: within 1.5 r of the eye
     M ====================================== T   horizontally, and above it
     M                                        :
     M   mast: >= 3 vertical survey poles     :
     L   the lance core, at the rift's rim     eye (the rift's warped core, StaticDeeps.core)
```
- `LanceBlockEntity.scan`: on the core, a mast of `MIN_MAST` (3) to 64 vertical `stasis_tower_segment` poles; off the
  mast's top pole, one boom of 1-64 horizontal poles in a straight line (two booms: invalid). Poles extend with
  `PoleItem` as on a tower; `TowerStructure.boomRefusal` has no rule for a mast with no tower core under it.
- Binding (`Lance.riftUnder`, `Lance.bind`, every second): the nearest lanceable rift whose eye is within 1.5 r of the
  tip horizontally, the tip above the eye. One lance per rift: the gate records the core (`Gate.lance`); a second lance
  is refused ("This rift already has a lance") while the first stands (one in an unloaded chunk counts as standing). A
  changed boom or a broken core lets go (`Lance.unbind`: no lance, `dismantled` = now).
- The core (`lance_core`): recipe `PSP/BMB/PCP` (iron plates, a rift shard, EA cost-oriented batteries, EA machine block,
  EA copper coil). It drops itself and its focus.
- The focus (`RiftFocusItem`, not consumed): use it on the core; sneak-use with an empty hand takes it out; an empty hand
  shows the state. `rift_focus` (tier 1; `DSD/SGS/DSD`: rift dust, rift shards, glass) breaches rifts whose gate leads to
  the Nether; `charged_rift_focus` (tier 2; `ARA/RFR/ARA`: riftstone, charged shards round a rift focus) the End too
  (`RiftFocusItem.needs(Lance.kind)`, so before hard mode in spreading mode a tier 1 does any rift: Argon).

## Power and numbers (L2; all `StasisConfig`)
- FE from EA's exporter into the core's store (any face), as a tower's. The core is exempt from stopping
  (`Stasis.exemptBlockEntity`) and has the tower's 6-block yard (`Stasis.nearTower`), so `NodeFreezer` keeps its cables,
  batteries and exporter running in stopped land. Its power plant still needs a field (or the spread's running land).
- Hold: `lanceHoldW` 10 kW (Baughn: "to break even"), any rift.
- Breach: the hold draw x `lanceBreachMinFactor` 3 at r 9 (stronghold gates), rising linearly to
  `lanceBreachMaxFactor` 5 at r 60: 30 kW (r 9), 36 kW (r 24), 50 kW (r 60). A storm over the rift (`Storms.at` at the
  boom's tip, or on the ground over the eye): x `lanceStormDrawFactor` 2 and no progress.
- Breach time: `lanceBreachSeconds` 120 x sqrt(r / 9): 2 min (r 9), 3.3 min (r 24), 5.2 min (r 60); 3.6 MJ (r 9) to
  15.5 MJ (r 60). Relax: `lanceRelaxSeconds` 240 x sqrt(r / 9). Grace: `lanceGraceSeconds` 30.
- Store: `lanceStoreSeconds` 30 s of the bound rift's breach draw (900 kJ at r 9, 1.5 MJ at r 60). Once it can't meet a
  tick's draw it browns out until full again, so a supply short of the draw can't hold a gate (it loses a grace in the end).

## Breach, latch, hold, relax (L2; `Lance.advance` is pure, `Lance.drive` saves and publishes)
- Gate state (in `Gate`, saved): `step` 0..8, `progress` 0..1 within the step, `grace` ticks, `lance`, `dismantled`.
  The rift's strength is `Lance.strength(step)` = 1 - 0.75 step / 8: quantised, so zones re-mesh once per step.
- Each tick of the core: `want` = bound, with a fitting focus; `powered` = the draw met. Powered: the grace resets; not
  yet latched, progress grows (not in a storm), a step every breach / 8; at step 8 the gate latches OPEN (L1's state:
  travel works). Latched: the hold draw.
- Not powered (or no focus, or unbound) past step 0: the grace counts (a klaxon every 2 s and an action-bar warning
  within 48 blocks), then the gate unlatches and progress runs down, a step back every relax / 8: never a snap. Power back
  mid-relax breaches on from there. Sounds at the eye: each step, the latch, the unlatch.
- Orphans (`Lance.tick`): a gate whose lance was dismantled (or hasn't driven it for 10 s) is driven as unpowered, so it
  relaxes by itself. Gates that never had a lance (the test command's) stay as set.
- The test command still opens (step 8, latched) and closes (step 0) at once; a bound lance then drives the gate.

## The effective radius everywhere (L2)
- `StaticDeeps.zone(seed, ...)` divides the shape by the rift's strength (`StaticDeeps.setStrengths`, per deep seed): the
  warped core stays put, the rift and its outer zone shrink round it. So `inRift`, `Stasis.rateAt`, `Freezing` (the
  statue, the drain, the eye's touch: `eyeHalfWidth` of the effective radius), towers' `staticOverlap`, `SpawnMove` and
  `Flames` follow. Worldgen's `shape` is untouched, and so are the strata (rock, pockets): venting is L4.
- At a quarter a r 9 gate rift's black is under a block across.
- Sync: `GateRifts` carries `steps` (`id << 4 | step`; protocol 15). Clients set the same strengths; `StasisPost` puts
  them in slots 240 + i; the shader divides `staticShape` by them (black body, fringe, grey) and scales the mist's
  envelope, the eye's half-width and the strain. `FrozenSprites.riftStrengths` re-meshes the rift's sections per step.

## The beam and the eye (L2; client)
- The core syncs its tip, the eye and `beam` (on while powered with a fitting focus). `StasisPost` sends the nearest
  beam within 192 blocks (slots 244-250); `stasis.fsh` draws a violet line: a thin core in a halo, flickering, hidden
  behind the scene. A shader, not particles (particles may be held still in stopped land). While the beam is on the
  eye's saccades turn to the tip.

## Chunk tickets, the base, the held field, dormancy (L2)
- `LanceTickets` (controller `stasisdelvers:lance`, the towers' pattern, without natural spawning): while active
  (powered, or the rift not whole) the core's chunk and the rift core's chunk. Released at rest and when the core goes.
- `Lance.basePosition`: beside the lance's core (the side away from the eye first, its own level first), Baughn's "the
  base of the tower"; the rift's rim only with no lance standing.
- Spreading mode, while active and the spread decides the columns there: a field of r 4 (`HELD_RADIUS`, at
  `towerFieldRate`) round the core (Argon), so a drifting deep over running land doesn't stop its EA nodes. It is a real
  field: once the land stills it runs time there too.
- Return rifts (`Arrival.dormant`, saved): always usable while the home lance stands, powered or not. Once it is
  dismantled, after 15 min and with nobody within 256 blocks, the return rift goes dormant (unpublished: not drawn, not a
  door; `Lance.dormancy`, every 5 s). A lance binding the rift again, or a trip through its gate, wakes it. Return rifts
  on the End rift's islands (shared by every lance) never go dormant.

## Gate state
- SavedData `stasisdelvers:lance_gates` (`Lance.GateData`) in the rift's level, keyed by the deep's id: the rift's
  geometry (so it can be found from anywhere), `open` (CLOSED / OPEN: the latched gate disc) and L2's breach state
  (above). An L1 save's open gate (no `step`) loads at the floor.
- An open gate holds its rift at `OPEN_SCALE` (25 %) of its radius: `Lance.effectiveRadius`; zones and the client
  follow (above).
- So that an open gate's core can be entered, Freezing spares (`Lance.spares`: the outer zone's rules instead of the
  rift's statue and the eye's touch) a player in the disc (within ~2.5 blocks of the core). A suit is still needed there
  (the zone is stopped land).
- Not lanceable: the End rift and return rifts (`Lance.lanceable`).

## Travel
- `Lance.step(p)`, the first thing in `Freezing.tick` (so a trip wins over the statue in the same tick, and GameTests'
  mock players, driven through `Freezing.tick`, travel too). A player whose centre is within `REACH` (1.5 blocks)
  horizontally of an open gate's core, the core between their feet and head, goes through. Touching the rift's edge
  does nothing. Players only (alive, not removed, not spectators); per-player cooldown `COOLDOWN` (5 s; memory only).
- `Lance.send`: stops riding, teleports (marked `TRAVELLING` for `HardMode.travel`), zeroes motion and fall distance,
  gives the grace of a new place (`Freezing.giveGrace`; a dimension change gives it too), a quiet teleport sound.

## Destinations (`Lance.kind`, pure; `LanceTest`)
| Mode | Rift | Kind | Where |
|---|---|---|---|
| stopped | Gates destination End | `OUTER_END` | >= 1100-1600 out at a hashed angle, beside the nearest `end_tower_sites` placement chunk (24 blocks towards the island), on ground within 32 blocks, else a floating pad at y 64 |
| stopped | stronghold gate rift | `STRONGHOLD_END` | 150-200 blocks from (0, 0), in view of the main island; ground if any, else a floating pad at y 64 |
| any | Gates destination Nether (or another non-End dimension in `riftDestinations`) | `NETHER` | the rift's x/8, z/8; ground within 32 blocks: sturdy, not lava or magma, 3 air, above the lava sea, y <= 112; else a pad carved at y 70 |
| spreading, before hard mode | any | `NETHER` | (Baughn's decision 5) |
| spreading, after hard mode | stronghold gate rift | `CENTRAL_END` | one of the End rift's floating islands (`EndRiftPlan.islands`, a roomy one, hashed per rift), on its highest planned block under the pad's footprint (so `EndRift.place`, which fills air, never buries it), stamped even if `EndRift` hasn't built it yet |
| spreading, after hard mode | End-bound / Nether-bound wild rift | `OUTER_END` / `NETHER` | as in stopped mode (decision 6) |

## Arrival pads and return rifts
- SavedData `stasisdelvers:lance_arrivals` (`Lance.ArrivalData`) in the destination level. Key: `rift:<dim>:<id>`
  per rift, `island:<index>` per End rift island (several gates may share one; its home is the first). Made once; later
  trips only clear the 1x2x1 foot pocket.
- The pad: 3 wide from one block behind the arrival to one past the return rift, riftstone with obsidian corners (all
  riftstone on the End rift's islands: `EndRift`'s lifts take obsidian in the core part and end stone everywhere, never
  riftstone), the space above cleared 3 high.
- The return rift: a static deep of radius 3 (`StaticDeeps.returnRift`, `isReturn`, `isGate` too), its centre
  `RETURN_ALONG` (5) blocks along the pad and one up (body height). Registered per deep seed (`setReturns`, from
  `StasisEvents.levelLoad` and when one is made) and synced in `GateRifts` (protocol 14; 15 adds the steps), so clients draw it.
  `Gates.destination` says "minecraft:overworld" for it (the shader's eye code 2).
- Stepping into a return rift's core (the same reach rule) goes home (`Lance.goHome`, `Lance.choose`, Baughn's
  decision 3):
  - on the End rift's islands (`central`): the gate the player last came out of (persisted NBT
    `stasisdelvers_lance_from`) if it is open; else a random open gate leading to this dimension; else the player's own
    gate regardless (the island's first home if the player has none on record);
  - elsewhere: the return rift's own home gate, in the same order.
  - "A random open one" is read as a random open gate leading to the return rift's dimension (the End or the Nether);
    Baughn's decision 3 says only "a random powered one". Our reading, to confirm.
  - If no gate is known at all: the respawn point (`Freezing.safeRespawn`).
- Home is `Lance.basePosition`: beside the lance's core (L2); with no lance standing, the rift's rim (`Lance.rim`): rings
  every 2 blocks from just outside the rift zone to r + 12, 16 directions, the standable spot outside the rift nearest
  the core's height; else the surface beside the rift.

## Hard mode (spreading mode; Argon's review)
- `HardMode.travel` lets a lance trip into the closed End (`Lance.travelling`); everything else stays out.
- `HardMode.login` (`evictsOnLogin`) leaves a player who came into the End by lance (persisted NBT
  `stasisdelvers_lance_end`, set on arrival in the End, cleared on leaving it any way).
- `EndBarrier` is off after hard mode on both sides (Baughn's decision 7), on before it.

## Not yet (L3/L4)
- The home lance's tiny field at the pad (LANCE-v2 point 7); strata venting (point 8: the strata and their pockets ignore
  the breach); the eye twitching between the tip and players while the breach stalls; the latched gate's own look (it is
  the rift's shrunken black and eye, no flat violet-black disc yet); a model for the core (a plain cube).
- Only the nearest lance beam is drawn. Flame light isn't relit when a step changes a rift's zone over running land.
- A first trip to a new destination generates the chunks it searches (up to ~25 in the Nether or the outer End) on the
  server thread: a stall of a second or two, once per destination.

## Test command (op)
- `/stasis lance open [rift]`, `/stasis lance close [rift]`: the nearest rift (its core, within ~512 blocks) or one by
  id (`/stasis rifts` doesn't show ids; `list` does). Says where it leads.
- `/stasis lance supply <watts>`: test power (W) into the nearest lance core within 32 blocks, no EA grid needed (lab checks); a negative value clears it.
- `/stasis lance list`: every gate in every level (state, step, core, radius, where it leads, its lance) and every
  arrival (dormant ones marked).

## Lab procedure
1. Stopped mode: `/stasis rifts` to find a rift (or a stronghold's gate rift); stand near it, `/stasis lance open`.
2. Walk (suited) into the eye's centre: you land on a pad in the End or the Nether, with the grace countdown. The return
   rift hangs 5 blocks along the pad. Check by eye that its eye renders (the client got `GateRifts` on the dimension change). Step into it: back at the home rift's rim.
3. `/stasis lance close`, step into the eye: nothing (the rift's statue, as before).
4. Spreading mode: before `/stasis spread hardmode` a gate goes to the Nether; after it a stronghold gate rift goes to an
   island round the End rift, and the End's wall is gone.
5. L2 (stopped mode; a stronghold gate rift is the cheapest): a lance core a few blocks outside the rift, 3+ survey
   poles on it, a boom off the mast's top out over the eye. Right-click the core: "rift N ... no focus". Put in a charged
   rift focus (an End gate). Power it: an EA exporter on the core, >= 30 kW (r 9). By eye: the violet beam into the eye,
   the eye looking at the tip, the black body and the mist shrinking a step at a time over ~2 min (no flicker between
   steps, sections re-meshed once per step), the latch sound; then walk into the eye. Cut the power: the klaxon for 30 s,
   then the rift grows back a step at a time over ~4 min. Walk 300+ blocks away: `/stasis lance list` should show the
   step still moving (the chunks stay loaded).

## Tests
- Unit (`LanceTest`): `kind` in every mode, `destDim`, the return chooser's order.
- GameTests: `lance_travel_and_return` (stopped mode: the edge is no trip, the disc spares, the trip, one arrival and
  its return rift, grace, the cooldown, the second trip's pocket, home to the rim, home closed -> another open gate,
  none open -> home regardless, a closed gate takes nobody); `lance_spreading_rules` (before hard mode the Nether, the
  wall up; after it the wall down, a plain teleport into the End turned away, the lance through onto an island, not
  evicted at login, nothing the islands will place on the pad, the islands' return rift home to the gate came out of,
  the End flag cleared); `lance_outer_end` (a wild End-bound rift: a pad >= 1024 out in the test End).
- `NetCodecTest.gateRiftsRoundTrip` covers the payload's lists; `lanceStepsPack` the steps' packing.
- L2 unit (`LanceTest`): costs and times by radius, the strength steps, breach -> latch -> hold, a storm stall, grace ->
  a step at a time back, power back mid-relax, no focus = no power, the dormancy rule.
- L2 GameTests: `lance_multiblock_binds` (no mast; a boom away from the rift; the boom over the eye binds; the tip; the
  base beside the core; one lance per rift; two booms let go; the rift free for a second lance);
  `lance_breach_hold_relax` (no focus, then a too-weak one: nothing; the breach one step at a time with the zones
  following (a probe in the body turns outer zone, the eye stays rift); the latch; the beam; the two forced chunks; the
  hold draw; power cut: the grace and klaxon, then a step at a time back to whole; tickets and beam off at rest; a storm
  box at the tip stalls the breach at twice the draw; the orphan relaxes by itself). Hooks: `LanceBlockEntity.testSupplyW`,
  `testSpeed`, `testStorm`, `testTick`; `Lance.testSpeed`.

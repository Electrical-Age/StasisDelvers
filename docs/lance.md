# The rift lance

Design: `notes/rifts/LANCE-v2.md` (with Argon's review and Baughn's decisions of 2026-10-19, which override the earlier
text). Baughn: "The lance device will be able to open a portal to the End from any rift." Build order (Argon): travel
first. **L1 (built)**: gate state, travel, arrival pads, return rifts, the hard-mode guards, driven by a test command.
**L2 (next)**: the multiblock (core, mast, boom), EA power (~10 kW to hold), the crystal focus, the breach stepping the
radius down, hold and relax. Then strata venting, the eye, storms.

Code: `Lance.java` (everything), hooks in `Freezing`, `HardMode`, `EndBarrier`, `StaticDeeps`, `Gates`, `StasisNet`.

## Gate state
- SavedData `stasisdelvers:lance_gates` (`Lance.GateData`) in the rift's level, keyed by the deep's id: the rift's
  geometry (so it can be found from anywhere) and `open` (CLOSED / OPEN: the latched gate disc).
- An open gate holds its rift at `OPEN_SCALE` (25 %) of its radius: `Lance.effectiveRadius`. **In L1 this is server
  state only**: the client still draws the whole rift and `StaticDeeps.zone` still answers for the whole rift. L2 steps
  the radius down during the breach and makes zones and visuals follow it.
- So that an open gate's core can be entered, Freezing spares (`Lance.spares`: the outer zone's rules instead of the
  rift's statue and the eye's touch) a player in the disc (within ~2.5 blocks of the core) or in the rift outside the
  held 25 % (`shape > 0.35 x 0.25`). A suit is still needed there (the zone is stopped land).
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
| spreading, after hard mode | stronghold gate rift | `CENTRAL_END` | one of the End rift's floating islands (`EndRiftPlan.islands`, a roomy one, hashed per rift), on its planned top, stamped even if `EndRift` hasn't built it yet |
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
  `StasisEvents.levelLoad` and when one is made) and synced in `GateRifts` (protocol 14), so clients draw it.
  `Gates.destination` says "minecraft:overworld" for it (the shader's eye code 2).
- Stepping into a return rift's core (the same reach rule) goes home (`Lance.goHome`, `Lance.choose`, Baughn's
  decision 3):
  - on the End rift's islands (`central`): the gate the player last came out of (persisted NBT
    `stasisdelvers_lance_from`) if it is open; else a random open gate leading to this dimension; else the player's own
    gate regardless (the island's first home if the player has none on record);
  - elsewhere: the return rift's own home gate, in the same order.
  - If no gate is known at all: the respawn point (`Freezing.safeRespawn`).
- Home is `Lance.basePosition`, the hook for L2's lance base; in L1 the rift's rim (`Lance.rim`): rings every 2 blocks
  from just outside the rift zone to r + 12, 16 directions, the standable spot outside the rift nearest the core's
  height; else the surface beside the rift.

## Hard mode (spreading mode; Argon's review)
- `HardMode.travel` lets a lance trip into the closed End (`Lance.travelling`); everything else stays out.
- `HardMode.login` (`evictsOnLogin`) leaves a player who came into the End by lance (persisted NBT
  `stasisdelvers_lance_end`, set on arrival in the End, cleared on leaving it any way).
- `EndBarrier` is off after hard mode on both sides (Baughn's decision 7), on before it.

## Not yet (L2 and later)
- The multiblock, power, focus, breach steps, hold and relax; the 25 % in zones and the shader.
- Spreading mode: an open lance should hold off a small field (r ~4) round its base, or a drifting deep passing over
  running land stops the EA nodes that power it (Argon). That belongs to L2's lance block.
- The home lance's tiny field at the pad (LANCE-v2 point 7), chunk loading, the return rift going dormant when the lance
  is dismantled, strata venting.
- A first trip to a new destination generates the chunks it searches (up to ~25 in the Nether or the outer End) on the
  server thread: a stall of a second or two, once per destination.

## Test command (op)
- `/stasis lance open [rift]`, `/stasis lance close [rift]`: the nearest rift (its core, within ~512 blocks) or one by
  id (`/stasis rifts` doesn't show ids; `list` does). Says where it leads.
- `/stasis lance list`: every gate in every level (state, core, radius, where it leads) and every arrival.

## Lab procedure
1. Stopped mode: `/stasis rifts` to find a rift (or a stronghold's gate rift); stand near it, `/stasis lance open`.
2. Walk (suited) into the eye's centre: you land on a pad in the End or the Nether, with the grace countdown. The return
   rift hangs 5 blocks along the pad. Step into it: back at the home rift's rim.
3. `/stasis lance close`, step into the eye: nothing (the rift's statue, as before).
4. Spreading mode: before `/stasis spread hardmode` a gate goes to the Nether; after it a stronghold gate rift goes to an
   island round the End rift, and the End's wall is gone.

## Tests
- Unit (`LanceTest`): `kind` in every mode, `destDim`, the return chooser's order.
- GameTests: `lance_travel_and_return` (stopped mode: the edge is no trip, the disc spares, the trip, one arrival and
  its return rift, grace, the cooldown, the second trip's pocket, home to the rim, home closed -> another open gate,
  none open -> home regardless, a closed gate takes nobody); `lance_spreading_rules` (before hard mode the Nether, the
  wall up; after it the wall down, a plain teleport into the End turned away, the lance through onto an island, not
  evicted at login, the islands' return rift home to the gate came out of, the End flag cleared).
- `NetCodecTest.gateRiftsRoundTrip` covers the payload's new list.

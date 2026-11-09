# The rift quern

Baughn, 2026-10-21 (notes/quern/PLAN.md; Argon reviewed it, notes/quern/second-opinion-1.md). A wooden table with a
metal bowl that grinds rift shards and, while it grinds, holds a small field: the way to start EA machinery in
stopped land now that the tower yard is off (config `towerYard`, ea-integration.md).

## Rules (`QuernBlockEntity`, `QuernBlock`)
- Fuel: a charged rift shard lasts 10 minutes, a plain one 5 (`CHARGED_TICKS`, `PLAIN_TICKS`). One grinds; a queue
  of 4 waits (`QUEUE`). Use a shard on it to queue it; sneak-use with an empty hand takes the last one back; use
  with an empty hand for its state. Hoppers may fill the queue (Baughn: for now); nothing comes out. Comparators read
  the queue.
- While grinding: a field (`FieldData`) of `RADIUS` 4 at `RATE` 0.4 (towers run at 0.6) centred on the block, orange
  all over (amber 1; Baughn: a much weaker field). Its last 30 s (with the queue empty) its hexes drop out, up to 80 %
  (a failing flicker was lost in the orange shield's pulse: AdventurAgent).
- Its status (empty-hand use) is the field time left in all its shards (Baughn), or "waiting" when time runs there.
- It grinds only where time would run slower than 0.4 without it (`Stasis.rateAtSteadyWithout`): stopped land, a
  drifting deep over running spread land; not in a tower's field, the spawn area or running land. Otherwise it waits
  with its field down (a started shard keeps its time).
- A shard is spent once it starts. Broken, the queue drops. Its block entity ticks in stopped land
  (`Stasis.exemptBlockEntity`). It counts down only while loaded and loads no chunks; unloaded or broken, its field
  goes (the next tick after loading puts it back). Pistons can't move it (`PushReaction.IMMOVEABLE`).
- A hopper is stopped land like anything else: it only runs once the quern's field holds it, so the first shard goes
  in by hand.
- R = 4 holds whole blocks only: 3 out along the axes, less on the diagonals ((3, 0, 2) is outside).

## Look (client)
- The table and bowl are the block model (`models/block/rift_quern.json`).
- `client/QuernRenderer`: the shard hovers over the bowl, turning, smaller as it wears down (full bright); the queue lies
  in the corners; five two-segment arms (netherite, copper joints) stand round the bowl, off the corners (asymmetric: an arm
  overlapped a queued shard), each re-aiming at a point near
  the crystal every 0.7-1.3 s (eased) and firing a violet laser (two crossed additive quads, a white core) while it
  holds. The arms' clock runs at the quern's rate while it grinds; idle, they rest folded.
- `QuernBlockEntity.clientTick`: falling amethyst dust, sparks and violet specks while it grinds.

## Quern vehicles (λ-Nero 2026-11-09; notes/quern-vehicles/PLAN.md)
- The quern's state and rules live in `QuernCore`, shared by the block and the vehicles (and a mini-tower later). A
  `QuernCore.Host` says where it is and whether its field is bound to an entity.
- **Quern Minecart** (`QuernMinecart`, item `quern_minecart`: a minecart + a rift quern): the quern block is its display
  block. It doesn't drive itself and isn't ridden (Nero: chain a quern cart, a minecart and a furnace cart). A shard
  used on it joins the queue; sneak-use with an empty hand takes the last one back; an empty hand shows its status.
- **Quern Boat** (`QuernBoat`, items `quern_WOOD_boat` and `quern_bamboo_raft`: that wood's chest boat + a rift quern):
  one entity type and item per vanilla wood (`QuernVehicles.WOODS`, 11 in 26.3; modded woods aren't covered). It keeps
  its chest. A shard used on it joins the queue; otherwise it's a chest boat (ride; sneak-use opens the chest).
  `client/QuernBoatRenderer` draws the wood's plain boat or raft model with the quern block at the stern.
- Same rules as the block: radius 4, rate 0.4, only where time would run slower without it, shards 5 or 10 minutes.
- **The field moves with the vehicle.** It is bound to the entity (`FieldData.bind`), moved each tick with
  `FieldData.move` (no packet), and each client moves it to its own copy of the entity (`ClientFields.follow`); see
  time-stop.md. So a boat's own client sees the water ahead thawed where its boat is now.
- **Starting in stopped land:** a vehicle with no field yet is frozen, so `StasisEvents.entityTick` runs its quern
  (`QuernVehicles.serverTick`) whether or not it is frozen.
- A quern vehicle's rider runs at full speed in its field (`PlayerRates.rate`; Nero), as with a suit.
- Gone (destroyed, unloaded, through a portal), its field goes; destroyed, the queue drops. Its saved field id records
  its level, so after a portal it doesn't take over another level's field with that id.

## Tests
`quern_grinds_a_field`, `quern_idle_in_a_field`, `quern_hopper_and_break`, `tower_yard_off_lance_yard_on`;
vehicles: `quern_cart_field_follows`, `quern_cart_waits_in_a_field`, `quern_cart_move_grace`, `quern_boat_thaws_water`,
`quern_boat_every_wood`.

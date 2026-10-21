# The rift quern

Baughn, 2026-10-21 (notes/quern/PLAN.md; Argon reviewed it, notes/quern/second-opinion-1.md). A wooden table with a
metal bowl that grinds rift shards and, while it grinds, holds a small field: the way to start EA machinery in
stopped land now that the tower yard is off (config `towerYard`, ea-integration.md).

## Rules (`QuernBlockEntity`, `QuernBlock`)
- Fuel: a charged rift shard lasts 10 minutes, a plain one 5 (`CHARGED_TICKS`, `PLAIN_TICKS`). One grinds; a queue
  of 4 waits (`QUEUE`). Use a shard on it to queue it; sneak-use with an empty hand takes the last one back; use
  with an empty hand for its state. Hoppers may fill the queue (Baughn: for now); nothing comes out. Comparators read
  the queue.
- While grinding: a field (`FieldData`) of `RADIUS` 4 at `RATE` 0.4 (towers run at 0.6) centred on the block. Its last
  30 s (with the queue empty) it is failing 0.5: the shield's flicker, below the eddies' 0.7.
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
  in the corners; five two-segment arms (netherite, copper joints) stand round the bowl, each re-aiming at a point near
  the crystal every 0.7-1.3 s (eased) and firing a violet laser (two crossed additive quads, a white core) while it
  holds. The arms' clock runs at the quern's rate while it grinds; idle, they rest folded.
- `QuernBlockEntity.clientTick`: falling amethyst dust, sparks and violet specks while it grinds.

## Tests
`quern_grinds_a_field`, `quern_idle_in_a_field`, `quern_hopper_and_break`, `tower_yard_off_lance_yard_on`.

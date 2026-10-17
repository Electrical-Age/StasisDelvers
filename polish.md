# Polish (end of the todo list)

Cosmetic leftovers to batch later. Add screenshots/layouts when you find more.

## Tower mesh (towergen MeshBuilder)
- Where a laced leg goes from 4 to 3 chords, the wider member's end battens show as a step. (2026-10-17: tried the
  3-chord member's ring instead; worse at big width changes, kept; see towergen/NOTES.md "Gusset seams".)
- Brace hubs inside laced legs: since 2026-10-17 drawn in the member colour instead of dark knuckle; they are still
  there (cutting the braces at the leg's faces left wide / shallow braces ending in mid-air, so that was reverted).
- K nodes (two wide laced diagonals meeting a leg): the gusset fills the crotch, up to 0.64 blocks on H64's widest
  legs. Fine if it reads as a gusset plate; revisit if it looks heavy.
- Nearly parallel members past the setback cap overlap inside each other.
- Fat member ending at a node can leave a block sticking out; ground hubs reach below ground.

## Generator
- (Done 2026-10-17) H40b4 strut colour JS vs JVM: tie forces (mirrored wind cases) are now steel; all 7 presets
  hash identically in the browser (towergen/NOTES.md "Strut colour: ties are steel").
- Stay rule refuses booms stacked 1-3 blocks apart on one face (anchors only at 4-block ring levels). 2026-10-17:
  `stayAtBoomAbove` hangs the lower boom from the upper boom's root nodes (refusals 16.7% -> 11.1%), but it is OFF:
  without a library rebuild ~5% of the newly accepted layouts are over the catalogue or mechanisms. Needs a
  decision: rebuild (mirror in towerlib) vs runtime bracing vs leave it (towergen/NOTES.md "Stays between rings").
- maxStaysPerBoom is a target: flat truss booms keep about one stay per free boom node (measured 2026-10-17: the
  flat-node put-back does nearly all of it); a real cut needs boom designs with fewer free nodes or out-of-plane
  depth, i.e. a library rebuild (towergen/NOTES.md "Known limits" under solver parameters).

## Client
- Suit glove: check slim (Alex) skins and two-handed maps; glove art is placeholder (mostly sleeve and cuff visible).

## Field tick rates
- From inside a slow field, arrows (client-simulated flight) creep at the client rate, then snap at each position sync
  (AdventurAgent). Walking mobs are fine (interpolated). Real-time interpolation steps were tried (727d17e) and
  reverted: mobs pulsed at a 4 Hz beat. An arrow fix would need extra client physics steps for projectiles only.
- Not yet checked: mob AI near/in slow fields; fields above or below the player.

## Later (Baughn 2026-10-13, msg 1559534246849282069)
- Doors in the lattice (ladder well access).
- Lattice a bit too dense: stronger material would thin it.

- Lattice-top cap (H14 E8 L4): big grey joint hulls at the top-ring corners where the cap struts meet (cosmetic).


- Field collapse effect too predictable: some hexes never show, and it barely depends on charge (Baughn 2026-10-14, later).

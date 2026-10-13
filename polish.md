# Polish (end of the todo list)

Cosmetic leftovers to batch later. Add screenshots/layouts when you find more.

## Tower mesh (towergen MeshBuilder)
- Chord ends are cut exactly at the gusset face, so end faces and battens are coplanar with it: thin slivers and
  faint z-fighting (Baughn, Discord 1559369833332744273). Fix: sink chord ends slightly into the hub, drop caps inside.
- Where a laced leg goes from 4 to 3 chords, the wider member's end battens show as a step.
- Small dark brace hubs float inside laced legs.
- K nodes (two wide laced diagonals meeting a leg): the gusset fills the crotch, up to 0.64 blocks on H64's widest
  legs. Fine if it reads as a gusset plate; revisit if it looks heavy.
- Nearly parallel members past the setback cap overlap inside each other.
- Fat member ending at a node can leave a block sticking out; ground hubs reach below ground.

## Generator
- H40b4: one strut's tension/compression colour differs between browser (JS) and JVM (force sign, floating point).
- Stay rule refuses booms stacked 1-3 blocks apart on one face (anchors only at 4-block ring levels): relax with
  anchors between rings or hanging the lower boom from the upper one.
- maxStaysPerBoom is a target: flat truss booms keep about one stay per node; a real cut needs fewer boom stations.

## Client
- Suit glove: check slim (Alex) skins and two-handed maps; glove art is placeholder (mostly sleeve and cuff visible).

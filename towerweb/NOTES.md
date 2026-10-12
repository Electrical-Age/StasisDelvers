# towerweb: towergen in the browser

A static site (https://towers.brage.info) that runs the mod's tower generator client-side: TeaVM compiles
`towergen` to an ES module that runs in a Web Worker; three.js draws the mesh. There are editors for layout.json and
visual.json, a q slider, the 7 golden layouts, a random-layout button (`LayoutGen`, the game rules), stats, errors
and warnings, and a store-only zip download of both JSON files. Edits stay in `localStorage`; nothing goes to a server.

## Build and deploy
- `tools/stasis-build.sh :towerweb:dist` -> `towerweb/dist/` (git-ignored). Copy the directory to the web root.
  No server code, no symlinks. Any static server works.
- `dist` pulls in: `generateJavaScript` (TeaVM 0.16.0, JS target, ES2015 module, obfuscated) -> `js/towergen.js`;
  `:towergen:libraryBin` -> `data/towerlib.bin` (gzipped TGLB1); `genCodec` -> `data/visual-defaults.json`,
  `data/visual-doc.json`; `towergen/layouts/*.json` -> `layouts/`; `vendor` (three.js 0.186.1, CodeMirror 5.65.21,
  minified copies fetched once from jsDelivr into `build/vendor`, MIT, licences copied along) -> `vendor/`; `src/web`.
- The TeaVM tasks only run when asked for. The root `build` compiles towerweb's Java but does not run TeaVM or `dist`.
- Local test: `cd towerweb/dist && nix-shell -p python3 --run 'python3 -m http.server 8765'`. Module workers need
  http(s), not `file://`.

## Code
- `src/main/java/.../TowerWeb.java`: the exported API (`@JSExport`). `loadLibrary(Int8Array)` takes TGLB1 or
  p3a JSON bytes (uncompressed). `build(layoutJson, q, visualJson)` returns stats JSON: counts, util, volume, tiers,
  timings, warnings, bounds, part colours and the viewer keys of visual.json. It returns `{"error": ...}` on bad
  JSON, a layout that breaks the game rules (not built), or a generator exception. Then `positions()`
  (Float32Array, 9 per triangle), `normals()` (one per triangle), `parts()` (Int8Array, `VisualParams.Part` ordinal).
  Also `randomLayout(seed)` and `formatLayout(json)`.
- `src/gen/java/.../GenCodec.java`: runs on the JVM at build time. TeaVM has no record reflection, so
  `VisualParams.fromJson` can't run in the browser. GenCodec writes `VisualParamsCodec.java` (an explicit constructor
  call; type errors name the key), the default visual.json, and the key docs. The docs come from the comments in
  `VisualParams.java`, plus an `EXTRA` map for the keys that have none. A new record component is picked up
  automatically; give it a comment.
- `src/web/js/worker.js`: fetches `data/towerlib.bin` and gunzips it with `DecompressionStream`. It sniffs the gzip
  magic, so a server that sets Content-Encoding still works. It sorts the triangles by part, one draw group and one
  material per part, and expands the normals per vertex.
- `src/web/js/app.js`: three.js scene (sky gradient, fog, ground, grid, hemisphere and sun light with a shadow map;
  all from visual.json), OrbitControls, CodeMirror editors (falls back to a textarea), debounced rebuild (350 ms),
  at most one build in flight (the latest edit wins), a JSON error line finder, `window.__tw` for tests.
  `js/zip.js`: store-only zip with CRC32.

## towergen changes made for this
- `Portable.compare` / `Portable.sha1` replace `Arrays.compare(int[], int[])` and `MessageDigest("SHA-1")`, which the
  TeaVM classlib lacks. The goldens are unchanged.
- `LibraryBin` (TGLB1): a lossless binary library. Doubles are stored as varint k with a tag when the value is exactly
  k/16 (97% of node coordinates) or k/1e4 (reactions, pretensions), else 8 raw bytes. The header is kept as JSON.
  `LibraryBinTest` checks every field bit for bit, plus mesh hashes from both libraries. `Library.header` is now kept.
  `:towergen:libraryBin` writes `build/libbin/p3a_library.bin.gz`.

## Numbers (tsugumi, headless Chromium 1243 via playwright, SwiftShader WebGL, localhost)
- Library: JSON 32.4 MB raw / 7.8 MB gz takes 4.65 s to parse in V8 (node, TeaVM code) vs 0.47 s on the JVM.
  TGLB1 is 6.6 MB raw / 2.0 MB gz: fetch 10 ms + gunzip 19 ms + parse 145 ms in the browser (JVM 20 ms).
  The page is ready about 0.3 s after navigation on localhost, and the first tower is drawn at about 0.5 s. Over the
  network the page transfers about 2.5 MB (the 2.0 MB library plus 0.45 MB of JS/CSS if the server gzips it,
  1.6 MB if it doesn't).
- Builds (statics + lookup + stitch + pass + mesh, in the worker; JVM = JDK 25 warm median of 15 runs):

| layout | browser q1 / q0.3 | JVM q1 / q0.3 | triangles q1 |
|---|---|---|---|
| H16 | 3 / 3 ms | 2 / 2 ms | 1344 |
| H24b | 31 / 24 ms | 12 / 9 ms | 13464 |
| H40 | 17 / 26 ms | 6 / 13 ms | 19512 |
| H40r3 | 87 / 120 ms | 37 / 64 ms | 62736 |
| H40b4 | 60 / 65 ms | 32 / 32 ms | 49740 |
| H64max | 66 / 120 ms | 26 / 44 ms | 116568 |
| H100 | 82 / 99 ms | 26 / 32 ms | 100056 |

  Triangle counts match the JVM exactly for all 14 runs, and max utilisations agree to about 1e-13. Add 2-8 ms for packing and the transfer to the main thread.
  The first build after loading takes about 145 ms (H40r3), because the JIT is cold.
- dist: 3.65 MB in 27 files. The library is 2.0 MB, towergen.js 0.57 MB (0.17 MB gzipped), three.js 0.83 MB, and
  CodeMirror 0.2 MB.

## Known issues / ideas
- JS `Math.sin/cos/pow/log` may differ from the JVM in the last ulp, so a borderline layout could in principle pick a
  different ladder class than in the game. None seen on the goldens: same triangle counts, and utils equal to 1e-13.
- The game's material ids are shown as colours only. Glow is emissive, steel/brass/cable are slightly metallic.
- WASM-GC was not tried; the JS target is already within 2-3x of the JVM.
- Very large meshes (H64max is 117k triangles) are fine on desktop but heavy on phones. There is no LOD.
- The ground grid barely shows through the fog at the default colours.
- `vendor` fetches jsDelivr's on-the-fly minified files without checksums, so a clean rebuild may get different
  (equivalent) bytes. Pin npm tarballs if that matters.
- The camera reframes only on a preset/random/reset or a marked change of tower size. Double-click reframes.
- Needs a 2023+ browser: import maps, module workers, DecompressionStream.
- Extreme layouts (H100 with four 96-block booms) build in about 1.2 s and come out at util 6.5. They show the
  over-catalogue warning and still render.

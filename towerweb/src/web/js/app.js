// Page logic: editors, 3D view (three.js), rebuilds in a Web Worker, localStorage, zip download.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { makeZip } from './zip.js';

const PRESETS = ['H16', 'H24b', 'H40', 'H40r3', 'H40b4', 'H64max', 'H100'];
const DEFAULT_PRESET = 'H40r3';
const LS = { layout: 'towerweb.layout', visual: 'towerweb.visual', solver: 'towerweb.solver', q: 'towerweb.q', preset: 'towerweb.preset', tab: 'towerweb.tab' };
const $ = (id) => document.getElementById(id);
// cache busting: dist's build rewrites the placeholder with {path: content hash}; v(path) appends ?v=hash
const ASSETS = /*@ASSETS@*/{};
const v = (p) => (ASSETS[p] ? `${p}?v=${ASSETS[p]}` : p);

// exposed for tests / the console
const T = window.__tw = { ready: false, builds: 0, last: null, load: null, errors: [] };

// ------------------------------------------------------------------ editors
function makeEditor(host, onChange) {
  if (window.CodeMirror) {
    const cm = window.CodeMirror(host, {
      mode: { name: 'javascript', json: true }, lineNumbers: true, matchBrackets: true, autoCloseBrackets: true,
      tabSize: 2, indentUnit: 2, viewportMargin: 50,
    });
    cm.on('change', () => onChange());
    let errLine = null;
    return {
      get: () => cm.getValue(),
      set: (s) => cm.setValue(s),
      refresh: () => cm.refresh(),
      markError: (line) => {
        if (errLine !== null) cm.removeLineClass(errLine, 'background', 'cm-errline');
        errLine = line;
        if (line !== null && line < cm.lineCount()) cm.addLineClass(line, 'background', 'cm-errline');
        else errLine = null;
      },
    };
  }
  const ta = document.createElement('textarea');            // fallback without CodeMirror
  ta.className = 'plain'; ta.spellcheck = false;
  host.appendChild(ta);
  ta.addEventListener('input', () => onChange());
  return { get: () => ta.value, set: (s) => { ta.value = s; onChange(); }, refresh: () => {}, markError: () => {} };
}

// JSON.parse with a line number for the error (located by a small scanner; browsers' messages differ)
function parseJson(text, what) {
  try { return { value: JSON.parse(text) }; } catch (e) {
    const pos = jsonErrorPos(text);
    const line = text.slice(0, pos).split('\n').length - 1;
    return { error: `${what} line ${line + 1}: ${e.message}`, line };
  }
}

// offset of the first syntax error in a JSON text (strict JSON)
function jsonErrorPos(t) {
  let i = 0;
  const ws = () => { while (i < t.length && ' \t\n\r'.includes(t[i])) i++; };
  const fail = () => { throw i; };
  const lit = (w) => { if (t.startsWith(w, i)) i += w.length; else fail(); };
  function str() {
    i++;
    while (i < t.length && t[i] !== '"') {
      if (t[i] === '\\') { i++; if (t[i] === 'u') { if (!/^[0-9a-fA-F]{4}$/.test(t.substr(i + 1, 4))) fail(); i += 4; } else if (!'"\\/bfnrt'.includes(t[i])) fail(); }
      else if (t.charCodeAt(i) < 0x20) fail();
      i++;
    }
    if (i >= t.length) fail();
    i++;
  }
  function val() {
    ws();
    const c = t[i];
    if (c === '{') {
      i++; ws();
      if (t[i] === '}') { i++; return; }
      for (;;) {
        ws(); if (t[i] !== '"') fail(); str(); ws();
        if (t[i] !== ':') fail(); i++; val(); ws();
        if (t[i] === ',') { i++; continue; }
        if (t[i] === '}') { i++; return; }
        fail();
      }
    } else if (c === '[') {
      i++; ws();
      if (t[i] === ']') { i++; return; }
      for (;;) {
        val(); ws();
        if (t[i] === ',') { i++; continue; }
        if (t[i] === ']') { i++; return; }
        fail();
      }
    } else if (c === '"') str();
    else if (c === 't') lit('true');
    else if (c === 'f') lit('false');
    else if (c === 'n') lit('null');
    else {
      const m = /^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?/.exec(t.slice(i, i + 64));
      if (!m) fail();
      i += m[0].length;
    }
  }
  try { val(); ws(); if (i < t.length) fail(); return t.length; } catch (p) { if (typeof p === 'number') return Math.min(p, t.length); throw p; }
}

// ------------------------------------------------------------------ 3D view
const view = $('view');
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFShadowMap;
view.prepend(renderer.domElement);

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 5000);
camera.position.set(60, 40, 60);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.target.set(0, 20, 0);

const hemi = new THREE.HemisphereLight(0xffffff, 0x444444, 0.6);
scene.add(hemi);
const sun = new THREE.DirectionalLight(0xffffff, 2.2);
sun.castShadow = true;
sun.shadow.mapSize.set(2048, 2048);
sun.shadow.bias = -0.0004;
sun.shadow.normalBias = 0.03;
scene.add(sun, sun.target);

const groundMat = new THREE.MeshStandardMaterial({ color: 0x6b7360, roughness: 1 });
const ground = new THREE.Mesh(new THREE.CircleGeometry(1, 64), groundMat);
ground.rotation.x = -Math.PI / 2;
ground.receiveShadow = true;
scene.add(ground);
let grid = null;

let tower = null, towerMats = [];
let framedKey = null;

function srgb(c) { return new THREE.Color().setRGB(c[0], c[1], c[2], THREE.SRGBColorSpace); }

function skyTexture(top, horizon) {
  const c = document.createElement('canvas');
  c.width = 2; c.height = 256;
  const g = c.getContext('2d');
  const grad = g.createLinearGradient(0, 0, 0, 256);
  const css = (a) => `rgb(${a.map((x) => Math.round(255 * Math.min(1, Math.max(0, x)))).join(',')})`;
  grad.addColorStop(0, css(top)); grad.addColorStop(1, css(horizon));
  g.fillStyle = grad; g.fillRect(0, 0, 2, 256);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

let lastViewKey = '';
function applyView(v, radius) {
  const key = JSON.stringify(v) + radius.toFixed(0);
  if (key === lastViewKey) return;
  lastViewKey = key;
  if (scene.background) scene.background.dispose();
  scene.background = skyTexture(v.skyTop, v.skyHorizon);
  const far = Math.max(400, radius * 12);
  scene.fog = new THREE.Fog(srgb(v.skyHorizon), far * 0.35, far);
  camera.fov = v.fovDeg; camera.far = far * 2; camera.updateProjectionMatrix();
  hemi.color = srgb(v.skyTop); hemi.groundColor = srgb(v.groundColour);
  hemi.intensity = 2.5 * v.ambient;
  const az = v.sunAzimuth * Math.PI / 180, el = v.sunElevation * Math.PI / 180;
  const d = new THREE.Vector3(Math.cos(el) * Math.cos(az), Math.sin(el), Math.cos(el) * Math.sin(az));
  sun.position.copy(d.multiplyScalar(radius * 3)); sun.target.position.set(0, 0, 0);
  const s = sun.shadow.camera;
  s.left = s.bottom = -radius * 1.1; s.right = s.top = radius * 1.1; s.near = 0.1; s.far = radius * 6;
  s.updateProjectionMatrix();
  groundMat.color = srgb(v.groundColour);
  ground.visible = v.drawGround;
  ground.scale.setScalar(far);
  if (grid) { scene.remove(grid); grid.geometry.dispose(); grid = null; }
  if (v.drawGround && v.groundGrid > 0) {
    const n = Math.min(400, Math.ceil(radius * 3 / v.groundGrid));
    const gc = srgb(v.groundColour).multiplyScalar(0.6);
    grid = new THREE.GridHelper(2 * n * v.groundGrid, 2 * n, gc, gc);
    grid.position.y = 0.01;
    grid.material.transparent = true; grid.material.opacity = 0.6;
    scene.add(grid);
  }
}

function showMesh(msg) {
  const s = msg.stats;
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(msg.positions, 3));
  geo.setAttribute('normal', new THREE.BufferAttribute(msg.normals, 3));
  towerMats.forEach((m) => m.dispose());
  towerMats = s.partColours.map((c, i) => {
    const glow = s.partNames[i] === 'GLOW';
    const metal = ['STEEL', 'BRASS', 'CABLE'].includes(s.partMaterials[i]);
    return new THREE.MeshStandardMaterial({
      color: srgb(c), roughness: metal ? 0.55 : 0.85, metalness: metal ? 0.35 : 0.05,
      emissive: glow ? srgb(c) : 0x000000, emissiveIntensity: glow ? 1.5 : 0,
    });
  });
  msg.groups.forEach((g) => geo.addGroup(g.start, g.count, g.part));
  geo.computeBoundingSphere();
  if (tower) { scene.remove(tower); tower.geometry.dispose(); }
  tower = new THREE.Mesh(geo, towerMats);
  tower.castShadow = true; tower.receiveShadow = true;
  scene.add(tower);
  const b = s.bounds;
  const radius = Math.max(10, Math.hypot(b[3] - b[0], b[4] - b[1], b[5] - b[2]) / 2);
  applyView(s.view, radius);
  const key = `${s.H}|${Math.round(Math.log2(radius) * 2)}`;   // reframe when the size changes markedly
  if (key !== framedKey) { frame(b); framedKey = key; }
}

function frame(b) {
  if (!b) { if (!T.last) return; b = T.last.bounds; }
  const cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
  const r = Math.max(4, Math.hypot(b[3] - b[0], b[4] - b[1], b[5] - b[2]) / 2);
  const dist = r / Math.sin(camera.fov * Math.PI / 360) * 0.75;
  const az = 215 * Math.PI / 180, el = 18 * Math.PI / 180;
  controls.target.set(cx, cy, cz);
  camera.position.set(cx + dist * Math.cos(el) * Math.cos(az), cy + dist * Math.sin(el), cz + dist * Math.cos(el) * Math.sin(az));
  controls.update();
}
renderer.domElement.addEventListener('dblclick', () => frame());

function resize() {
  const w = view.clientWidth, h = view.clientHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / Math.max(1, h);
  camera.updateProjectionMatrix();
}
new ResizeObserver(resize).observe(view);
resize();
renderer.setAnimationLoop(() => { controls.update(); renderer.render(scene, camera); });

// ------------------------------------------------------------------ state, worker
const state = { preset: localStorage.getItem(LS.preset) || DEFAULT_PRESET, q: +(localStorage.getItem(LS.q) || 1) };
const defaults = { layouts: {}, visual: '', solver: '' };
const worker = new Worker(new URL('../' + v('js/worker.js'), import.meta.url), { type: 'module' });
let jsonErrs = [];
let busy = false, pending = null, nextId = 1, timer = null, randomCb = null, statusNote = '';

function setStatus(s) { $('status').textContent = s; }
function showErrors(errs, warns) {
  const box = $('errors');
  box.innerHTML = '';
  for (const e of errs) { const d = document.createElement('div'); d.className = 'err'; d.textContent = e; box.appendChild(d); }
  for (const w of warns) { const d = document.createElement('div'); d.className = 'warn'; d.textContent = w; box.appendChild(d); }
}

function scheduleBuild(delay = 350) {
  clearTimeout(timer);
  timer = setTimeout(requestBuild, delay);
}

function requestBuild() {
  const lt = edLayout.get(), vt = edVisual.get(), st = edSolver.get();
  localStorage.setItem(LS.layout, lt);
  localStorage.setItem(LS.visual, vt);
  localStorage.setItem(LS.solver, st);
  localStorage.setItem(LS.q, String(state.q));
  localStorage.setItem(LS.preset, state.preset);
  const pl = parseJson(lt, 'layout.json'), pv = vt.trim() ? parseJson(vt, 'visual.json') : { value: {} };
  edLayout.markError(pl.error ? pl.line : null);
  const ps = st.trim() ? parseJson(st, 'solver.json') : { value: {} };
  edVisual.markError(pv.error ? pv.line : null);
  edSolver.markError(ps.error ? ps.line : null);
  const errs = [pl.error, pv.error, ps.error].filter(Boolean);
  jsonErrs = errs;
  if (errs.length) { pending = null; showErrors(errs, []); setStatus('not rebuilt: fix the JSON'); return; }
  pending = { type: 'build', layout: lt, q: state.q, visual: vt, solver: st };
  pump();
}

function pump() {
  if (busy || !pending || !T.ready) return;
  const m = pending; pending = null;
  m.id = nextId++;
  busy = true;
  m.t0 = performance.now();
  inflight = m;
  setStatus('building…');
  worker.postMessage({ type: m.type, id: m.id, layout: m.layout, q: m.q, visual: m.visual, solver: m.solver });
}
let inflight = null;

worker.onmessage = (e) => {
  const m = e.data;
  if (m.type === 'ready') {
    T.ready = true; T.load = m;
    $('overlay').classList.add('hidden');
    console.log(`[towerweb] library ready: ${m.entries} entries, ${(m.bytes / 1e6).toFixed(2)} MB download, fetch ${m.fetchMs.toFixed(0)} ms, inflate ${m.inflateMs.toFixed(0)} ms, parse ${m.parseMs.toFixed(0)} ms, total ${(performance.now() - T.t0).toFixed(0)} ms since page start`);
    pump();
  } else if (m.type === 'fatal') {
    $('loading').textContent = 'Could not load the generator: ' + m.error;
    T.errors.push(m.error);
  } else if (m.type === 'random') {
    if (randomCb) randomCb(m.layout);
  } else if (m.type === 'built' || m.type === 'error') {
    busy = false;
    const wall = performance.now() - inflight.t0;
    if (m.type === 'built') {
      T.builds++; T.last = m.stats; m.stats.ms.roundTrip = wall;
      const st = m.stats, ms = st.ms;
      const gen = (ms.statics || 0) + (ms.reduce || 0) + (ms.pass || 0);
      // status and errors first, so a display problem below cannot leave a stale "building…" or error behind
      if (jsonErrs.length) { showErrors(jsonErrs, []); setStatus('not rebuilt: fix the JSON (showing the last good tower)'); }
      else {
        showErrors(st.refusal ? ['REFUSED (the game would not build this): ' + st.refusal.message] : [], st.warnings || []);
        setStatus(statusNote + `built in ${fmt(ms.total)} ms (generator ${fmt(gen)} ms, mesh ${fmt(ms.mesh)} ms; ${wall.toFixed(0)} ms incl. worker and upload)`);
      }
      try { showMesh(m); showStats(st); } catch (err) {
        T.errors.push('display: ' + err.message);
        showErrors([`could not display the result (${err.message}); if this persists, reload the page`], st.warnings || []);
        console.error(err);
      }
      console.log(`[towerweb] built H${st.H} q${st.q}: ${st.triangles} triangles, util ${st.util?.toFixed(3)}, generator ${gen.toFixed(1)} ms, mesh ${fmt(ms.mesh, 1)} ms, round trip ${wall.toFixed(0)} ms`);
    } else {
      T.errors.push(m.error);
      showErrors([...jsonErrs, m.error], []);
      setStatus(String(m.error).startsWith('REFUSED') ? 'refused: the game would not build this layout (showing the last good tower)'
                                                      : 'build failed (showing the last good tower)');
      console.log('[towerweb] error: ' + m.error);
    }
    pump();
  }
};

// wire counts: final tower, and the stitched (library) tower if different
function wiresRow(w, s) {
  if (!w || !w.final) return `${s.cables} cables${s.backstays ? `, ${s.backstays} back-stays` : ''}`;
  const f = w.final, r = w.stitched;
  const one = (c) => `${c.cables} cables, ${c.stays} stays (${c.stayCables} cables), ${c.backstays} back-stays`;
  let h = one(f);
  if (f.cables !== r.cables || f.stays !== r.stays || f.backstays !== r.backstays) h += `<br><span class="dim">stitched: ${one(r)}</span>`;
  const x = w.reduction;
  if (x) h += `<br><span class="dim">reduction: ${x.xSingled} X pairs → 1 strut${x.xRestored ? ` (${x.xRestored} put back)` : ''}, ${x.bayCablesToStruts} bay cables → struts, stays −${x.staysRemoved} +${x.staysPutBack}</span>`;
  return h;
}

function fmt(x, d = 0) { return x == null || Number.isNaN(x) ? '–' : x.toFixed(d); }
function showStats(s) {
  const tiers = Object.entries(s.tiers).map(([k, v]) => `${k} ×${v}`).join(', ');
  const util = s.util == null ? '<span class="bad">mechanism</span>'
    : `<span class="${s.util > 1.001 ? 'bad' : ''}">${fmt(s.util, 3)}</span> (before resizing ${fmt(s.rawUtil, 2)})`;
  const rows = [
    ['Layout', `H ${s.H}, ${s.booms} boom${s.booms === 1 ? '' : 's'}, q ${fmt(s.q, 2)}`],
    ['Nodes / members', `${s.nodes} / ${s.members}`],
    ['Struts', `${s.struts} (${s.laced} laced)`],
    ['Wires', wiresRow(s.wires, s)],
    ['Max utilisation', util],
    ['Steel volume', `${fmt(s.volume, 1)} (raw ${fmt(s.volumeRaw, 1)}), ${s.rounds} resize rounds`],
    ['Library tiers', tiers],
    ['Triangles', `${s.triangles}`],
    ['Time', `statics+lookup+stitch ${fmt(s.ms.statics, 1)} ms, ${s.wires && s.wires.reduction ? `wire reduction ${fmt(s.ms.reduce, 1)} ms, ` : ''}pass ${fmt(s.ms.pass, 1)} ms, mesh ${fmt(s.ms.mesh, 1)} ms`],
  ];
  $('stats').innerHTML = rows.map(([a, b]) => `<tr><td>${a}</td><td>${b}</td></tr>`).join('');
}

// ------------------------------------------------------------------ controls
const edLayout = makeEditor($('edLayout'), () => { statusNote = ''; scheduleBuild(); });
const edVisual = makeEditor($('edVisual'), () => { statusNote = ''; syncControls('visual'); scheduleBuild(); });
const edSolver = makeEditor($('edSolver'), () => { statusNote = ''; syncControls('solver'); scheduleBuild(); });

const sel = $('preset');
for (const p of PRESETS) { const o = document.createElement('option'); o.value = p; o.textContent = p; sel.appendChild(o); }
const customOpt = document.createElement('option'); customOpt.value = 'custom'; customOpt.textContent = '(edited / random)';
sel.appendChild(customOpt);

function setPreset(name) {
  state.preset = name;
  sel.value = name;
  if (defaults.layouts[name]) { edLayout.set(defaults.layouts[name]); }
}
sel.addEventListener('change', () => { if (sel.value !== 'custom') { setPreset(sel.value); framedKey = null; requestBuild(); } });
$('random').addEventListener('click', () => {
  if (!T.ready) return;
  const seed = Math.floor(Math.random() * 2147483647);
  randomCb = (layout) => {
    randomCb = null;
    state.preset = 'custom'; sel.value = 'custom';
    framedKey = null;
    edLayout.set(layout);
    statusNote = `random layout (seed ${seed}): `;
    clearTimeout(timer); requestBuild();
  };
  worker.postMessage({ type: 'random', id: 0, seed });
});
$('resetLayout').addEventListener('click', () => {
  setPreset(PRESETS.includes(state.preset) ? state.preset : DEFAULT_PRESET); framedKey = null; requestBuild();
});
$('resetVisual').addEventListener('click', () => { edVisual.set(defaults.visual); requestBuild(); });
document.querySelectorAll('.helplink').forEach((a) => a.addEventListener('click', () => { $('help').open = true; }));
$('resetSolverCtl').addEventListener('click', () => { edSolver.set(defaults.solver); requestBuild(); });
$('resetVisualCtl').addEventListener('click', () => { edVisual.set(defaults.visual); requestBuild(); });
$('resetSolver').addEventListener('click', () => { edSolver.set(defaults.solver); requestBuild(); });
const q = $('q');
function setQ(v) { state.q = v; q.value = v; $('qval').textContent = (+v).toFixed(2); }
q.addEventListener('input', () => { setQ(+q.value); scheduleBuild(150); });
$('zip').addEventListener('click', () => {
  const blob = makeZip([{ name: 'layout.json', data: edLayout.get() }, { name: 'visual.json', data: edVisual.get() },
    { name: 'solver.json', data: edSolver.get() }]);
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'tower-json.zip';
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
});

// ------------------------------------------------------------------ slider controls (generated from the key docs)
// Both views edit the same JSON text: a control change rewrites the key in the editor (one key per line), an editor
// change re-reads every control. Keys missing from the JSON show their default.
const ENUMS = { xPanels: ['keep', 'single', 'auto'], stayCap: ['same', 'any', 'none'] };
// [min, max, 'log'?] where the default-derived range does not fit (documented valid ranges, angles, loads)
const RANGES = {
  maxStaysPerBoom: [0, 30], minStaysPerBoom: [0, 4], maxCablesPerBay: [0, 16],
  backstayMinBoom: [2, 100], backstayDistFactor: [0.1, 2], backstayDistCap: [2, 64], backstayMinTension: [0, 5],
  backstaySecondPair: [5, 1000, 'log'], stayRise: [0.2, 2], stayMinRatio: [0, 1], stayMinRise: [0, 8],
  pretensionFactor: [0, 5], pretensionMargin: [1, 2], raiseIters: [0, 200], maxRounds: [1, 100], utilLimit: [0.5, 1.5],
  emitterWeight: [0.3, 30, 'log'], emitterWind: [0.05, 5, 'log'], windDrag: [0.003, 0.3, 'log'], torsion: [0.04, 4, 'log'],
  density: [0.1, 10, 'log'], extraWidthSteps: [0, 12],
  sunAzimuth: [0, 360], sunElevation: [0, 90], fovDeg: [15, 110], ambient: [0, 1], lacingPanelsMin: [1, 10],
  lacingPanelsMax: [2, 40], groundGrid: [0, 16], lacedEndTaper: [0, 0.5], lacedEndFrac: [0, 1], lacingWidthFrac: [0.05, 1],
  jointMargin: [0, 1], jointMaxSetbackFrac: [0, 0.49], jointMaxSetback: [0, 4], gussetLip: [0, 1], chordSink: [0, 2], crossOffsetFrac: [0, 1], crossMinAngle: [0, 45],
};
const SECTION_NAMES = [
  ['wire reduction', 'Wires'], ['back-stays', 'Back-stays'], ['boom stays', 'Stays'], ['pretension', 'Pass'], ['loads', 'Loads'],
  ['member catalogue', 'Catalogue'], ['solid struts', 'Struts, pole, cables'], ['laced', 'Lacing'],
  ['joints (hull hubs)', 'Joints'], ['footings', 'Footings, deadmen, core, emitters'], ['colours', 'Colours'], ['viewer', 'Viewer'],
];
const OPEN_SECTIONS = ['Wires', 'Struts, pole, cables'];
const paramCtls = { visual: [], solver: [] };

function niceCeil(x) {
  const e = Math.pow(10, Math.floor(Math.log10(x))), m = x / e;
  return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 2.5 ? 2.5 : m <= 5 ? 5 : 10) * e;
}
function rangeOf(e) {
  if (RANGES[e.key]) return RANGES[e.key];
  const d = +e.default;
  if (e.type === 'integer') return [0, Math.max(4, Math.ceil(3 * d))];
  return [0, d > 0 ? niceCeil(3 * d) : 1];
}
const round = (x) => +(+x).toPrecision(4);
const hex = (c) => '#' + c.map((v) => Math.round(Math.max(0, Math.min(1, v)) * 255).toString(16).padStart(2, '0')).join('');
const unhex = (h) => [1, 3, 5].map((i) => +(parseInt(h.substr(i, 2), 16) / 255).toFixed(3));

// one key per line, values compact
function stringifyParams(o) {
  const ks = Object.keys(o);
  return '{\n' + ks.map((k, i) => `  ${JSON.stringify(k)}: ${JSON.stringify(o[k]).replace(/,/g, ', ')}${i + 1 < ks.length ? ',' : ''}`).join('\n') + '\n}\n';
}

function editorOf(which) { return which === 'solver' ? edSolver : edVisual; }

function setKey(which, key, value) {
  const ed = editorOf(which);
  let o;
  try { o = ed.get().trim() ? JSON.parse(ed.get()) : {}; } catch (e) { o = null; }
  const note = $(which === 'solver' ? 'ctlSolver' : 'ctlVisual').querySelector('.ctlnote');
  if (!o || typeof o !== 'object' || Array.isArray(o)) { note.textContent = `${which}.json has a syntax error: fix it in Advanced: JSON first`; return; }
  note.textContent = '';
  o[key] = value;
  ed.set(stringifyParams(o));            // fires the editor change: rebuild (debounced) and control sync
}

function buildControls(which, doc) {
  const host = $(which === 'solver' ? 'ctlSolver' : 'ctlVisual');
  host.innerHTML = '<div class="ctlnote"></div>';
  let det = null, sec = null;
  for (const e of doc) {
    if (e.section !== sec || !det) {
      sec = e.section;
      const name = (SECTION_NAMES.find(([p]) => sec.startsWith(p)) || [null, sec || 'Other'])[1];
      det = document.createElement('details');
      det.open = OPEN_SECTIONS.includes(name);
      det.innerHTML = `<summary>${esc(name)}<span class="full">${esc(sec)}</span></summary>`;
      host.appendChild(det);
    }
    const row = document.createElement('div');
    row.className = 'ctl';
    const lab = document.createElement('label');
    lab.textContent = e.key;
    lab.title = `${e.key} (default ${Array.isArray(e.default) ? '[' + e.default.join(', ') + ']' : e.default}): ${e.doc}`;
    row.appendChild(lab);
    const c = { key: e.key, def: e.default, lab };
    if (e.type === 'boolean') {
      const cb = document.createElement('input'); cb.type = 'checkbox';
      cb.addEventListener('change', () => setKey(which, e.key, cb.checked));
      row.appendChild(cb); row.appendChild(document.createElement('span'));
      c.set = (v) => { cb.checked = !!v; };
    } else if (ENUMS[e.key] || e.type === 'string') {
      const sl = document.createElement('select');
      for (const o of ENUMS[e.key] || [e.default]) { const op = document.createElement('option'); op.value = op.textContent = o; sl.appendChild(op); }
      sl.addEventListener('change', () => setKey(which, e.key, sl.value));
      row.appendChild(sl); row.appendChild(document.createElement('span'));
      c.set = (v) => { sl.value = v; };
    } else if (e.type.startsWith('colour')) {
      const ci = document.createElement('input'); ci.type = 'color';
      const tx = document.createElement('span'); tx.className = 'note';
      ci.addEventListener('input', () => setKey(which, e.key, unhex(ci.value)));
      row.appendChild(ci); row.appendChild(tx);
      c.set = (v) => { if (Array.isArray(v) && v.length === 3) { ci.value = hex(v); tx.textContent = v.map((x) => (+x).toFixed(2)).join(' '); } };
    } else {
      const [lo, hi, scale] = rangeOf(e);
      const isInt = e.type === 'integer', log = scale === 'log';
      const sl = document.createElement('input'); sl.type = 'range';
      const nb = document.createElement('input'); nb.type = 'number'; nb.step = isInt ? '1' : 'any';
      if (log) { sl.min = 0; sl.max = 1000; sl.step = 1; }
      else { sl.min = lo; sl.max = hi; sl.step = isInt ? 1 : (hi - lo) / 500; }
      const toSlider = (v) => log ? 1000 * Math.log(Math.max(v, lo) / lo) / Math.log(hi / lo) : v;
      const fromSlider = (t) => {
        if (log) return round(lo * Math.pow(hi / lo, t / 1000));
        return isInt ? Math.round(t) : round(t);
      };
      sl.addEventListener('input', () => { const v = fromSlider(+sl.value); nb.value = v; setKey(which, e.key, v); });
      nb.addEventListener('change', () => {
        if (nb.value === '' || Number.isNaN(+nb.value)) return;
        const v = isInt ? Math.round(+nb.value) : +nb.value;
        sl.value = toSlider(v); setKey(which, e.key, v);
      });
      row.appendChild(sl); row.appendChild(nb);
      c.set = (v) => { if (typeof v === 'number') { sl.value = toSlider(v); nb.value = isInt ? v : round(v); } };
    }
    det.appendChild(row);
    paramCtls[which].push(c);
  }
}

function syncControls(which) {
  if (!paramCtls[which].length) return;
  let o;
  try { o = editorOf(which).get().trim() ? JSON.parse(editorOf(which).get()) : {}; } catch (e) { return; }
  if (!o || typeof o !== 'object') return;
  $(which === 'solver' ? 'ctlSolver' : 'ctlVisual').querySelector('.ctlnote').textContent = '';
  for (const c of paramCtls[which]) {
    const v = c.key in o ? o[c.key] : c.def;
    c.set(v);
    c.lab.classList.toggle('changed', JSON.stringify(v) !== JSON.stringify(c.def));
  }
}

function showTab(name) {
  document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  document.querySelectorAll('.tabpane').forEach((p) => p.classList.toggle('active', p.id === 'pane-' + name));
  localStorage.setItem(LS.tab, name);
  if (name === 'json') { edVisual.refresh(); edSolver.refresh(); }
}
document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));

// ------------------------------------------------------------------ help
function esc(s) { return String(s).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])); }
function keyTable(doc) {
  let h = '<table>', sec = null;
  for (const e of doc) {
    if (e.section !== sec) { sec = e.section; h += `<tr class="sec"><td colspan="3">${esc(sec)}</td></tr>`; }
    const def = Array.isArray(e.default) ? `[${e.default.join(', ')}]`
      : typeof e.default === 'number' ? String(+e.default.toPrecision(6)) : typeof e.default === 'string' ? `"${e.default}"` : String(e.default);
    h += `<tr><td>${esc(e.key)}</td><td>${esc(def)}</td><td>${esc(e.doc)}</td></tr>`;
  }
  return h + '</table>';
}

function helpHtml(doc, sdoc) {
  let h = `<p><b>layout.json</b>: <code>{"H": 40, "booms": [[10, "E", 16], ...]}</code>.
  <code>H</code> is the mast height above the core (4&ndash;100 blocks). Each boom is
  <code>[y, direction, length]</code>: attachment height <code>y</code> (2&ndash;H&minus;2), direction
  <code>E</code> (+x), <code>S</code> (+z), <code>W</code> (&minus;x) or <code>N</code> (&minus;z), and length
  2&ndash;(H&minus;y), at most one boom per height and direction. These are the game rules; layouts that break them
  are not built. Emitters sit at the boom tips and the mast top.</p>
  <p><b>q</b> (slider) is the material budget: 1 builds the stiffest library variant of every module, lower values
  swap in leaner variants (0.3 = all lean); the runtime pass then resizes members until every member is within its
  capacity. Tall towers with long booms can exceed the member catalogue: the stats then show a utilisation above 1.</p>
  <p><b>visual.json</b> sets the mesh proportions and colours (missing keys keep their defaults, sizes in blocks,
  colours <code>[r, g, b]</code> in 0..1). Compression struts are drawn in <code>colRust</code>, tension struts in
  <code>colSteel</code>. Keys:</p>` + keyTable(doc);
  h += `<p id="solverhelp"><b>solver.json</b> sets the runtime solver: fewer wires (stays on the booms, X cable
  panels in the mast), the back-stays, the pretension, the sizing loop, the loads of the runtime check and the member
  catalogue. The library modules stay as solved offline; the runtime pass then re-sizes every member for the changed
  tower. The defaults are the normal generator. Missing keys keep their defaults. A removal that would let the
  tower fold is undone member by member, and if the reduced tower cannot be sized within the catalogue the generator
  falls back to a smaller reduction or none; the warnings say what happened. Booms are flat trusses, so each boom
  node needs a stay of its own: <code>maxStaysPerBoom</code> below about one per boom node is not reachable. Keys:</p>`
    + keyTable(sdoc);
  return h + '<p>Source: the Stasis Delvers mod, <code>towergen</code> (pure Java), compiled with TeaVM.</p>';
}

// ------------------------------------------------------------------ start
T.t0 = performance.now();
worker.postMessage({ type: 'init' });
(async () => {
  const get = async (u) => { const r = await fetch(u); if (!r.ok) throw new Error(`${u}: HTTP ${r.status}`); return r.text(); };
  const [vis, doc, sol, sdoc, ...lays] = await Promise.all([get(v('data/visual-defaults.json')), get(v('data/visual-doc.json')),
    get(v('data/solver-defaults.json')), get(v('data/solver-doc.json')), ...PRESETS.map((p) => get(v(`layouts/${p}.json`)))]);
  defaults.visual = vis;
  defaults.solver = sol;
  PRESETS.forEach((p, i) => { defaults.layouts[p] = prettyLayout(lays[i]); });
  $('helptext').innerHTML = helpHtml(JSON.parse(doc), JSON.parse(sdoc));
  buildControls('solver', JSON.parse(sdoc));
  buildControls('visual', JSON.parse(doc));
  showTab(localStorage.getItem(LS.tab) || 'solver');
  const savedL = localStorage.getItem(LS.layout), savedV = localStorage.getItem(LS.visual), savedS = localStorage.getItem(LS.solver);
  sel.value = PRESETS.includes(state.preset) ? state.preset : 'custom';
  edLayout.set(savedL ?? defaults.layouts[PRESETS.includes(state.preset) ? state.preset : DEFAULT_PRESET]);
  edVisual.set(savedV ?? defaults.visual);
  edSolver.set(savedS ?? defaults.solver);
  syncControls('visual'); syncControls('solver');
  setQ(state.q);
  clearTimeout(timer);
  requestBuild();
})().catch((e) => { showErrors([String(e.message || e)], []); T.errors.push(String(e)); });

// one boom per line
function prettyLayout(text) {
  const l = JSON.parse(text);
  const b = (l.booms || []).map((x) => `    ${JSON.stringify(x).replace(/,/g, ', ')}`);
  return `{\n  "H": ${l.H},\n  "booms": [${b.length ? '\n' + b.join(',\n') + '\n  ' : ''}]\n}\n`;
}

// mark the preset as edited when the layout text no longer matches it
setInterval(() => {
  if (PRESETS.includes(state.preset) && defaults.layouts[state.preset] && edLayout.get() !== defaults.layouts[state.preset]) {
    state.preset = 'custom'; sel.value = 'custom';
  }
}, 1000);

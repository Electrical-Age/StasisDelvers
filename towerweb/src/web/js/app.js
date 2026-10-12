// Page logic: editors, 3D view (three.js), rebuilds in a Web Worker, localStorage, zip download.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { makeZip } from './zip.js';

const PRESETS = ['H16', 'H24b', 'H40', 'H40r3', 'H40b4', 'H64max', 'H100'];
const DEFAULT_PRESET = 'H40r3';
const LS = { layout: 'towerweb.layout', visual: 'towerweb.visual', q: 'towerweb.q', preset: 'towerweb.preset' };
const $ = (id) => document.getElementById(id);

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
  return { get: () => ta.value, set: (s) => { ta.value = s; }, markError: () => {} };
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
const defaults = { layouts: {}, visual: '' };
const worker = new Worker(new URL('./worker.js', import.meta.url), { type: 'module' });
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
  const lt = edLayout.get(), vt = edVisual.get();
  localStorage.setItem(LS.layout, lt);
  localStorage.setItem(LS.visual, vt);
  localStorage.setItem(LS.q, String(state.q));
  localStorage.setItem(LS.preset, state.preset);
  const pl = parseJson(lt, 'layout.json'), pv = vt.trim() ? parseJson(vt, 'visual.json') : { value: {} };
  edLayout.markError(pl.error ? pl.line : null);
  edVisual.markError(pv.error ? pv.line : null);
  const errs = [pl.error, pv.error].filter(Boolean);
  if (errs.length) { pending = null; showErrors(errs, []); setStatus('not rebuilt: fix the JSON'); return; }
  pending = { type: 'build', layout: lt, q: state.q, visual: vt };
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
  worker.postMessage({ type: m.type, id: m.id, layout: m.layout, q: m.q, visual: m.visual });
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
      showMesh(m);
      showStats(m.stats);
      showErrors([], m.stats.warnings);
      setStatus(statusNote + `built in ${m.stats.ms.total.toFixed(0)} ms (generator ${(m.stats.ms.statics + m.stats.ms.pass).toFixed(0)} ms, mesh ${m.stats.ms.mesh.toFixed(0)} ms; ${wall.toFixed(0)} ms incl. worker and upload)`);
      console.log(`[towerweb] built H${m.stats.H} q${m.stats.q}: ${m.stats.triangles} triangles, util ${m.stats.util?.toFixed(3)}, statics ${m.stats.ms.statics.toFixed(1)} ms, pass ${m.stats.ms.pass.toFixed(1)} ms, mesh ${m.stats.ms.mesh.toFixed(1)} ms, round trip ${wall.toFixed(0)} ms`);
    } else {
      T.errors.push(m.error);
      showErrors([m.error], []);
      setStatus('build failed (showing the last good tower)');
      console.log('[towerweb] error: ' + m.error);
    }
    pump();
  }
};

function fmt(x, d = 0) { return x == null || Number.isNaN(x) ? '–' : x.toFixed(d); }
function showStats(s) {
  const tiers = Object.entries(s.tiers).map(([k, v]) => `${k} ×${v}`).join(', ');
  const util = s.util == null ? '<span class="bad">mechanism</span>'
    : `<span class="${s.util > 1.001 ? 'bad' : ''}">${fmt(s.util, 3)}</span> (before resizing ${fmt(s.rawUtil, 2)})`;
  const rows = [
    ['Layout', `H ${s.H}, ${s.booms} boom${s.booms === 1 ? '' : 's'}, q ${fmt(s.q, 2)}`],
    ['Nodes / members', `${s.nodes} / ${s.members}`],
    ['Struts', `${s.struts} (${s.laced} laced)`],
    ['Cables', `${s.cables}${s.backstays ? `, ${s.backstays} back-stays` : ''}`],
    ['Max utilisation', util],
    ['Steel volume', `${fmt(s.volume, 1)} (raw ${fmt(s.volumeRaw, 1)}), ${s.rounds} resize rounds`],
    ['Library tiers', tiers],
    ['Triangles', `${s.triangles}`],
    ['Time', `statics+lookup+stitch ${fmt(s.ms.statics, 1)} ms, pass ${fmt(s.ms.pass, 1)} ms, mesh ${fmt(s.ms.mesh, 1)} ms`],
  ];
  $('stats').innerHTML = rows.map(([a, b]) => `<tr><td>${a}</td><td>${b}</td></tr>`).join('');
}

// ------------------------------------------------------------------ controls
const edLayout = makeEditor($('edLayout'), () => { statusNote = ''; scheduleBuild(); });
const edVisual = makeEditor($('edVisual'), () => { statusNote = ''; scheduleBuild(); });

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
const q = $('q');
function setQ(v) { state.q = v; q.value = v; $('qval').textContent = (+v).toFixed(2); }
q.addEventListener('input', () => { setQ(+q.value); scheduleBuild(150); });
$('zip').addEventListener('click', () => {
  const blob = makeZip([{ name: 'layout.json', data: edLayout.get() }, { name: 'visual.json', data: edVisual.get() }]);
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'tower-json.zip';
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
});

// ------------------------------------------------------------------ help
function esc(s) { return String(s).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])); }
function helpHtml(doc) {
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
  <code>colSteel</code>. Keys:</p><table>`;
  let sec = null;
  for (const e of doc) {
    if (e.section !== sec) { sec = e.section; h += `<tr class="sec"><td colspan="3">${esc(sec)}</td></tr>`; }
    const def = Array.isArray(e.default) ? `[${e.default.join(', ')}]` : String(e.default);
    h += `<tr><td>${esc(e.key)}</td><td>${esc(def)}</td><td>${esc(e.doc)}</td></tr>`;
  }
  return h + '</table><p>Source: the Stasis Delvers mod, <code>towergen</code> (pure Java), compiled with TeaVM.</p>';
}

// ------------------------------------------------------------------ start
T.t0 = performance.now();
worker.postMessage({ type: 'init' });
(async () => {
  const get = async (u) => { const r = await fetch(u); if (!r.ok) throw new Error(`${u}: HTTP ${r.status}`); return r.text(); };
  const [vis, doc, ...lays] = await Promise.all([get('data/visual-defaults.json'), get('data/visual-doc.json'),
    ...PRESETS.map((p) => get(`layouts/${p}.json`))]);
  defaults.visual = vis;
  PRESETS.forEach((p, i) => { defaults.layouts[p] = prettyLayout(lays[i]); });
  $('helptext').innerHTML = helpHtml(JSON.parse(doc));
  const savedL = localStorage.getItem(LS.layout), savedV = localStorage.getItem(LS.visual);
  sel.value = PRESETS.includes(state.preset) ? state.preset : 'custom';
  edLayout.set(savedL ?? defaults.layouts[PRESETS.includes(state.preset) ? state.preset : DEFAULT_PRESET]);
  edVisual.set(savedV ?? defaults.visual);
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

// Web Worker: owns the TeaVM-compiled generator (towergen.js) and the module library.
// Messages in:  {type: 'init'} | {type: 'build', id, layout, q, visual} | {type: 'random', id, seed}
// Messages out: {type: 'ready', ...timings} | {type: 'built', id, stats, positions, normals, groups} |
//               {type: 'error', id, error} | {type: 'random', id, layout}
import * as tg from './towergen.js';

let ready = false;

async function init() {
  const t0 = performance.now();
  const resp = await fetch('../data/towerlib.bin');
  if (!resp.ok) throw new Error(`library download failed: HTTP ${resp.status}`);
  let buf = new Uint8Array(await resp.arrayBuffer());
  const t1 = performance.now();
  const downloaded = buf.length;
  // the file is gzipped; a server may already have undone that (Content-Encoding), so sniff the magic
  if (buf[0] === 0x1f && buf[1] === 0x8b) {
    const ds = new Blob([buf]).stream().pipeThrough(new DecompressionStream('gzip'));
    buf = new Uint8Array(await new Response(ds).arrayBuffer());
  }
  const t2 = performance.now();
  const r = JSON.parse(tg.loadLibrary(new Int8Array(buf.buffer, buf.byteOffset, buf.length)));
  const t3 = performance.now();
  if (r.error) throw new Error('library: ' + r.error);
  ready = true;
  postMessage({ type: 'ready', entries: r.entries, bytes: downloaded, rawBytes: buf.length,
                fetchMs: t1 - t0, inflateMs: t2 - t1, parseMs: t3 - t2, totalMs: t3 - t0 });
}

// Sort triangles by part (one draw group per part) and expand the per-triangle normals to the vertices.
function pack(pos, nrm, parts) {
  const tris = parts.length, nParts = 16;
  const count = new Int32Array(nParts);
  for (let t = 0; t < tris; t++) count[parts[t]]++;
  const start = new Int32Array(nParts);
  for (let p = 1; p < nParts; p++) start[p] = start[p - 1] + count[p - 1];
  const fill = start.slice();
  const P = new Float32Array(9 * tris), N = new Float32Array(9 * tris);
  for (let t = 0; t < tris; t++) {
    const d = fill[parts[t]]++;
    P.set(pos.subarray(9 * t, 9 * t + 9), 9 * d);
    const nx = nrm[3 * t], ny = nrm[3 * t + 1], nz = nrm[3 * t + 2];
    for (let k = 0; k < 3; k++) { N[9 * d + 3 * k] = nx; N[9 * d + 3 * k + 1] = ny; N[9 * d + 3 * k + 2] = nz; }
  }
  const groups = [];
  for (let p = 0; p < nParts; p++) if (count[p]) groups.push({ part: p, start: 3 * start[p], count: 3 * count[p] });
  return { P, N, groups };
}

onmessage = (e) => {
  const m = e.data;
  try {
    if (m.type === 'init') {
      init().catch((err) => postMessage({ type: 'fatal', error: String(err && err.message || err) }));
    } else if (m.type === 'random') {
      postMessage({ type: 'random', id: m.id, layout: tg.randomLayout(m.seed) });
    } else if (m.type === 'build') {
      if (!ready) { postMessage({ type: 'error', id: m.id, error: 'library not loaded yet' }); return; }
      const t0 = performance.now();
      const stats = JSON.parse(tg.build(m.layout, m.q, m.visual));
      if (stats.error) { postMessage({ type: 'error', id: m.id, error: stats.error }); return; }
      const t1 = performance.now();
      const { P, N, groups } = pack(tg.positions(), tg.normals(), tg.parts());
      const t2 = performance.now();
      stats.ms.worker = t1 - t0;
      stats.ms.pack = t2 - t1;
      postMessage({ type: 'built', id: m.id, stats, positions: P, normals: N, groups }, [P.buffer, N.buffer]);
    }
  } catch (err) {
    postMessage({ type: 'error', id: m.id, error: 'internal: ' + String(err && err.message || err) });
  }
};

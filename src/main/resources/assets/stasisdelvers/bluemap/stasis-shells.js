// Stasis field shells for BlueMap's web app (Baughn 2026-11-04, Discord 1567602802908209393: "rendered similarly to
// in-game, a spheroid with hexagons"). BlueMapOverlay writes this file into BlueMap's web root and registers it.
// Each field marker of the "stasisdelvers" marker set carries its shape in a hidden span of its detail
// (data-shell = x,y,z,r,e,s,w,n,column,amber,dropout,collapse,side,rate). For each one this draws a translucent shell:
// the field's ellipsoid in quarters (Field.Stretch) with the in-game hexagon grid (stasis.fsh hexCell / hexColumn /
// shieldAt, simplified: no start-up pop, no weather). Shells are children of the marker set, so its toggle hides them.
(function () {
    "use strict";
    const BM = window.BlueMap, app = window.bluemap;
    if (!BM || !app || !BM.Three) return;
    const T = BM.Three;
    const SET_ID = "stasisdelvers";

    const GLSL_COMMON = `
float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}
const vec3 ICO[12] = vec3[12](
    vec3(-0.5257311, 0.8506508, 0.0), vec3(0.5257311, 0.8506508, 0.0), vec3(-0.5257311, -0.8506508, 0.0), vec3(0.5257311, -0.8506508, 0.0),
    vec3(0.0, -0.5257311, 0.8506508), vec3(0.0, 0.5257311, 0.8506508), vec3(0.0, -0.5257311, -0.8506508), vec3(0.0, 0.5257311, -0.8506508),
    vec3(0.8506508, 0.0, -0.5257311), vec3(0.8506508, 0.0, 0.5257311), vec3(-0.8506508, 0.0, -0.5257311), vec3(-0.8506508, 0.0, 0.5257311));
const ivec3 FACES[20] = ivec3[20](
    ivec3(0, 11, 5), ivec3(0, 5, 1), ivec3(0, 1, 7), ivec3(0, 7, 10), ivec3(0, 10, 11), ivec3(1, 5, 9), ivec3(5, 11, 4), ivec3(11, 10, 2),
    ivec3(10, 7, 6), ivec3(7, 1, 8), ivec3(3, 9, 4), ivec3(3, 4, 2), ivec3(3, 2, 6), ivec3(3, 6, 8), ivec3(3, 8, 9), ivec3(4, 9, 5),
    ivec3(2, 4, 11), ivec3(6, 2, 10), ivec3(8, 6, 7), ivec3(9, 8, 1));
vec2 hexCell(vec3 n, float f, out vec3 centre) {
    int best = 0;
    float bd = -2.0;
    for (int i = 0; i < 20; i++) {
        vec3 c = normalize(ICO[FACES[i].x] + ICO[FACES[i].y] + ICO[FACES[i].z]);
        float d = dot(n, c);
        if (d > bd) { bd = d; best = i; }
    }
    vec3 A = ICO[FACES[best].x], B = ICO[FACES[best].y], C = ICO[FACES[best].z];
    vec3 cn = normalize(A + B + C);
    vec3 p = n * (dot(A, cn) / dot(n, cn));
    vec3 v0 = B - A, v1 = C - A, v2 = p - A;
    float d00 = dot(v0, v0), d01 = dot(v0, v1), d11 = dot(v1, v1), d20 = dot(v2, v0), d21 = dot(v2, v1);
    float den = d00 * d11 - d01 * d01;
    float wb = (d11 * d20 - d01 * d21) / den, wc = (d00 * d21 - d01 * d20) / den;
    float gi = wb * f, gj = wc * f;
    float fi = floor(gi), fj = floor(gj);
    float d1 = 9.0, d2 = 9.0;
    vec3 q1 = n;
    for (int a = -1; a <= 2; a++)
        for (int b = -1; b <= 2; b++) {
            float i = fi + float(a), j = fj + float(b);
            vec3 q = normalize(A * (f - i - j) + B * i + C * j);
            float d = acos(clamp(dot(q, n), -1.0, 1.0));
            if (d < d1) { d2 = d1; d1 = d; q1 = q; } else if (d < d2) { d2 = d; }
        }
    centre = q1;
    return vec2(d2 - d1, hash13(floor(q1 * 2048.0)));
}
vec2 hexColumn(vec3 d, float worldY, float r) {
    float circ = 6.2831853 * r;
    float cols = max(6.0, floor(circ / (3.2 * 1.7320508) + 0.5));
    float w = circ / cols, sz = w / 1.7320508, rowH = 1.5 * sz;
    float u = (atan(d.z, d.x) + 3.1415927) * r, v = worldY;
    float r0 = floor(v / rowH);
    float d1 = 99.0, d2 = 99.0;
    vec2 best = vec2(0.0);
    for (int a = -1; a <= 1; a++) {
        float row = r0 + float(a);
        float off = mod(row, 2.0) * 0.5 * w;
        float c0 = floor((u - off) / w);
        for (int b = -1; b <= 1; b++) {
            float col = c0 + float(b);
            vec2 ctr = vec2(off + col * w, row * rowH);
            float dd = length(vec2(u, v) - ctr);
            if (dd < d1) { d2 = d1; d1 = dd; best = vec2(mod(col, cols), row); } else if (dd < d2) { d2 = dd; }
        }
    }
    return vec2(d2 - d1, hash13(vec3(best, 7.0)));
}
`;

    // (BlueMap's renderer uses a logarithmic depth buffer: without three's logdepthbuf chunks the shells' depth doesn't
    // match the terrain's and they vanish behind it)
    const VERT = `
#include <common>
#include <logdepthbuf_pars_vertex>
uniform vec3 centre;
uniform float radius;
uniform vec4 stretch;    // e, s, w, n
uniform float column;
uniform float yMin, yMax;
varying vec3 vDir;       // the unstretched unit direction (the cell grid's)
varying vec3 vWorld;
varying vec3 vNormal;
void main() {
    vDir = position;
    vec3 p;
    if (column > 0.5) {
        p = vec3(position.x * radius, mix(yMin, yMax, position.y * 0.5 + 0.5), position.z * radius) + vec3(centre.x, 0.0, centre.z);
        vNormal = normalize(vec3(position.x, 0.0, position.z));
    } else {
        vec3 k = vec3(position.x > 0.0 ? stretch.x : stretch.z, 1.0, position.z > 0.0 ? stretch.y : stretch.w);
        p = position * k * radius + centre;
        vNormal = normalize(position / k);
    }
    vWorld = p;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
    #include <logdepthbuf_vertex>
}
`;

    const FRAG = "#include <logdepthbuf_pars_fragment>\n" + GLSL_COMMON + `
uniform float radius, column, time, amber, dropout, collapse, side, rate;
varying vec3 vDir;
varying vec3 vWorld;
varying vec3 vNormal;
void main() {
    #include <logdepthbuf_fragment>
    float r = radius;
    vec3 n = normalize(vDir);
    vec3 cc = n;
    vec2 cell;
    if (column > 0.5) cell = hexColumn(normalize(vec3(n.x, 0.0, n.z)), vWorld.y, r);
    else {
        float f = max(4.0, floor(r / 2.3 + 0.5));
        cell = hexCell(n, f, cc) * vec2(r, 1.0);
    }
    float t = rate > 1.0 ? time * rate : time;
    float edge = cell.x, rnd = cell.y;
    vec3 view = normalize(cameraPosition - vWorld);
    float wave = 0.5 + 0.5 * sin(t * 1.3 - cc.y * r * 0.18 + rnd * 1.5);
    float fres = pow(1.0 - abs(dot(normalize(vNormal), view)), 2.0);
    float rim = 1.0 - smoothstep(0.12, 0.45, edge);
    float gap = 1.0 - smoothstep(0.0, 0.08, edge);
    // (a little more body than in game: there it is drawn over the world's own colours, here over map tiles from afar)
    float a = 0.05 + 0.10 * fres + rim * (0.20 + 0.25 * wave);
    a *= 1.0 - 0.8 * gap;
    if (!gl_FrontFacing) a *= 0.5;
    float warm = collapse > 0.0 ? 1.0 : 0.0;
    vec3 tint = mix(vec3(0.62, 0.84, 1.0), vec3(1.0, 0.55, 0.2), warm);
    float lost = dropout;
    if (collapse > 0.0) lost += collapse * (1.0 + 0.9 * dot(cc, vec3(cos(side), 0.0, sin(side))));
    float band = clamp((amber - rnd) / 0.03, 0.0, 1.0);
    tint = mix(tint, vec3(1.0, 0.55, 0.2), max(warm, floor(band * 3.0 + 0.999) / 3.0));
    float fl = (lost - rnd) / 0.02;
    if (lost > 0.0 && fl > -0.2) {
        if (fl >= 1.0) discard;
        if (fl < 0.0) {
            if (hash13(vec3(rnd * 97.0, floor(t * 14.0), 3.3)) < 0.5) a *= 0.12;
            else tint = mix(tint, vec3(1.0, 0.92, 0.75), 0.6);
        } else {
            if (edge < fl * 2.5) discard;
            a *= 1.0 - 0.6 * fl;
        }
    }
    vec3 col = tint * (0.8 + 0.4 * wave);
    if (rate > 1.0) { col = mix(col, vec3(1.0, 0.78, 0.25), 0.75 + 0.2 * clamp((rate - 1.0) / 9.0, 0.0, 1.0)); a = min(0.9, a * 1.4); }
    gl_FragColor = vec4(col, clamp(a, 0.0, 0.9));
}
`;

    const sphereGeo = new T.SphereGeometry(1, 128, 96);
    // a unit cylinder: x, z on the circle, y from -1 to 1 (the vertex shader maps it to yMin..yMax)
    const cylGeo = new T.CylinderGeometry(1, 1, 2, 128, 48, true);
    const start = performance.now();
    const shells = new WeakMap();   // marker set -> {group, meshes: Map(id -> mesh)}

    function findSet() {
        let found = null;
        app.mapViewer.markers.traverse(o => {
            if (!found && o.isMarkerSet && o.data && o.data.id === SET_ID) found = o;
        });
        return found;
    }

    function parse(marker) {
        const d = marker && marker.data && marker.data.detail;
        if (!d) return null;
        const m = /data-shell="([^"]+)"/.exec(d);
        if (!m) return null;
        const v = m[1].split(",").map(Number);
        if (v.length < 14 || v.some(isNaN)) return null;
        return {x: v[0], y: v[1], z: v[2], r: v[3], e: v[4], s: v[5], w: v[6], n: v[7], column: v[8], amber: v[9],
            dropout: v[10], collapse: v[11], side: v[12], rate: v[13]};
    }

    function makeMesh(f) {
        const mat = new T.ShaderMaterial({
            vertexShader: VERT, fragmentShader: FRAG,
            uniforms: {
                centre: {value: new T.Vector3()}, radius: {value: 1}, stretch: {value: new T.Vector4(1, 1, 1, 1)},
                column: {value: 0}, yMin: {value: -64}, yMax: {value: 320}, time: {value: 0}, amber: {value: 0},
                dropout: {value: 0}, collapse: {value: 0}, side: {value: 0}, rate: {value: 1}
            },
            transparent: true, depthWrite: false, side: T.DoubleSide
        });
        const mesh = new T.Mesh(f.column ? cylGeo : sphereGeo, mat);
        mesh.frustumCulled = false;      // (the vertex shader places it)
        mesh.raycast = function () {};   // clicks go through to the map and the field's own marker
        mesh.renderOrder = 10;
        return mesh;
    }


    // ---- stopped land on the terrain (λ-Nero: the flat grey polygons floated at sea level in 16-block steps) ----
    // BlueMap's hires and lowres terrain shaders get a few lines at the end of main(): where the server's mask (one pixel
    // per 8 blocks, the marker "mask-land" says where) is set, the terrain is desaturated as in game, smoothly over a few
    // blocks, with a pale border where the stillness ends; inside a field's ellipsoid (3D, so a mountain poking out of a
    // field is stopped) it isn't. The flat polygons are then hidden (they stay for browsers where this fails).
    const MAX_FIELDS = 32;
    const landUniforms = {
        stasisMask: {value: null},
        stasisMaskRect: {value: new T.Vector4(0, 0, 1, 1)},   // x0, z0, width, height (blocks)
        stasisShift: {value: new T.Vector2(0, 0)},
        stasisOn: {value: 0},
        stasisTime: {value: 0},
        stasisFieldCount: {value: 0},
        stasisFieldPos: {value: Array.from({length: MAX_FIELDS}, () => new T.Vector4())},     // x, y, z, r
        stasisFieldStretch: {value: Array.from({length: MAX_FIELDS}, () => new T.Vector4(1, 1, 1, 1))}
    };
    const LAND_GLSL = `
uniform sampler2D stasisMask;
uniform vec4 stasisMaskRect;
uniform vec2 stasisShift;
uniform float stasisOn;
uniform float stasisTime;
uniform int stasisFieldCount;
uniform vec4 stasisFieldPos[${MAX_FIELDS}];
uniform vec4 stasisFieldStretch[${MAX_FIELDS}];
vec3 stasisLand(vec3 c, vec3 wp) {
    if (stasisOn < 0.5) return c;
    vec3 w = wp + vec3(stasisShift.x, 0.0, stasisShift.y);
    vec2 uv = (w.xz - stasisMaskRect.xy) / stasisMaskRect.zw;
    float m = texture(stasisMask, clamp(uv, 0.0, 1.0)).r;
    for (int i = 0; i < ${MAX_FIELDS}; i++) {
        if (i >= stasisFieldCount) break;
        vec4 f = stasisFieldPos[i];
        vec4 st = stasisFieldStretch[i];
        vec3 d = w - f.xyz;
        if (f.w < 0.0) { d.y = 0.0; f.w = -f.w; }
        d.x /= d.x > 0.0 ? st.x : st.z;
        d.z /= d.z > 0.0 ? st.y : st.w;
        if (dot(d, d) < f.w * f.w) return c;   // inside a field: time runs
    }
    float stopped = smoothstep(0.3, 0.7, m);
    float grey = dot(c, vec3(0.299, 0.587, 0.114));
    vec3 still = mix(c, vec3(grey) * vec3(0.9, 0.93, 1.0), 0.85) * 0.92;
    c = mix(c, still, stopped);
    // the border: a pale violet band a few blocks wide where the mask crosses one half, shimmering slowly
    float edge = 1.0 - smoothstep(0.0, 0.2, abs(m - 0.5));
    float shimmer = 0.7 + 0.3 * sin(stasisTime * 1.7 + (w.x + w.z) * 0.15);
    return mix(c, vec3(0.88, 0.8, 1.0), edge * 0.8 * shimmer);
}
`;
    function patchSource(src) {
        const close = src.lastIndexOf("}");
        const main = src.indexOf("void main");
        if (close < 0 || main < 0 || src.indexOf("vWorldPosition") < 0 || src.indexOf("stasisLand") >= 0) return null;
        src = src.slice(0, close) + "\n    gl_FragColor.rgb = stasisLand(gl_FragColor.rgb, vWorldPosition);\n" + src.slice(close);
        return src.slice(0, main) + LAND_GLSL + src.slice(main);
    }
    function patchMaterial(mat) {
        if (!mat || !mat.isShaderMaterial || mat.userData.stasisLand) return;
        const src = patchSource(mat.fragmentShader);
        mat.userData.stasisLand = true;
        if (!src) return;
        mat.fragmentShader = src;
        Object.assign(mat.uniforms, landUniforms);
        mat.needsUpdate = true;
    }
    // Hires tiles share the map's materials; each lowres tile makes its own from its loader's shader and uniforms, so
    // the loaders are patched for new tiles and the loaded tiles' materials one by one.
    function patchMap(map) {
        if (map.hiresMaterial) map.hiresMaterial.forEach(patchMaterial);
        patchMaterial(map.lowresMaterial);
        (map.lowresTileManager || []).forEach(tm => {
            const l = tm && tm.tileLoader;
            if (l && !l.stasisLand && typeof l.fragmentShader === "string") {
                const src = patchSource(l.fragmentShader);
                if (src) {
                    l.fragmentShader = src;
                    Object.assign(l.uniforms, landUniforms);
                }
                l.stasisLand = true;
            }
            if (tm && tm.scene) tm.scene.traverse(o => { if (o.isMesh) patchMaterial(o.material); });
        });
    }
    let maskUrl = null;
    function updateLand(set) {
        const map = app.mapViewer.map;
        if (map) patchMap(map);
        const mk = set && set.markers.get("mask-land");
        const html = mk && (mk.html || (mk.data && mk.data.html)) || "";
        const m = /data-stasis-mask="([^"]+)"/.exec(html);
        if (!m) { landUniforms.stasisOn.value = 0; return; }
        const v = m[1].split(",");
        const url = v[0];
        landUniforms.stasisMaskRect.value.set(+v[1], +v[2], +v[3] * +v[4], +v[3] * +v[5]);
        if (url !== maskUrl) {
            maskUrl = url;
            new T.TextureLoader().load(url, tex => {
                if (maskUrl !== url) { tex.dispose(); return; }
                tex.magFilter = T.LinearFilter;
                tex.minFilter = T.LinearMipmapLinearFilter;
                tex.generateMipmaps = true;
                tex.flipY = false;
                tex.wrapS = tex.wrapT = T.ClampToEdgeWrapping;
                tex.needsUpdate = true;
                const old = landUniforms.stasisMask.value;
                landUniforms.stasisMask.value = tex;
                landUniforms.stasisOn.value = 1;
                if (old) old.dispose();
            });
        }
        // the flat polygons only where the terrain tint isn't working
        const on = landUniforms.stasisOn.value > 0.5;
        set.markers.forEach((marker, id) => { if (id.startsWith("land-")) marker.visible = !on; });
    }
    function updateFieldUniforms(fields) {
        const n = Math.min(MAX_FIELDS, fields.length);
        landUniforms.stasisFieldCount.value = n;
        for (let i = 0; i < n; i++) {
            const f = fields[i];
            landUniforms.stasisFieldPos.value[i].set(f.x, f.y, f.z, f.column ? -f.r : f.r);   // (negative: a column, no height limit)
            landUniforms.stasisFieldStretch.value[i].set(f.e, f.s, f.w, f.n);
        }
    }

    function update() {
        const set = findSet();
        if (!set) return;
        let s = shells.get(set);
        if (!s) {
            s = {group: new T.Group(), meshes: new Map()};
            s.group.name = "stasis-shells";
            set.add(s.group);
            shells.set(set, s);
        }
        const seen = new Set(), fields = [];
        set.markers.forEach((marker, id) => {
            const f = parse(marker);
            if (!f) return;
            seen.add(id);
            fields.push(f);
            let mesh = s.meshes.get(id);
            if (mesh && (mesh.geometry === cylGeo) !== !!f.column) {
                s.group.remove(mesh);
                mesh.material.dispose();
                mesh = null;
            }
            if (!mesh) {
                mesh = makeMesh(f);
                s.meshes.set(id, mesh);
                s.group.add(mesh);
            }
            const u = mesh.material.uniforms;
            u.centre.value.set(f.x, f.y, f.z);
            u.radius.value = f.r;
            u.stretch.value.set(f.e, f.s, f.w, f.n);
            u.column.value = f.column;
            u.amber.value = f.amber;
            u.dropout.value = f.dropout;
            u.collapse.value = f.collapse;
            u.side.value = f.side;
            u.rate.value = f.rate;
        });
        updateFieldUniforms(fields);
        updateLand(set);
        s.meshes.forEach((mesh, id) => {
            if (!seen.has(id)) {
                s.group.remove(mesh);
                mesh.material.dispose();
                s.meshes.delete(id);
            }
        });
    }

    function tick() {
        const t = (performance.now() - start) / 1000;
        landUniforms.stasisTime.value = t;
        // the map's scene is shifted towards 0,0 by multiples of 10000 for precision (MapViewer.render); undo it here
        const cam = app.mapViewer.camera.position;
        landUniforms.stasisShift.value.set(Math.round(cam.x / 10000) * 10000, Math.round(cam.z / 10000) * 10000);
        const set = findSet();
        const s = set && shells.get(set);
        if (s) s.meshes.forEach(m => m.material.uniforms.time.value = t);
    }

    window.StasisShells = {VERT, FRAG, update};   // (for debugging from the console)
    setInterval(update, 2000);
    update();
    app.events.addEventListener("bluemapRenderFrame", tick);
    // (BlueMap renders at least every 50 ms when idle, enough for the shimmer; no extra redraws)
})();

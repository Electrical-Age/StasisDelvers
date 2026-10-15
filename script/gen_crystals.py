#!/usr/bin/env python3
"""Rift crystals (Baughn: amethyst-like regrowth, a procedural violet crystal with dark spots inside).

Generates, deterministically from a seed:
  - the crystal texture (violet with darker streaks and black-violet inclusions, bright facet rims), 32x32;
  - four growth stages (small, medium, large bud, cluster): clusters of faceted shards (hexagonal prisms with
    pyramid tips, tapered, jittered), as OBJ + MTL in block space (0..1, base at y = 0, growing up +y),
    for NeoForge's obj loader (the block rotates it to the face it grows on);
  - a preview sheet (software z-buffer render, flat-shaded with the texture) of all stages.
Usage: gen_crystals.py OUTDIR [seed]"""
import math
import os
import sys

import numpy as np
from PIL import Image

TEX = 32


def texture(rng):
    """Violet crystal: a vertical gradient (dark base, bright tip), streaks along the growth axis, inclusions."""
    v = np.linspace(0, 1, TEX)[:, None] * np.ones((1, TEX))          # 0 bottom row .. 1 top (we flip at the end)
    u = np.ones((TEX, 1)) * np.linspace(0, 1, TEX)[None, :]
    base = np.array([0.30, 0.12, 0.48])
    tip = np.array([0.78, 0.55, 0.98])
    t = v ** 0.8
    img = base[None, None, :] * (1 - t[..., None]) + tip[None, None, :] * t[..., None]
    # streaks along the axis
    streak = np.zeros(TEX)
    for _ in range(5):
        c, w, a = rng.uniform(0, TEX), rng.uniform(0.6, 2.0), rng.uniform(-0.12, 0.12)
        x = np.arange(TEX)
        streak += a * np.exp(-((x - c + TEX / 2) % TEX - TEX / 2) ** 2 / (2 * w * w))
    img *= (1 + streak)[None, :, None]
    # inclusions: dark spots, black-violet cores with a soft violet halo
    for _ in range(int(rng.integers(16, 22))):
        cx, cy = rng.uniform(0, TEX), rng.uniform(TEX * 0.08, TEX * 0.8)
        r = rng.uniform(0.7, 1.5)
        yy, xx = np.mgrid[0:TEX, 0:TEX]
        dx = (xx - cx + TEX / 2) % TEX - TEX / 2
        d = np.sqrt(dx ** 2 + ((yy - cy) * 1.4) ** 2)
        core = np.clip(1 - d / r, 0, 1) ** 0.6
        img = img * (1 - 0.92 * core[..., None]) + np.array([0.05, 0.0, 0.08]) * 0.92 * core[..., None]
    # a dark vein up the middle of each facet (the 'black' of the theme), fading towards the tip
    fu = (u * 6) % 1
    vein = np.exp(-((fu - 0.5) / 0.09) ** 2) * (1 - t) ** 1.5 * 0.55
    img = img * (1 - vein[..., None])
    alpha = np.full((TEX, TEX), 0.82)
    out = np.concatenate([np.clip(img, 0, 1), alpha[..., None]], -1)
    return out[::-1]   # row 0 = top (v = 1)


def shard(rng, origin, axis, length, radius, sides=6):
    """A shard: tapered prism (base at origin) with a pyramid tip. Returns (verts, uvs, faces) with faces as
    triangles of (vertex index, uv index)."""
    axis = axis / np.linalg.norm(axis)
    a = np.cross(axis, [0.3, 0.1, 1.0])
    a /= np.linalg.norm(a)
    b = np.cross(axis, a)
    tip_len = length * rng.uniform(0.22, 0.32)
    body = length - tip_len
    rot = rng.uniform(0, 2 * math.pi)
    jit = rng.uniform(0.85, 1.15, sides)
    verts, uvs, faces = [], [], []
    ring0, ring1 = [], []
    for i in range(sides):
        ang = rot + 2 * math.pi * i / sides
        d = (math.cos(ang) * a + math.sin(ang) * b) * jit[i]
        ring0.append(origin + d * radius)
        ring1.append(origin + axis * body + d * radius * rng.uniform(0.82, 0.95))
    tip = origin + axis * length + (a * rng.normal() + b * rng.normal()) * radius * 0.12
    verts = ring0 + ring1 + [tip]
    n = sides
    for i in range(sides):
        j = (i + 1) % sides
        u0, u1 = i / sides, (i + 1) / sides
        k = len(uvs)
        uvs += [(u0, 0.0), (u1, 0.0), (u1, 0.72), (u0, 0.72), ((u0 + u1) / 2, 1.0)]
        faces.append(((i, k), (j, k + 1), (n + j, k + 2)))
        faces.append(((i, k), (n + j, k + 2), (n + i, k + 3)))
        faces.append(((n + i, k + 3), (n + j, k + 2), (2 * n, k + 4)))
    return np.array(verts), uvs, faces


STAGES = {
    # name: (shard count range, length range in blocks, radius range)
    'small': ((1, 2), (0.2, 0.3), (0.04, 0.055)),
    'medium': ((2, 3), (0.28, 0.42), (0.045, 0.065)),
    'large': ((3, 4), (0.42, 0.62), (0.055, 0.08)),
    'cluster': ((5, 7), (0.45, 0.86), (0.06, 0.095)),
}


def stage_mesh(rng, name):
    (n0, n1), (l0, l1), (r0, r1) = STAGES[name]
    n = int(rng.integers(n0, n1 + 1))
    V, UV, F = [], [], []
    for s in range(n):
        if s == 0:
            origin, tilt = np.array([0.5, 0.0, 0.5]), 0.12
        else:
            ang = rng.uniform(0, 2 * math.pi)
            rr = rng.uniform(0.08, 0.24)
            origin, tilt = np.array([0.5 + rr * math.cos(ang), 0.0, 0.5 + rr * math.sin(ang)]), rng.uniform(0.25, 0.55)
        dirxz = origin[[0, 2]] - 0.5
        if np.linalg.norm(dirxz) < 1e-6:
            dirxz = np.array([math.cos(rng.uniform(0, 6.3)), math.sin(rng.uniform(0, 6.3))])
        dirxz = dirxz / np.linalg.norm(dirxz)
        axis = np.array([dirxz[0] * math.sin(tilt), math.cos(tilt), dirxz[1] * math.sin(tilt)])
        length = rng.uniform(l0, l1) * (1.0 if s == 0 else rng.uniform(0.6, 0.9))
        v, uv, f = shard(rng, origin - axis * 0.03, axis, length, rng.uniform(r0, r1))
        vo, uo = len(V), len(UV)
        V += list(v)
        UV += uv
        F += [tuple((a + vo, b + uo) for a, b in tri) for tri in f]
    V = np.array(V)
    V[:, [0, 2]] = np.clip(V[:, [0, 2]], 0.02, 0.98)
    V[:, 1] = np.clip(V[:, 1], 0.0, 0.98)
    return V, UV, F


def write_obj(path, name, V, UV, F):
    with open(path, 'w') as f:
        f.write(f'# rift crystal {name} (script/gen_crystals.py)\nmtllib rift_crystal.mtl\nusemtl crystal\n')
        for v in V:
            f.write(f'v {v[0]:.5f} {v[1]:.5f} {v[2]:.5f}\n')
        for uv in UV:
            f.write(f'vt {uv[0]:.5f} {uv[1]:.5f}\n')
        for tri in F:
            f.write('f ' + ' '.join(f'{a + 1}/{b + 1}' for a, b in tri) + '\n')


def render(V, UV, F, tex, size=256, yaw=0.6, pitch=0.45):
    """Flat-shaded, textured, z-buffered render of a mesh (block space) on a dark background."""
    cy, sy, cp, sp = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)
    P = V - np.array([0.5, 0.4, 0.5])
    x = P[:, 0] * cy - P[:, 2] * sy
    z = P[:, 0] * sy + P[:, 2] * cy
    y = P[:, 1] * cp - z * sp
    z = P[:, 1] * sp + z * cp
    S = np.stack([x * size * 0.9 + size / 2, size / 2 - y * size * 0.9, z], -1)
    img = np.zeros((size, size, 3)) + np.array([0.13, 0.12, 0.15])
    zb = np.full((size, size), -1e9)
    light = np.array([0.4, 0.8, 0.45])
    light /= np.linalg.norm(light)
    th, tw = tex.shape[:2]
    for tri in F:
        vi = [a for a, _ in tri]
        ui = [b for _, b in tri]
        p = S[vi]
        n = np.cross(V[vi[1]] - V[vi[0]], V[vi[2]] - V[vi[0]])
        if np.linalg.norm(n) < 1e-12:
            continue
        n /= np.linalg.norm(n)
        shade = 0.45 + 0.55 * abs(np.dot(n, light))
        x0, x1 = int(max(0, p[:, 0].min())), int(min(size - 1, p[:, 0].max() + 1))
        y0, y1 = int(max(0, p[:, 1].min())), int(min(size - 1, p[:, 1].max() + 1))
        if x1 < x0 or y1 < y0:
            continue
        yy, xx = np.mgrid[y0:y1 + 1, x0:x1 + 1] + 0.5
        d = (p[1, 1] - p[2, 1]) * (p[0, 0] - p[2, 0]) + (p[2, 0] - p[1, 0]) * (p[0, 1] - p[2, 1])
        if abs(d) < 1e-9:
            continue
        w0 = ((p[1, 1] - p[2, 1]) * (xx - p[2, 0]) + (p[2, 0] - p[1, 0]) * (yy - p[2, 1])) / d
        w1 = ((p[2, 1] - p[0, 1]) * (xx - p[2, 0]) + (p[0, 0] - p[2, 0]) * (yy - p[2, 1])) / d
        w2 = 1 - w0 - w1
        m = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
        if not m.any():
            continue
        zz = w0 * p[0, 2] + w1 * p[1, 2] + w2 * p[2, 2]
        sub = zb[y0:y1 + 1, x0:x1 + 1]
        m &= zz > sub
        uv = np.array([UV[k] for k in ui])
        u = w0 * uv[0, 0] + w1 * uv[1, 0] + w2 * uv[2, 0]
        v = w0 * uv[0, 1] + w1 * uv[1, 1] + w2 * uv[2, 1]
        tx = np.clip((u * tw).astype(int), 0, tw - 1)
        ty = np.clip(((1 - v) * th).astype(int), 0, th - 1)
        col = tex[ty, tx, :3] * shade
        # bright rims on the facet edges (not on the triangles' diagonals): u near a facet boundary, or the tip line
        fu = (u * 6) % 1
        rim = (np.minimum(fu, 1 - fu) < 0.07) | (np.abs(v - 0.72) < 0.02)
        col = col + rim[..., None] * 0.16
        reg = img[y0:y1 + 1, x0:x1 + 1]
        reg[m] = col[m]
        sub[m] = zz[m]
    return np.clip(img, 0, 1)


def main():
    out = sys.argv[1]
    seed = int(sys.argv[2]) if len(sys.argv) > 2 else 0x41F7
    os.makedirs(out, exist_ok=True)
    rng = np.random.default_rng(seed)
    tex = texture(rng)
    Image.fromarray((tex * 255).astype(np.uint8), 'RGBA').save(os.path.join(out, 'rift_crystal.png'))
    with open(os.path.join(out, 'rift_crystal.mtl'), 'w') as f:
        f.write('newmtl crystal\nmap_Kd #crystal\n')
    tiles = []
    for name in STAGES:
        V, UV, F = stage_mesh(np.random.default_rng([seed, len(name), ord(name[0])]), name)
        write_obj(os.path.join(out, f'rift_crystal_{name}.obj'), name, V, UV, F)
        tiles.append(np.concatenate([render(V, UV, F, tex, yaw=0.6), render(V, UV, F, tex, yaw=2.2, pitch=0.25)], 0))
        print(name, len(V), 'verts', len(F), 'tris')
    sheet = np.concatenate(tiles, 1)
    big = np.kron(tex[..., :3], np.ones((8, 8, 1)))
    pad = np.zeros((sheet.shape[0], big.shape[1], 3)) + 0.13
    pad[:big.shape[0]] = big
    sheet = np.concatenate([sheet, pad], 1)
    Image.fromarray((sheet * 255).astype(np.uint8)).save(os.path.join(out, 'preview.png'))


if __name__ == '__main__':
    main()

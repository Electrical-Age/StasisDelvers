#!/usr/bin/env python3
"""Generate assets/stasisdelvers/textures/effect/mistnoise.png: a tileable 32^3 noise volume for the deep mist
(stasis.fsh, mistTex). The 32 z slices are 34 x 34 tiles (the 32 x 32 slice plus a 1-pixel wrapped border, so
hardware bilinear filtering wraps inside a tile), 8 across and 4 down: 272 x 136.
Channels (each tileable with period 32 voxels, 4 noise cells per period):
  R, G, B: three independent gradient (Perlin) noises, remapped to 0..1
  A: inverted Worley (cellular) noise, 1 at the feature points: billowy shapes
Usage: gen_mistnoise.py OUT.png [seed]"""
import sys

import numpy as np
from PIL import Image

N = 32          # voxels per period
CELLS = 4       # noise cells per period


def fade(t):
    return t * t * t * (t * (t * 6 - 15) + 10)


def perlin(rng):
    g = rng.normal(size=(CELLS, CELLS, CELLS, 3))
    g /= np.linalg.norm(g, axis=-1, keepdims=True)
    c = (np.arange(N) + 0.5) * CELLS / N
    x, y, z = np.meshgrid(c, c, c, indexing="ij")
    xi, yi, zi = np.floor(x).astype(int), np.floor(y).astype(int), np.floor(z).astype(int)
    xf, yf, zf = x - xi, y - yi, z - zi
    out = 0.0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                gr = g[(xi + dx) % CELLS, (yi + dy) % CELLS, (zi + dz) % CELLS]
                dot = gr[..., 0] * (xf - dx) + gr[..., 1] * (yf - dy) + gr[..., 2] * (zf - dz)
                wx = fade(xf) if dx else 1 - fade(xf)
                wy = fade(yf) if dy else 1 - fade(yf)
                wz = fade(zf) if dz else 1 - fade(zf)
                out = out + wx * wy * wz * dot
    out = (out - out.min()) / (out.max() - out.min())
    return out


def worley(rng):
    pts = rng.random(size=(CELLS, CELLS, CELLS, 3))
    c = (np.arange(N) + 0.5) * CELLS / N
    x, y, z = np.meshgrid(c, c, c, indexing="ij")
    xi, yi, zi = np.floor(x).astype(int), np.floor(y).astype(int), np.floor(z).astype(int)
    best = np.full(x.shape, 9.0)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                cx, cy, cz = xi + dx, yi + dy, zi + dz
                p = pts[cx % CELLS, cy % CELLS, cz % CELLS]
                d = np.sqrt((cx + p[..., 0] - x) ** 2 + (cy + p[..., 1] - y) ** 2 + (cz + p[..., 2] - z) ** 2)
                best = np.minimum(best, d)
    best = np.clip(best / best.max(), 0, 1)
    return 1 - best


def main():
    out = sys.argv[1]
    rng = np.random.default_rng(int(sys.argv[2]) if len(sys.argv) > 2 else 0x5ea5)
    vol = np.stack([perlin(rng), perlin(rng), perlin(rng), worley(rng)], axis=-1)   # [x, y, z, ch]
    img = np.zeros((4 * (N + 2), 8 * (N + 2), 4), dtype=np.uint8)
    q = np.clip(np.round(vol * 255), 0, 255).astype(np.uint8)
    idx = np.arange(-1, N + 1) % N
    for z in range(N):
        tile = q[idx][:, idx][:, :, z]       # [x, y, ch], wrapped border
        tx, ty = (z % 8) * (N + 2), (z // 8) * (N + 2)
        img[ty:ty + N + 2, tx:tx + N + 2] = tile.transpose(1, 0, 2)   # rows = y
    Image.fromarray(img, "RGBA").save(out, optimize=True)


if __name__ == "__main__":
    main()

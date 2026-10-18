#!/usr/bin/env python3
"""Spawn site (Baughn 2026-10-18): EA's node and ghost data for the blocks in a box, relative to the box's origin, as
data/stasisdelvers/structure/spawn_cottage_eln.nbt (SpawnCottage places the nodes with the template).
Usage: extract_eln.py WORLD_DIR X0 Y0 Z0 X1 Y1 Z1 SPAWN_X SPAWN_Y SPAWN_Z GROUND OUT.nbt   (box inclusive; overworld;
GROUND: the template's ground level, relative y, e.g. the median height of its border columns' top natural block)"""
import sys, gzip, copy
sys.path.insert(0, '/var/lib/mclab/agent/tools')
import snbt

world, *rest = sys.argv[1:]
x0, y0, z0, x1, y1, z1, sx, sy, sz, ground = map(int, rest[:10])
out = rest[10]
n = snbt.load(world + '/data/electricalAgeWorld0.dat')


def shift(d, keys):
    for k, o in zip(keys, (x0, y0, z0)):
        if k in d: d[k] = (3, d[k][1] - o)


def rel_node(tag):
    t = copy.deepcopy(tag)
    d = t[1]
    shift(d, ('cx', 'cy', 'cz'))
    if 'node' in d: shift(d['node'][1], ('cx', 'cy', 'cz'))
    if 'legacy' in d and 'node' in d['legacy'][1]: shift(d['legacy'][1]['node'][1], ('cx', 'cy', 'cz'))
    for k, v in d.items():   # (a lamp socket's light block)
        if k.startswith('ED') and v[0] == 10: shift(v[1], ('lbCoordInstx', 'lbCoordInsty', 'lbCoordInstz'))
    return t


def pos(d):
    if 'cx' in d: return d['cx'][1], d['cy'][1], d['cz'][1], d['cd'][1]
    nd = d['node'][1]
    return nd['cx'][1], nd['cy'][1], nd['cz'][1], nd['cd'][1]


def inside(x, y, z):
    return x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1


nodes = {}
for v in n[1]['nodes'][1].values():
    x, y, z, d = pos(v[1])
    if d == 0 and inside(x, y, z): nodes['n%d' % len(nodes)] = rel_node(v)
ghosts = {}
for v in n[1]['ghost'][1].values():
    g = v[1]
    if g['elemCoordd'][1] == 0 and inside(g['elemCoordx'][1], g['elemCoordy'][1], g['elemCoordz'][1]):
        t = copy.deepcopy(v)
        shift(t[1], ('elemCoordx', 'elemCoordy', 'elemCoordz'))
        shift(t[1], ('obserCoordx', 'obserCoordy', 'obserCoordz'))
        ghosts['n%d' % len(ghosts)] = t
root = (10, {'nodes': (10, nodes), 'ghost': (10, ghosts), 'spawn': (11, [sx - x0, sy - y0, sz - z0]),
             'size': (11, [x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1]), 'ground': (3, ground)})
open(out, 'wb').write(gzip.compress(snbt.dump(root)))
print('nodes', len(nodes), 'ghosts', len(ghosts), '->', out)

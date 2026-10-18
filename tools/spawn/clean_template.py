#!/usr/bin/env python3
"""Clean a saved spawn template (Stasis /stasis savetemplate output) for shipping:
markers, dropped items and players out; leaves persistent; lava -> stone; natural terrain above the ground level
(GROUND, relative y) -> air (the original's hillside cut by the box). Usage: clean_template.py IN.nbt GROUND OUT.nbt"""
import sys, gzip, collections
sys.path.insert(0, '/var/lib/mclab/agent/tools')
import snbt
src, ground, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
t = snbt.load(src)
d = t[1]
ents = d['entities'][1]
d['entities'] = (9, [e for e in ents if e[1]['nbt'][1]['id'][1] not in ('minecraft:marker', 'minecraft:item', 'minecraft:player')])
TERRAIN = {'minecraft:stone', 'minecraft:dirt', 'minecraft:grass_block', 'minecraft:coarse_dirt', 'minecraft:gravel',
           'minecraft:andesite', 'minecraft:granite', 'minecraft:diorite', 'minecraft:coal_ore', 'minecraft:iron_ore',
           'minecraft:copper_ore', 'stasisdelvers:riftstone', 'stasisdelvers:riftstone_vein'}
pal = d['palette'][1]
names = []
air = None
for i, e in enumerate(pal):
    p = e[1]
    name = (p.get('id') or p.get('Name') or p.get(''))[1]
    names.append(name)
    if name == 'minecraft:air': air = i
    if name.endswith('_leaves') and 'properties' in p: p['properties'][1]['persistent'] = (8, 'true')
    if name == 'minecraft:lava':
        for k in list(p): del p[k]
        p['id'] = (8, 'minecraft:stone')
if air is None:
    air = len(pal)
    pal.append((10, {'id': (8, 'minecraft:air')}))
n = 0
for b in d['blocks'][1]:
    y = b[1]['pos'][1][1][1]
    if y > ground and names[b[1]['state'][1]] in TERRAIN and 'nbt' not in b[1]:
        b[1]['state'] = (3, air)
        n += 1
open(out, 'wb').write(gzip.compress(snbt.dump(t)))
print('entities', len(ents), '->', len(d['entities'][1]), '; terrain above ground cleared:', n)

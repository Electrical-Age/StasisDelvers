#!/usr/bin/env python3
"""Compare a box in a world against a structure template (or another world's box): block states per position.
Usage: region_diff.py TEMPLATE.nbt WORLD_DIR OX OY OZ      (template origin in the world)
       region_diff.py --world WORLD_A AX AY AZ WORLD_B BX BY BZ W H D
Prints counts of differing positions and a sample, grouped by (expected -> found)."""
import sys, struct, zlib, collections, math
sys.path.insert(0, '/var/lib/mclab/agent/tools')
import snbt

_cache = {}


def chunk(world, cx, cz):
    k = (world, cx, cz)
    if k in _cache: return _cache[k]
    f = '%s/dimensions/minecraft/overworld/region/r.%d.%d.mca' % (world, cx >> 5, cz >> 5)
    buf = open(f, 'rb').read()
    i = (cx & 31) + (cz & 31) * 32
    off = struct.unpack('>I', buf[4 * i:4 * i + 4])[0]
    if off == 0:
        _cache[k] = None
        return None
    o = (off >> 8) * 4096
    n, comp = struct.unpack('>IB', buf[o:o + 5])
    data = buf[o + 5:o + 4 + n]
    c = snbt.parse(zlib.decompress(data))
    secs = {}
    for s in c[1]['sections'][1]:
        y = s[1]['Y'][1]
        bs = s[1].get('block_states')
        if bs is None: continue
        pal = [state_name(p) for p in bs[1]['palette'][1]]
        data = bs[1].get('data', (12, []))[1]
        secs[y] = (pal, data)
    _cache[k] = secs
    return secs


def state_name(p):
    if p[0] == 8: return p[1]
    d = p[1]
    name = (d.get('Name') or d.get('id') or d.get(''))[1]
    props = d.get('Properties') or d.get('properties')
    if props: name += '[' + ','.join('%s=%s' % (k, v[1]) for k, v in sorted(props[1].items())) + ']'
    return name


def block(world, x, y, z):
    secs = chunk(world, x >> 4, z >> 4)
    if secs is None: return '<no chunk>'
    s = secs.get(y >> 4)
    if s is None: return 'minecraft:air'
    pal, data = s
    if len(pal) == 1: return pal[0]
    bits = max(4, math.ceil(math.log2(len(pal))))
    per = 64 // bits
    idx = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15)
    word = data[idx // per] & 0xFFFFFFFFFFFFFFFF
    v = (word >> ((idx % per) * bits)) & ((1 << bits) - 1)
    return pal[v]


def template_blocks(path):
    t = snbt.load(path)[1]
    pal = []
    for p in t['palette'][1]:
        d = p[1]
        name = (d.get('Name') or d.get('id') or d.get(''))[1]
        props = d.get('properties') or d.get('Properties')
        if props: name += '[' + ','.join('%s=%s' % (k, v[1]) for k, v in sorted(props[1].items())) + ']'
        pal.append(name)
    out = {}
    for b in t['blocks'][1]:
        x, y, z = [v[1] for v in b[1]['pos'][1]]
        out[(x, y, z)] = pal[b[1]['state'][1]]
    return out, [v[1] for v in t['size'][1]]


def same(a, b):
    # (worlds store a block's default state as the bare name)
    if a == b: return True
    if '[' not in a or '[' not in b: return a.split('[')[0] == b.split('[')[0]
    return False


def report(diffs, total):
    print('differing', len(diffs), 'of', total)
    g = collections.Counter((a, b) for _, a, b in diffs)
    for (a, b), n in g.most_common(40): print('%5d  %s  ->  %s' % (n, a, b))
    for p, a, b in diffs[:30]: print('  ', p, a, '->', b)


if sys.argv[1] == '--world':
    wa, ax, ay, az, wb, bx, by, bz, w, h, d = sys.argv[2:]
    ax, ay, az, bx, by, bz, w, h, d = map(int, (ax, ay, az, bx, by, bz, w, h, d))
    diffs = []
    for x in range(w):
        for y in range(h):
            for z in range(d):
                a, b = block(wa, ax + x, ay + y, az + z), block(wb, bx + x, by + y, bz + z)
                if not same(a, b): diffs.append(((x, y, z), a, b))
    report(diffs, w * h * d)
else:
    tb, (w, h, d) = template_blocks(sys.argv[1])
    world = sys.argv[2]
    ox, oy, oz = map(int, sys.argv[3:6])
    diffs = []
    for (x, y, z), s in sorted(tb.items()):
        f = block(world, ox + x, oy + y, oz + z)
        if not same(s, f): diffs.append(((x, y, z), s, f))
    report(diffs, len(tb))

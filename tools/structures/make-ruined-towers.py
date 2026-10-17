"""Ruined variants of Baughn's tower template (msg 1561135384879173664). Usage:
make-ruined-towers.py BASE.nbt OUTDIR  -> OUTDIR/<name>.nbt, one per variant in VARIANTS (deterministic).
Template coords (x, y, z): pillars y0-2 at the corners, end-stone platform y3, converter (1,4,2), core (2,4,2)+(2,5,2),
mast poles y6..12 at (2,2), booms at y7 (pole k=2, length 2 each way). Tower rules: a boom on pole k needs
H = 2 + poles >= k + 4 and length <= H - k - 2. Poles go back to part=survey and the core forgets its old build
(parted/layout), so the tower is read anew where it is placed."""
import copy, gzip, random, struct, sys, os

# --- typed NBT: every value is (type, payload) ---
def read(buf):
    pos = 0
    def rd(n):
        nonlocal pos
        b = buf[pos:pos + n]; pos += n; return b
    def string():
        (n,) = struct.unpack('>H', rd(2)); return rd(n).decode()
    def payload(t):
        if t == 1: return struct.unpack('>b', rd(1))[0]
        if t == 2: return struct.unpack('>h', rd(2))[0]
        if t == 3: return struct.unpack('>i', rd(4))[0]
        if t == 4: return struct.unpack('>q', rd(8))[0]
        if t == 5: return struct.unpack('>f', rd(4))[0]
        if t == 6: return struct.unpack('>d', rd(8))[0]
        if t == 7: (n,) = struct.unpack('>i', rd(4)); return rd(n)
        if t == 8: return string()
        if t == 9:
            et, n = struct.unpack('>bi', rd(5)); return (et, [payload(et) for _ in range(n)])
        if t == 10:
            d = {}
            while True:
                (ct,) = struct.unpack('>b', rd(1))
                if ct == 0: return d
                k = string(); d[k] = (ct, payload(ct))
        if t == 11: (n,) = struct.unpack('>i', rd(4)); return list(struct.unpack(f'>{n}i', rd(4 * n)))
        if t == 12: (n,) = struct.unpack('>i', rd(4)); return list(struct.unpack(f'>{n}q', rd(8 * n)))
        raise ValueError(t)
    (t,) = struct.unpack('>b', rd(1)); string()
    return payload(t)

def write(t, v):
    if t == 1: return struct.pack('>b', v)
    if t == 2: return struct.pack('>h', v)
    if t == 3: return struct.pack('>i', v)
    if t == 4: return struct.pack('>q', v)
    if t == 5: return struct.pack('>f', v)
    if t == 6: return struct.pack('>d', v)
    if t == 7: return struct.pack('>i', len(v)) + v
    if t == 8: b = v.encode(); return struct.pack('>H', len(b)) + b
    if t == 9:
        et, items = v; return struct.pack('>bi', et if items else 0, len(items)) + b''.join(write(et, i) for i in items)
    if t == 10:
        out = b''
        for k, (tt, vv) in v.items():
            kb = k.encode(); out += struct.pack('>bH', tt, len(kb)) + kb + write(tt, vv)
        return out + b'\x00'
    if t == 11: return struct.pack('>i', len(v)) + struct.pack(f'>{len(v)}i', *v)
    if t == 12: return struct.pack('>i', len(v)) + struct.pack(f'>{len(v)}q', *v)
    raise ValueError(t)

SEG = 'stasisdelvers:stasis_tower_segment'


class T:
    """A template as a dict (x,y,z) -> (block id, props dict, nbt or None); positions missing = left alone (no air)."""
    def __init__(self, root):
        self.root = root
        pal = [{'id': p['id'][1], 'props': {k: v[1] for k, v in p.get('properties', (10, {}))[1].items()}} for p in root['palette'][1][1]]
        self.b = {}
        for blk in root['blocks'][1][1]:
            x, y, z = blk['pos'][1][1]
            p = pal[blk['state'][1]]
            self.b[(x, y, z)] = [p['id'], dict(p['props']), blk['nbt'][1] if 'nbt' in blk else None]
        self.size = list(root['size'][1][1])

    def copy(self):
        t = T.__new__(T); t.root = self.root; t.size = list(self.size)
        t.b = {k: [v[0], dict(v[1]), copy.deepcopy(v[2])] for k, v in self.b.items()}
        return t

    def get(self, x, y, z): return self.b.get((x, y, z), ['minecraft:air', {}, None])[0]
    def set(self, x, y, z, bid, props=None, nbt=None): self.b[(x, y, z)] = [bid, props or {}, nbt]
    def air(self, x, y, z): self.set(x, y, z, 'minecraft:air')

    def save(self, path):
        pal, idx, blocks = [], {}, []
        for (x, y, z), (bid, props, nbt) in sorted(self.b.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
            key = (bid, tuple(sorted(props.items())))
            if key not in idx:
                idx[key] = len(pal)
                e = {'id': (8, bid)}
                if props: e['properties'] = (10, {k: (8, v) for k, v in props.items()})
                pal.append(e)
            blk = {'pos': (9, (3, [x, y, z])), 'state': (3, idx[key])}
            if nbt is not None: blk['nbt'] = (10, nbt)
            blocks.append(blk)
        root = dict(self.root)
        root['size'] = (9, (3, self.size))
        root['palette'] = (9, (10, pal))
        root['blocks'] = (9, (10, blocks))
        open(path, 'wb').write(gzip.compress(struct.pack('>bH', 10, 0) + write(10, root)))
        return len(blocks)


def clean_tower(t, seed):
    """Poles back to survey poles, cores forget the old build (absolute 'parted' positions, layout) and die."""
    for k, v in t.b.items():
        if v[0] == SEG: v[1]['part'] = 'survey'
        if v[0] == 'stasisdelvers:stasis_tower' and v[2] is not None:
            n = v[2]
            for key in ('parted', 'layout'): n.pop(key, None)
            n['tstatus'] = (8, 'NONE'); n['ladder'] = (3, -1)
            n['seed'] = (3, seed)


def mast_top(t, h):
    """Cut the mast above y h (poles there and their booms go)."""
    for y in range(h + 1, 13):
        for x in range(5):
            for z in range(5):
                if t.get(x, y, z) == SEG: t.air(x, y, z)


def boom(t, d, length):
    """Shorten the y7 boom in direction d (E S W N) to length (0: gone; a boom is at least 2 long)."""
    dx, dz = {'E': (1, 0), 'S': (0, 1), 'W': (-1, 0), 'N': (0, -1)}[d]
    for i in (1, 2):
        if i > length: t.air(2 + dx * i, 7, 2 + dz * i)


def lying_pole(t, x, y, z, axis):
    t.set(x, y, z, SEG, {'axis': axis, 'part': 'survey'})


def chip(t, rng, frac, ys=(3,), keep=()):
    """Knock out a fraction of the platform's edge blocks (never the centre 3x3 under the core)."""
    for y in ys:
        for x in range(5):
            for z in range(5):
                if (x, z) in keep or (1 <= x <= 3 and 1 <= z <= 3): continue
                if t.get(x, y, z) != 'minecraft:air' and rng.random() < frac: t.air(x, y, z)


def weather_pillars(t, rng, frac):
    for (x, y, z), v in t.b.items():
        if v[0] == 'minecraft:obsidian' and rng.random() < frac: v[0] = 'minecraft:crying_obsidian'


def rubble(t, rng, cells, ids=('minecraft:end_stone', 'minecraft:end_stone_bricks', 'minecraft:cobblestone', 'minecraft:gravel')):
    for x, y, z in cells: t.set(x, y, z, rng.choice(ids))


def cobweb(t, cells):
    for c in cells: t.set(*c, 'minecraft:cobweb')


# --- the variants: (name, what it is) -> function(t, rng) ---
def snapped(t, rng):
    """The mast snapped above the boom level (4 poles left, H 6: the booms still fit); the top lies on the platform."""
    mast_top(t, 9)
    lying_pole(t, 3, 4, 0, 'x'); lying_pole(t, 4, 4, 0, 'x')   # (y4 is below the core's top: not read as a boom)
    lying_pole(t, 0, 4, 3, 'z')
    chip(t, rng, 0.25)
    weather_pillars(t, rng, 0.3)


def stripped(t, rng):
    """Full mast, but two booms gone; the converter was taken."""
    boom(t, 'N', 0); boom(t, 'E', 0)
    t.air(1, 4, 2)
    chip(t, rng, 0.15)
    cobweb(t, [(3, 9, 2), (2, 11, 1)])


def sagging(t, rng):
    """One corner pillar gave way: the platform corner above it fell to the ground; mast cut to 6 poles."""
    for y in (1, 2): t.air(4, y, 4)
    for x, z in ((4, 4), (3, 4), (4, 3), (4, 2), (2, 4)): t.air(x, 3, z)
    rubble(t, rng, [(4, 0, 3), (3, 0, 4), (3, 0, 3), (4, 1, 3)])
    mast_top(t, 11)
    weather_pillars(t, rng, 0.2)


def stump(t, rng):
    """Only a stump of the mast is left (2 poles, no booms: a small, cheap field); the rest lies under the platform."""
    mast_top(t, 7)
    for d in 'ESWN': boom(t, d, 0)
    for x in (1, 2, 3): lying_pole(t, x, 0, 2, 'x')
    lying_pole(t, 2, 0, 1, 'z')
    chip(t, rng, 0.4)
    weather_pillars(t, rng, 0.5)
    rubble(t, rng, [(1, 0, 3), (3, 0, 1)])


def weathered(t, rng):
    """Standing whole, just old: crying obsidian, a few lost edge stones, cobwebs, one boom arm fallen."""
    boom(t, 'S', 0)
    lying_pole(t, 2, 4, 4, 'z')   # (the fallen boom arm)
    chip(t, rng, 0.1)
    weather_pillars(t, rng, 0.6)
    cobweb(t, [(1, 8, 2), (3, 10, 2), (2, 12, 3), (1, 0, 1)])


def toppled(t, rng):
    """The mast came down whole: two poles stand (no booms), the rest lies over the platform's edge, and one pillar
    is gone (the platform's corner above it went with it)."""
    mast_top(t, 7)
    for d in 'ESWN': boom(t, d, 0)
    for x in (1, 2, 3, 4): lying_pole(t, x, 4, 4, 'x')
    lying_pole(t, 4, 4, 1, 'z'); lying_pole(t, 4, 4, 0, 'z')
    for y in (0, 1, 2): t.air(0, y, 0)
    for x, z in ((0, 0), (1, 0), (0, 1)): t.air(x, 3, z)
    rubble(t, rng, [(0, 0, 1), (1, 0, 0)])
    weather_pillars(t, rng, 0.3)


VARIANTS = [('snapped', snapped), ('stripped', stripped), ('sagging', sagging), ('stump', stump),
            ('weathered', weathered), ('toppled', toppled)]


def main():
    base = T(read(gzip.decompress(open(sys.argv[1], 'rb').read())))
    os.makedirs(sys.argv[2], exist_ok=True)
    for i, (name, f) in enumerate(VARIANTS):
        t = base.copy()
        rng = random.Random(1000 + i)
        f(t, rng)
        clean_tower(t, 7919 * (i + 1))
        n = t.save(os.path.join(sys.argv[2], name + '.nbt'))
        poles = sum(1 for y in range(6, 13) if t.get(2, y, 2) == SEG)
        print(f'{name}: {n} blocks, {poles} mast poles; {f.__doc__.split(chr(10))[0]}')


if __name__ == '__main__':
    main()

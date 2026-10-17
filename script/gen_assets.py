#!/usr/bin/env python3
"""Placeholder art and item JSON for Stasis Delvers' suits (until real textures exist).
Writes item icons (16x16), equipment layers (64x32), item model + item definition JSON, and equipment assets."""
import json, os, random
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'stasisdelvers')
SUITS = {
    'delver': {'base': (38, 92, 96), 'trim': (196, 160, 72), 'glow': (120, 230, 220)},
    'stasis_armor': {'base': (34, 34, 40), 'trim': (90, 90, 100), 'glow': (110, 220, 255)},
}
PIECES = {
    'helmet': ["  XXXXXXXXXXXX  ", " XXXXXXXXXXXXXX ", " XXGGGGGGGGGGXX ", " XXG        GXX ", " XX          XX ", " XX          XX "],
    'chestplate': ["XXXX      XXXX  ", "XXXXXXXXXXXXXX  ", " XXXXXXXXXXXX   ", "  XXXXGGXXXX    ", "  XXXXGGXXXX    ", "  XXXXXXXXXX    ",
                   "  XXXXXXXXXX    ", "  XXXXXXXXXX    ", "  TTTTTTTTTT    "],
    'leggings': ["  TTTTTTTTTT    ", "  XXXXXXXXXX    ", "  XXXX  XXXX    ", "  XXXX  XXXX    ", "  XXXX  XXXX    ",
                 "  XXXX  XXXX    ", "  XXXX  XXXX    ", "  XXXX  XXXX    "],
    'boots': ["  XXX    XXX    ", "  XXX    XXX    ", "  XXX    XXX    ", " XXXX    XXXX   ", " TTTT    TTTT   "],
}


def write_json(rel, obj):
    p = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w') as f:
        json.dump(obj, f, indent=2)
        f.write('\n')


def icon(colors, rows, path):
    rng = random.Random(path)
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    top = (16 - len(rows)) // 2
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            c = {'X': colors['base'], 'T': colors['trim'], 'G': colors['glow']}.get(ch)
            if c:
                k = rng.uniform(0.85, 1.12)
                im.putpixel((x, top + y), tuple(min(255, int(v * k)) for v in c) + (255,))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)


def layer(colors, path):
    rng = random.Random(path)
    im = Image.new('RGBA', (64, 32), (0, 0, 0, 0))
    for y in range(32):
        for x in range(64):
            k = rng.uniform(0.85, 1.1)
            c = colors['base']
            if y % 8 == 7 or x % 16 == 0:
                c = colors['trim']
            if (x + y) % 23 == 0:
                c = colors['glow']
            im.putpixel((x, y), tuple(min(255, int(v * k)) for v in c) + (255,))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)


def glove(colors, path):
    """First-person glove (client/SuitArm.java), 32x16, box UV: the cuff (4x1x4) at (0, 0), the glove (4x4x4) at
    (0, 5). Side faces of a box sit in the row below its top/bottom faces (right, front, left, back, 4 px each); the
    glove's fingertips are its bottom. Each side: a wrist strap, knuckle plates, four fingers with lighter tips; a
    charge pip on the back of the hand and the outer side; the cuff gets a dark rim and glow studs."""
    rng = random.Random(path)
    im = Image.new('RGBA', (32, 16), (0, 0, 0, 0))
    base, trim, glow = colors['base'], colors['trim'], colors['glow']

    def shade(c, k):
        return tuple(min(255, int(v * k)) for v in c)

    def px(x, y, c, jitter=0.06):
        k = rng.uniform(1 - jitter, 1 + jitter)
        im.putpixel((x, y), shade(c, k) + (255,))
    # cuff: top/bottom faces (rows 0..3) trim with a darker rim, side band (row 4) trim with glow studs
    for y in range(0, 4):
        for x in range(4, 12):
            rim = x in (4, 7, 8, 11) or y in (0, 3)
            px(x, y, shade(trim, 0.7) if rim else trim)
    for x in range(16):
        px(x, 4, glow if x % 4 in (1, 2) and x // 4 in (1, 2) else shade(trim, 0.85))
    # hand: top face (rows 5..8, x 4..7, under the cuff) dark; bottom face (fingertips, x 8..11) four finger pads
    for y in range(5, 9):
        for x in range(4, 8):
            px(x, y, shade(base, 0.45))
        for x in range(8, 12):
            px(x, y, shade(base, 0.75) if (x - 8) % 2 == 0 else shade(base, 0.5))
    # sides (rows 9..12): strap, knuckles, fingers, tips
    for face in range(4):
        x0 = face * 4
        for i in range(4):
            x = x0 + i
            px(x, 9, shade(trim, 0.6))                                   # wrist strap
            px(x, 10, shade(base, 1.15 if i in (1, 2) else 0.95))        # knuckle plate, lit in the middle
            px(x, 11, shade(base, 0.62 if i % 2 == 0 else 0.5))          # fingers, seams between
            px(x, 12, shade(base, 0.8 if i % 2 == 0 else 0.62))          # tips
    # the charge pip: back of the hand (front face) and the outer side (right face), on the knuckle row
    for x in (5, 6, 1):
        px(x, 10, glow, 0.02)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)


for suit, colors in SUITS.items():
    glove(colors, os.path.join(ROOT, 'textures', 'entity', 'glove', suit + '.png'))
    prefix = 'delver' if suit == 'delver' else 'stasis'
    for piece, rows in PIECES.items():
        name = f'{prefix}_{piece}'
        icon(colors, rows, os.path.join(ROOT, 'textures', 'item', name + '.png'))
        write_json(f'models/item/{name}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'stasisdelvers:item/{name}'}})
        write_json(f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'stasisdelvers:item/{name}'}})
    layer(colors, os.path.join(ROOT, 'textures', 'entity', 'equipment', 'humanoid', suit + '.png'))
    layer(colors, os.path.join(ROOT, 'textures', 'entity', 'equipment', 'humanoid_leggings', suit + '.png'))
    write_json(f'equipment/{suit}.json', {'layers': {'humanoid': [{'texture': f'stasisdelvers:{suit}'}],
                                                     'humanoid_leggings': [{'texture': f'stasisdelvers:{suit}'}]}})

# tower blocks: a dark metal casing with a cyan core line (base) and plain ribbed segments
def block_tex(path, core):
    rng = random.Random(path)
    im = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            k = rng.uniform(0.85, 1.1)
            c = (70, 74, 82)
            if x in (0, 15) or y in (0, 15):
                c = (120, 104, 70)
            if core and 6 <= x <= 9:
                c = (90, 210, 230) if y % 4 else (60, 140, 160)
            if not core and y % 4 == 0:
                c = (52, 55, 62)
            im.putpixel((x, y), tuple(min(255, int(v * k)) for v in c) + (255,))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)


for name, core in (('stasis_tower', True), ('stasis_tower_segment', False)):
    block_tex(os.path.join(ROOT, 'textures', 'block', name + '.png'), core)
    write_json(f'models/block/{name}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'stasisdelvers:block/{name}'}})
    write_json(f'blockstates/{name}.json', {'variants': {'': {'model': f'stasisdelvers:block/{name}'}}})
    write_json(f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'stasisdelvers:block/{name}'}})
    data = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'data', 'stasisdelvers', 'loot_table', 'blocks')
    os.makedirs(data, exist_ok=True)
    with open(os.path.join(data, name + '.json'), 'w') as f:
        json.dump({'type': 'minecraft:block', 'pools': [{'rolls': 1, 'conditions': [{'condition': 'minecraft:survives_explosion'}],
                   'entries': [{'type': 'minecraft:item', 'name': f'stasisdelvers:{name}'}]}]}, f, indent=2)
print('ok')

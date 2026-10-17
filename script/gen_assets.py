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
    """Equipment layer (64x32, the vanilla humanoid armour UV): helmet with a dark visor and glow line, a chest plate with
    a collar, a centre seam, a charge gauge and a belt, a power pack on the back, trim bands at elbows and wrists, knee
    pads and boot cuffs. Leggings use the same texture (legs and the belt)."""
    rng = random.Random(path)
    im = Image.new('RGBA', (64, 32), (0, 0, 0, 0))
    base, trim, glow = colors['base'], colors['trim'], colors['glow']

    def shade(c, k):
        return tuple(min(255, int(v * k)) for v in c)

    def px(x, y, c, j=0.07):
        im.putpixel((x, y), shade(c, rng.uniform(1 - j, 1 + j)) + (255,))

    def rect(x0, y0, x1, y1, c, j=0.07):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                px(x, y, c, j)
    # everything first: the base plate, faintly mottled
    for y in range(32):
        for x in range(64):
            px(x, y, base, 0.1)
    # the hat overlay (32-63, rows 0-15) stays clear, or it would cover the helmet's face
    for y in range(16):
        for x in range(32, 64):
            im.putpixel((x, y), (0, 0, 0, 0))
    # head (box at 0,0; 8x8x8): faces right 0-7, front 8-15, left 16-23, back 24-31 (rows 8-15); top 8-15, bottom 16-23
    rect(0, 15, 31, 15, trim)                         # neck ring
    rect(8, 0, 15, 7, shade(base, 1.08))              # crown
    rect(11, 0, 12, 7, trim)                          # crest ridge over the top
    rect(8, 8, 15, 8, trim)                           # brow
    rect(9, 10, 14, 13, shade(base, 0.22), 0.04)      # the visor: dark glass
    rect(9, 10, 14, 10, glow, 0.03)                   # its glow line
    px(8, 11, trim); px(15, 11, trim)                 # visor hinges
    for x in (2, 5, 18, 21):                          # vents on the sides
        rect(x, 11, x, 13, shade(base, 0.55))
    # body (box at 16,16; 8x12x4): right 16-19, front 20-27, left 28-31, back 32-39 (rows 20-31)
    rect(16, 20, 39, 20, trim)                        # collar
    rect(23, 21, 24, 29, shade(base, 0.7))            # centre seam
    rect(21, 22, 22, 25, shade(base, 0.35), 0.03)     # charge gauge
    rect(21, 23, 22, 25, glow, 0.03)
    rect(16, 30, 39, 31, trim)                        # belt
    rect(25, 30, 26, 31, shade(trim, 1.25))           # buckle
    rect(33, 22, 38, 28, shade(base, 0.6))            # power pack on the back
    for y in (23, 25, 27):
        rect(34, y, 37, y, glow, 0.03)                # its vents
    # arms (box at 40,16; 4x12x4): right 40-43, front 44-47, left 48-51, back 52-55 (rows 20-31)
    rect(40, 20, 55, 20, trim)                        # shoulder rim
    rect(40, 25, 55, 25, trim)                        # elbow band
    rect(40, 31, 55, 31, trim)                        # wrist cuff
    rect(41, 21, 41, 30, glow, 0.03)                  # a glow line down the outer side
    # legs (box at 0,16; 4x12x4): right 0-3, front 4-7, left 8-11, back 12-15 (rows 20-31)
    rect(4, 24, 7, 26, shade(trim, 0.95))             # knee pad
    rect(5, 25, 6, 25, glow, 0.03)
    rect(0, 28, 15, 31, shade(base, 0.6))             # boots
    rect(0, 28, 15, 28, trim)                         # boot cuff
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

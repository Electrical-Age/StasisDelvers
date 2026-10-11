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


for suit, colors in SUITS.items():
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
print('ok')

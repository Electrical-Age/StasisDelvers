#!/usr/bin/env python3
"""Rift crystal grades (rift stage 2, notes/rifts/STAGE2-PLAN.md C): rift_dust and charged_rift_shard items (textures
from the shard icon gen_rift_assets.py made), and the bud loot tables: small and medium give dust, large and cluster
give shards, a cluster broken while its pocket is violet (stasisdelvers:pocket_violet) gives charged shards.
Run after gen_rift_assets.py. Usage: gen_rift_grades.py"""
import json
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', 'src', 'main', 'resources')
A = os.path.join(RES, 'assets', 'stasisdelvers')
D = os.path.join(RES, 'data')


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write('\n')


def save(img, rel):
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(os.path.join(A, 'textures', rel))


def item(name):
    write_json(os.path.join(A, 'models', 'item', f'{name}.json'),
               {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'stasisdelvers:item/{name}'}})
    write_json(os.path.join(A, 'items', f'{name}.json'), {'model': {'type': 'minecraft:model', 'model': f'stasisdelvers:item/{name}'}})


def drop(name, count, extra=None):
    e = {'type': 'minecraft:item', 'name': f'stasisdelvers:{name}', 'modifier': [
        {'type': 'minecraft:set_count', 'count': count}, {'type': 'minecraft:explosion_decay'}]}
    if extra:
        e.update(extra)
    return e


def table(block, entries):
    write_json(os.path.join(D, 'stasisdelvers', 'loot_table', 'blocks', f'{block}.json'), {
        'type': 'minecraft:block', 'pools': [{'entries': entries, 'rolls': 1}],
        'random_sequence': f'stasisdelvers:blocks/{block}'})


def main():
    rng = np.random.default_rng(0x6EAD)
    shard = np.asarray(Image.open(os.path.join(A, 'textures', 'item', 'rift_shard.png')).convert('RGBA')).astype(float)
    cols = shard[shard[..., 3] > 0][:, :3]
    # dust: a small heap of grains in the shard's colours, darker at the bottom
    dust = np.zeros((16, 16, 4))
    for _ in range(160):
        x = rng.normal(8, 2.4)
        y = 13 - abs(rng.normal(0, 1.4)) * max(0.3, 1 - abs(x - 8) / 6) * 2
        xi, yi = int(round(x)), int(round(y))
        if 1 <= xi <= 14 and 3 <= yi <= 14:
            c = cols[rng.integers(len(cols))] * (1.15 - 0.45 * (yi - 3) / 11)
            dust[yi, xi, :3] = c
            dust[yi, xi, 3] = 255
    save(dust, 'item/rift_dust.png')
    # charged shard: the shard, brighter and more violet, a dark core line and a white-violet rim glint
    ch = shard.copy()
    m = ch[..., 3] > 0
    ch[m, :3] = ch[m, :3] * np.array([1.15, 0.85, 1.3]) + np.array([30, 0, 40])
    for y in range(16):
        for x in range(16):
            u, v = (x - 8) + (y - 8) * 0.35, (y - 8)
            if m[y, x] and abs(u) < 0.6 and abs(v) < 5:
                ch[y, x, :3] = [40, 10, 60]
            if m[y, x] and 0.82 < abs(u) / 2.6 + abs(v) / 7.5 <= 1 and u < 0:
                ch[y, x, :3] = [240, 215, 255]
    save(ch, 'item/charged_rift_shard.png')
    item('rift_dust')
    item('charged_rift_shard')
    # loot
    table('rift_crystal_small', [drop('rift_dust', 1)])
    table('rift_crystal_medium', [drop('rift_dust', {'type': 'minecraft:uniform', 'min': 1, 'max': 2})])
    table('rift_crystal_large', [drop('rift_shard', 1)])
    table('rift_crystal_cluster', [{'type': 'minecraft:alternatives', 'children': [
        drop('charged_rift_shard', {'type': 'minecraft:uniform', 'min': 2, 'max': 4}, {'condition': {'type': 'stasisdelvers:pocket_violet'}}),
        drop('rift_shard', {'type': 'minecraft:uniform', 'min': 2, 'max': 4})]}])
    lang = os.path.join(A, 'lang', 'en_us.json')
    l = json.load(open(lang))
    l['item.stasisdelvers.rift_dust'] = 'Rift Dust'
    l['item.stasisdelvers.charged_rift_shard'] = 'Charged Rift Shard'
    write_json(lang, l)


if __name__ == '__main__':
    main()

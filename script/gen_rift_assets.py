#!/usr/bin/env python3
"""Assets and data for the rift strata blocks (Strata, DeepFeature; notes/rifts/STAGE1-PLAN.md):
riftstone, riftstone_vein, budding_rift_crystal, rift_crystal_{small,medium,large,cluster} (OBJ models from
gen_crystals.py, run first into the models dir), rift_shard. Writes procedural textures, blockstates, block and item
models, item definitions, loot tables, the pickaxe tag and English names. Usage: gen_rift_assets.py"""
import json
import os
import subprocess
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', 'src', 'main', 'resources')
A = os.path.join(RES, 'assets', 'stasisdelvers')
D = os.path.join(RES, 'data')
STAGES = ['small', 'medium', 'large', 'cluster']
CRYSTALS = [f'rift_crystal_{s}' for s in STAGES]
CUBES = ['riftstone', 'riftstone_vein', 'budding_rift_crystal']
NAMES = {'riftstone': 'Riftstone', 'riftstone_vein': 'Riftstone Vein', 'budding_rift_crystal': 'Budding Rift Crystal',
         'rift_crystal_small': 'Small Rift Crystal', 'rift_crystal_medium': 'Medium Rift Crystal',
         'rift_crystal_large': 'Large Rift Crystal', 'rift_crystal_cluster': 'Rift Crystal Cluster'}


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write('\n')


def save(img, rel):
    p = os.path.join(A, 'textures', rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(p)


def stone(rng):
    """Dark violet-grey stone: blotchy value noise in 2x2 clumps plus speckle."""
    base = np.array([62, 55, 72], float)
    coarse = rng.normal(0, 1, (8, 8))
    coarse = np.kron(coarse, np.ones((2, 2)))
    fine = rng.normal(0, 1, (16, 16))
    v = 1 + 0.07 * coarse + 0.04 * fine
    img = base[None, None, :] * v[..., None]
    return np.concatenate([img, np.full((16, 16, 1), 255.0)], -1)


def main():
    rng = np.random.default_rng(0x41F7)
    # textures
    rs = stone(rng)
    save(rs, 'block/riftstone.png')
    vein = rs.copy()
    for _ in range(3):   # bright violet cracks: short random walks
        x, y = rng.integers(0, 16, 2)
        for _ in range(14):
            vein[y % 16, x % 16, :3] = [185, 105, 230]
            x += rng.integers(-1, 2)
            y += rng.integers(0, 2)
    save(vein, 'block/riftstone_vein.png')
    bud = rs.copy()
    for _ in range(7):   # crystal flecks
        x, y = rng.integers(1, 15, 2)
        bud[y, x, :3] = [175, 120, 225]
        bud[y, x + 1, :3] = [120, 70, 165]
        bud[y + 1, x, :3] = [30, 12, 40]
    save(bud, 'block/budding_rift_crystal.png')
    # the crystal texture and OBJ models
    mdir = os.path.join(A, 'models', 'block')
    subprocess.run([sys.executable, '-I', os.path.join(HERE, 'gen_crystals.py'), mdir], check=True)
    os.replace(os.path.join(mdir, 'rift_crystal.png'), os.path.join(A, 'textures', 'block', 'rift_crystal.png'))
    os.remove(os.path.join(mdir, 'preview.png'))
    crystal = np.asarray(Image.open(os.path.join(A, 'textures', 'block', 'rift_crystal.png')).convert('RGBA')).astype(float)
    shard = np.zeros((16, 16, 4))
    for y in range(16):      # a shard icon: a slanted lozenge cut from the crystal texture
        for x in range(16):
            u, v = (x - 8) + (y - 8) * 0.35, (y - 8)
            if abs(u) / 2.6 + abs(v) / 7.5 <= 1:
                shard[y, x] = crystal[(y * 2) % 32, (x * 2) % 32]
                shard[y, x, 3] = 255
    save(shard, 'item/rift_shard.png')
    # cube blocks
    for name in CUBES:
        write_json(os.path.join(A, 'blockstates', f'{name}.json'), {'variants': {'': {'model': f'stasisdelvers:block/{name}'}}})
        write_json(os.path.join(A, 'models', 'block', f'{name}.json'),
                   {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'stasisdelvers:block/{name}'}})
        write_json(os.path.join(A, 'items', f'{name}.json'), {'model': {'type': 'minecraft:model', 'model': f'stasisdelvers:block/{name}'}})
    # crystals: OBJ models, facing like amethyst buds
    rot = {'up': {}, 'down': {'x': 180}, 'north': {'x': 90}, 'south': {'x': 90, 'y': 180}, 'west': {'x': 90, 'y': 270},
           'east': {'x': 90, 'y': 90}}
    for st, name in zip(STAGES, CRYSTALS):
        write_json(os.path.join(A, 'models', 'block', f'{name}.json'), {
            'loader': 'neoforge:obj', 'model': f'stasisdelvers:models/block/{name}.obj', 'flip_v': True,
            'textures': {'crystal': 'stasisdelvers:block/rift_crystal', 'particle': 'stasisdelvers:block/rift_crystal'}})
        write_json(os.path.join(A, 'blockstates', f'{name}.json'),
                   {'variants': {f'facing={f}': {'model': f'stasisdelvers:block/{name}', **r} for f, r in rot.items()}})
        write_json(os.path.join(A, 'items', f'{name}.json'), {'model': {'type': 'minecraft:model', 'model': f'stasisdelvers:block/{name}'}})
    write_json(os.path.join(A, 'models', 'item', 'rift_shard.json'),
               {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'stasisdelvers:item/rift_shard'}})
    write_json(os.path.join(A, 'items', 'rift_shard.json'), {'model': {'type': 'minecraft:model', 'model': 'stasisdelvers:item/rift_shard'}})
    # loot: stone drops itself; budding blocks and buds drop nothing (as amethyst); a cluster drops 2-4 shards
    lt = os.path.join(D, 'stasisdelvers', 'loot_table', 'blocks')
    # (26.x loot format: pool "condition", entry "modifier" with "type"; the old "functions"/"conditions" keys are
    # silently ignored: a cluster dropped 1 shard, AdventurAgent)
    for name in ['riftstone', 'riftstone_vein']:
        write_json(os.path.join(lt, f'{name}.json'), {'type': 'minecraft:block', 'pools': [{
            'condition': {'type': 'minecraft:survives_explosion'},
            'entries': [{'type': 'minecraft:item', 'name': f'stasisdelvers:{name}'}], 'rolls': 1}],
            'random_sequence': f'stasisdelvers:blocks/{name}'})
    for name in ['budding_rift_crystal'] + CRYSTALS[:3]:
        write_json(os.path.join(lt, f'{name}.json'), {'type': 'minecraft:block', 'pools': []})
    write_json(os.path.join(lt, 'rift_crystal_cluster.json'), {'type': 'minecraft:block', 'pools': [{'entries': [{
        'type': 'minecraft:item', 'name': 'stasisdelvers:rift_shard', 'modifier': [
            {'type': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform', 'min': 2, 'max': 4}},
            {'type': 'minecraft:explosion_decay'}]}], 'rolls': 1}],
        'random_sequence': 'stasisdelvers:blocks/rift_crystal_cluster'})
    # the pickaxe tag (merge with what's there)
    tag = os.path.join(D, 'minecraft', 'tags', 'block', 'mineable', 'pickaxe.json')
    t = json.load(open(tag)) if os.path.exists(tag) else {'replace': False, 'values': []}
    for name in CUBES + CRYSTALS:
        if f'stasisdelvers:{name}' not in t['values']:
            t['values'].append(f'stasisdelvers:{name}')
    write_json(tag, t)
    # English names
    lang = os.path.join(A, 'lang', 'en_us.json')
    l = json.load(open(lang))
    for k, v in NAMES.items():
        l[f'block.stasisdelvers.{k}'] = v
    l['item.stasisdelvers.rift_shard'] = 'Rift Shard'
    write_json(lang, l)


if __name__ == '__main__':
    main()

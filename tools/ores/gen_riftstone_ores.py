#!/usr/bin/env python3
"""Riftstone ores (λ-Nero: ore in rifts should look like ore in riftstone, not stone ore blocks set into riftstone).

DeepFeature's rift scatter picks ores from c:ores_in_ground/stone and /deepslate. For each ore material in those tags
(across the vanilla client jar, NeoForge and the pack's mods), this writes one block `stasisdelvers:riftstone_<m>_ore`
that stands in for both the stone and the deepslate source:

- texture: the stone ore's spots (its pixels that are not plain stone grey) laid over riftstone.png;
- blockstate, block model, item definition, English name;
- loot table: silk touch gives the riftstone ore itself (like vanilla's deepslate ores), anything else runs the stone
  source's own loot table (Fortune and explosion decay included);
- tags (block and item): the sources' own tags (c:ores/<m>, minecraft:<m>_ores, c:ore_rates/*, needs_*_tool),
  mineable/pickaxe, and c:ores_in_ground/riftstone, but never c:ores_in_ground/stone|deepslate (the scatter reads
  those, and the variants would feed back in as sources);
- smelting/blasting recipes copied from the vanilla per-item ore recipes (modded ores smelt by c:ores/<m> tags);
- src/main/java/org/erisia/stasis/RiftOreTable.java: the list the registry and the scatter use.

Usage (from the repo root):
  nix-shell -p 'python3.withPackages(ps:[ps.pillow ps.numpy])' --run \
    'python3 -I tools/ores/gen_riftstone_ores.py [--client JAR] [--neoforge JAR] [--mods DIR] [--preview PNG]'
"""
import argparse
import glob
import io
import json
import os
import re
import sys
import zipfile
from collections import defaultdict

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
RES = os.path.join(ROOT, 'src', 'main', 'resources')
A = os.path.join(RES, 'assets', 'stasisdelvers')
D = os.path.join(RES, 'data')
JAVA = os.path.join(ROOT, 'src', 'main', 'java', 'org', 'erisia', 'stasis', 'RiftOreTable.java')
NS = 'stasisdelvers'

GH = '/var/lib/mclab/agent/mods/gradle-home/caches'
CLIENT = GH + '/neoformruntime/artifacts/minecraft_26.3_client.jar'
NEOFORGE = glob.glob(GH + '/modules-2/files-2.1/net.neoforged/neoforge/26.3.0.75-beta/*/neoforge-26.3.0.75-beta-universal.jar')
MODS = '/srv/minecraft-lab/spawn/alt-server/mods'

# XP per material (the sources' DropExperienceBlock ranges; redstone is a RedStoneOreBlock, 1-5). Hardness is 3/3
# for every stone source (vanilla, EnergizedPower, Occultism's ofFullCopy(IRON_ORE), Theurgy), so it is not listed.
XP = {'coal': (0, 2), 'copper': (0, 0), 'iron': (0, 0), 'gold': (0, 0), 'redstone': (1, 5), 'lapis': (2, 5),
      'diamond': (3, 7), 'emerald': (3, 7), 'tin': (0, 0), 'silver': (0, 0), 'sal_ammoniac': (2, 5),
      'lead': (0, 0), 'tungsten': (0, 0), 'cinnabar': (0, 0)}
# blast resistance where the sources' isn't stone's 3 (Electrical Age's ores: 3/5)
RESISTANCE = {'lead': 5, 'tungsten': 5, 'cinnabar': 5}
# tags of the sources that do not carry over (the scatter's own tags; world-shape tags of other mods)
SKIP_TAGS = {'c:ores_in_ground/stone', 'c:ores_in_ground/deepslate', 'minecraft:snaps_goat_horn', 'terralith:island_blocks'}


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write('\n')


class Pack:
    """Tags, textures, loot tables and recipes from a list of jars (later jars do not override earlier ones)."""

    def __init__(self, jars):
        self.jars = [zipfile.ZipFile(j) for j in jars]
        self.tags = defaultdict(list)   # (kind, id) -> [values]
        for z in self.jars:
            for n in z.namelist():
                m = re.match(r'data/([^/]+)/tags/(block|item)/(.+)\.json$', n)
                if not m:
                    continue
                try:
                    d = json.loads(z.read(n))
                except ValueError:
                    continue
                self.tags[(m.group(2), m.group(1) + ':' + m.group(3))] += [v if isinstance(v, str) else v.get('id') for v in d.get('values', [])]

    def read(self, path):
        for z in self.jars:
            try:
                return z.read(path)
            except KeyError:
                pass
        raise KeyError(path)

    def members(self, kind, tag, seen=None):
        seen = set() if seen is None else seen
        out = set()
        for v in self.tags.get((kind, tag), []):
            if v.startswith('#'):
                if v[1:] not in seen:
                    seen.add(v[1:])
                    out |= self.members(kind, v[1:], seen)
            else:
                out.add(v)
        return out

    def direct(self, kind, block):
        return {t for (k, t), vals in self.tags.items() if k == kind and block in vals}

    def texture(self, block):
        ns, path = block.split(':')
        model = json.loads(self.read(f'assets/{ns}/models/block/{path}.json'))
        tex = model['textures']['all']
        tns, tpath = tex.split(':') if ':' in tex else ('minecraft', tex)
        return np.array(Image.open(io.BytesIO(self.read(f'assets/{tns}/textures/{tpath}.png'))).convert('RGBA')).astype(int)


def spots(ore, stone):
    """The ore's spots: its pixels that are not stone. The ore textures sit on an older stone than stone.png (same grey
    levels, other places), so "differs from stone.png" marks most of the block. Instead a pixel is stone when its
    brightness is within 4 of one of stone.png's grey levels and it is nearly grey (chroma <= 12: redstone ore's
    pinkish stone stays stone). Everything else is ore: colour, coal's darks, the bright greys of silver and tin."""
    rgb = ore[..., :3]
    chroma = rgb.max(-1) - rgb.min(-1)
    v = rgb.mean(-1)
    levels = np.array(sorted(set(stone[..., 0].ravel().tolist())))
    off = np.min(np.abs(v[..., None] - levels[None, None, :]), -1) > 4
    return off | (chroma > 12)


def spots_own_stone(ore):
    """The spots of an ore painted on its own stone, a smooth grey ramp rather than stone.png's four levels (Electrical
    Age: lead, tungsten, cinnabar): colour (chroma > 20), or a grey well outside that stone's range (lead's bright
    flecks above 149, tungsten's dark ones below 70)."""
    rgb = ore[..., :3]
    chroma = rgb.max(-1) - rgb.min(-1)
    v = rgb.mean(-1)
    return (chroma > 20) | (v > 149) | (v < 70)


OWN_STONE = {'eln'}   # mods whose ores sit on their own stone texture


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--client', default=CLIENT)
    ap.add_argument('--neoforge', default=NEOFORGE[0] if NEOFORGE else None)
    ap.add_argument('--mods', default=MODS)
    ap.add_argument('--preview', help='write a contact sheet (stone source, deepslate source, riftstone ore; 16x) to this PNG')
    a = ap.parse_args()
    mods = sorted(j for j in glob.glob(os.path.join(a.mods, '*.jar')) if 'stasisdelvers' not in os.path.basename(j))
    pack = Pack([a.client, a.neoforge] + mods)

    stone_ores = pack.members('block', 'c:ores_in_ground/stone')
    deep_ores = pack.members('block', 'c:ores_in_ground/deepslate')
    material_tags = [t for (k, t) in pack.tags if k == 'block' and t.startswith('c:ores/')]
    by_mat = defaultdict(dict)
    for src in sorted(stone_ores | deep_ores):
        mats = [t[len('c:ores/'):] for t in material_tags if src in pack.members('block', t)]
        if len(mats) != 1:
            sys.exit(f'{src}: no single c:ores/<material> tag ({mats})')
        by_mat[mats[0]]['stone' if src in stone_ores else 'deepslate'] = src
    unknown = set(by_mat) - set(XP)
    if unknown:
        sys.exit(f'materials without an XP entry: {sorted(unknown)}')

    stone = pack.texture('minecraft:stone')
    rift = np.array(Image.open(os.path.join(A, 'textures', 'block', 'riftstone.png')).convert('RGBA')).astype(int)
    recipes = {}
    for n in zipfile.ZipFile(a.client).namelist():
        if re.match(r'data/minecraft/recipe/[a-z_]+_from_(smelting|blasting)_[a-z_]+\.json$', n):
            r = json.loads(pack.read(n))
            if isinstance(r.get('ingredient'), str):
                recipes.setdefault(r['ingredient'], []).append(r)

    pick = os.path.join(D, 'minecraft', 'tags', 'block', 'mineable', 'pickaxe.json')
    pick_vals = [v for v in json.load(open(pick))['values'] if not re.match(rf'{NS}:riftstone_.+_ore$', v)]
    # tag files this script owns are rewritten from scratch
    for f in glob.glob(os.path.join(D, '*', 'tags', '*', '**', '*.json'), recursive=True):
        try:
            vals = json.load(open(f)).get('values', [])
        except ValueError:
            continue
        if vals and all(isinstance(v, str) and re.match(rf'{NS}:riftstone_.+_ore$', v) for v in vals):
            os.remove(f)
    for f in glob.glob(os.path.join(D, NS, 'recipe', '*_riftstone_*_ore.json')):
        os.remove(f)

    lang_path = os.path.join(A, 'lang', 'en_us.json')
    lang = {k: v for k, v in json.load(open(lang_path)).items() if not re.match(rf'block\.{NS}\.riftstone_.+_ore$', k)}
    tags = defaultdict(set)   # (kind, tag) -> variants
    rows, sheet = [], []
    for mat in sorted(by_mat):
        s = by_mat[mat]
        src = s.get('stone') or s['deepslate']
        name = f'riftstone_{mat}_ore'
        vid = f'{NS}:{name}'
        ore = pack.texture(src)
        if src.split(':')[0] in OWN_STONE:
            mask = spots_own_stone(ore)
        elif s.get('stone'):
            mask = spots(ore, stone)
        else:   # a deepslate-only ore: spots are what differs from deepslate
            mask = np.abs(ore - pack.texture('minecraft:deepslate'))[..., :3].sum(-1) > 12
        n = int(mask.sum())
        if not 15 <= n <= 140:
            sys.exit(f'{src}: {n} spot pixels; is it painted on vanilla stone?')
        img = rift.copy()
        img[mask] = ore[mask]
        img[..., 3] = 255
        p = os.path.join(A, 'textures', 'block', name + '.png')
        Image.fromarray(img.astype(np.uint8)).save(p)
        sheet.append((mat, [pack.texture(x) for x in (s.get('stone'), s.get('deepslate')) if x], img))
        print(f'{name}: {n} spot pixels from {src}')

        model = f'{NS}:block/{name}'
        write_json(os.path.join(A, 'models', 'block', name + '.json'), {'parent': 'minecraft:block/cube_all', 'textures': {'all': model}})
        variants = {'lit=false': {'model': model}, 'lit=true': {'model': model}} if mat == 'redstone' else {'': {'model': model}}
        write_json(os.path.join(A, 'blockstates', name + '.json'), {'variants': variants})
        write_json(os.path.join(A, 'items', name + '.json'), {'model': {'type': 'minecraft:model', 'model': model}})
        lang[f'block.{NS}.{name}'] = 'Riftstone ' + ' '.join(w.capitalize() for w in mat.split('_')) + ' Ore'

        sns, spath = src.split(':')
        # a pack mod's ore: only with that mod loaded (a reference to a missing loot table stops the server; without the
        # mod the riftstone ore has no table and drops nothing, but the scatter never places it then)
        cond = {} if sns == 'minecraft' else {'neoforge:conditions': [{'type': 'neoforge:mod_loaded', 'modid': sns}]}
        write_json(os.path.join(D, NS, 'loot_table', 'blocks', name + '.json'), {
            **cond,
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:alternatives', 'children': [
                {'type': 'minecraft:item', 'condition': 'minecraft:tool/can_silk_touch', 'name': vid},
                {'type': 'minecraft:loot_table', 'value': f'{sns}:blocks/{spath}'}]}]}],
            'random_sequence': f'{NS}:blocks/{name}'})

        for kind in ('block', 'item'):
            for x in s.values():
                for t in pack.direct(kind, x) - SKIP_TAGS:
                    tags[(kind, t)].add(vid)
            tags[(kind, 'c:ores_in_ground/riftstone')].add(vid)
        pick_vals.append(vid)

        for r in recipes.get(src, []):
            kind = r['type'].split(':')[1]
            res = r['result']['id'].split(':')[1]
            write_json(os.path.join(D, NS, 'recipe', f'{res}_from_{kind}_{name}.json'), dict(r, ingredient=vid))

        lo, hi = XP[mat]
        rows.append(f'        new Entry("{name}", "{s.get("stone", "")}", "{s.get("deepslate", "")}", {lo}, {hi}, {RESISTANCE.get(mat, 3)}f, {str(mat == "redstone").lower()})')

    for (kind, t), vals in sorted(tags.items()):
        tns, tpath = t.split(':')
        write_json(os.path.join(D, tns, 'tags', kind, tpath + '.json'), {'replace': False, 'values': sorted(vals)})
    write_json(pick, {'replace': False, 'values': pick_vals})
    write_json(lang_path, lang)

    with open(JAVA, 'w') as f:
        f.write('package org.erisia.stasis;\n\n'
                '/** Generated by tools/ores/gen_riftstone_ores.py from the pack\'s ore tags; do not edit. The riftstone ores\n'
                ' * (λ-Nero): one per material in c:ores_in_ground/stone|deepslate, standing in for both sources in the rift\n'
                ' * scatter (DeepFeature.ore). Hardness 3 like every stone source; blast resistance and XP as the sources\'. */\n'
                'final class RiftOreTable {\n'
                '    private RiftOreTable() {\n    }\n\n'
                '    /** stone/deepslate sources: block ids, "" when the pack has none; redstone: a RedStoneOreBlock. */\n'
                '    record Entry(String name, String stoneSource, String deepslateSource, int xpMin, int xpMax, float resistance, boolean redstone) {\n    }\n\n'
                '    static final java.util.List<Entry> ENTRIES = java.util.List.of(\n' + ',\n'.join(rows) + ');\n}\n')

    if a.preview:
        k = 16
        w = 3 * (16 * k + 8) + 8
        out = Image.new('RGBA', (w, len(sheet) * (16 * k + 8) + 8), (24, 24, 28, 255))
        for i, (mat, srcs, img) in enumerate(sheet):
            y = 8 + i * (16 * k + 8)
            for j, im in enumerate(srcs + [img]):
                im = im.copy()
                im[..., 3] = 255
                out.paste(Image.fromarray(im.astype(np.uint8)).resize((16 * k, 16 * k), Image.NEAREST), (8 + (j if j < len(srcs) else 2) * (16 * k + 8), y))
        out.save(a.preview)


if __name__ == '__main__':
    main()

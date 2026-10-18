import sys, collections, statistics; sys.path.insert(0,'/var/lib/mclab/agent/tools'); import snbt
t=snbt.load('spawn_cottage.nbt')[1]
pal=[p[1]['id'][1] for p in t['palette'][1]]
blocks=t['blocks'][1]
be=collections.Counter(b[1]['nbt'][1]['id'][1] for b in blocks if 'nbt' in b[1])
print('size',[v[1] for v in t['size'][1]],'blocks',len(blocks),'be',be)
print('entities',collections.Counter(e[1]['nbt'][1]['id'][1] for e in t['entities'][1]))
natural={'minecraft:grass_block','minecraft:dirt','minecraft:stone','minecraft:coarse_dirt','minecraft:podzol','minecraft:sand','minecraft:gravel','minecraft:andesite','minecraft:diorite','minecraft:granite','minecraft:deepslate','minecraft:tuff','minecraft:dirt_path','minecraft:rooted_dirt','minecraft:snow_block','minecraft:moss_block'}
top={}
for b in blocks:
    x,y,z=[v[1] for v in b[1]['pos'][1]]
    if pal[b[1]['state'][1]] in natural: top[(x,z)]=max(top.get((x,z),-1),y)
W,H,D=[v[1] for v in t['size'][1]]
per=[top.get((x,z),-1) for x in range(W) for z in range(D) if x in (0,W-1) or z in (0,D-1)]
print('perimeter ground: median',statistics.median(per),'min',min(per),'max',max(per))
print(collections.Counter(per).most_common(10))
print('palette', pal)

"""Random layouts by the game rules (TOWER-DESIGN.md): H 4..100; 0..12 booms in the 4 cardinal directions at any
integer height 2..H-2 (above the 2-high core), length 2..(H - yb), at most one boom per (height, direction).
Weighted to realistic sizes: 75% of towers H <= 40; boom count skewed low; lengths skewed short."""
import json, os, random
HERE = os.path.dirname(os.path.abspath(__file__))


def layout(rng):
    H = rng.randint(4, 40) if rng.random() < 0.75 else rng.randint(41, 100)
    u = rng.random()
    nb = 0 if u < 0.2 else min(12, 1 + int(rng.expovariate(1 / 2.2)))
    slots = [(y, d) for y in range(2, H - 1) for d in 'ENWS']
    rng.shuffle(slots)
    booms = []
    for y, d in slots[:nb]:
        Lmax = H - y
        L = 2 + int((Lmax - 2) * rng.random() ** 1.7 + 0.5) if Lmax > 2 else 2
        booms.append((y, d, min(L, Lmax)))
    booms.sort()
    return dict(H=H, booms=booms)


if __name__ == '__main__':
    rng = random.Random(20261012)
    train = [layout(rng) for _ in range(400)]
    test = [layout(rng) for _ in range(150)]
    json.dump(dict(train=train, test=test), open(os.path.join(HERE, 'p3acov_layouts.json'), 'w'))
    import statistics as st
    for nm, ls in (('train', train), ('test', test)):
        Hs = [l['H'] for l in ls]; nbs = [len(l['booms']) for l in ls]
        print(nm, len(ls), 'H<=40:', sum(h <= 40 for h in Hs), 'median H', st.median(Hs), 'booms mean', st.mean(nbs),
              'zero-boom', sum(n == 0 for n in nbs), 'max L', max([b[2] for l in ls for b in l['booms']] or [0]))

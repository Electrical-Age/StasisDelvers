"""The parts of grow/ground_structure/gs.py (E, candidates, merge_chains) and grow/xbrace/xb.py (count_x) that the
library builder uses, copied verbatim (timestop-proto e060c08)."""
import numpy as np

SIG_T = 300.0       # tension allowable (force / area)
E = SIG_T / 1.25e-3 # strain at full tension stress ~ steel


def candidates(nodes, R):
    """All node pairs within R, except support-support pairs and pairs whose segment passes through another node."""
    n = len(nodes)
    D = np.linalg.norm(nodes[:, None] - nodes[None], axis=2)
    I, J = np.nonzero(np.triu(D <= R + 1e-9, 1))
    ok = ~((nodes[I, 1] == 0) & (nodes[J, 1] == 0))
    I, J = I[ok], J[ok]
    keep = np.ones(len(I), bool)
    for s in range(0, len(I), 2000):
        p, q = nodes[I[s:s + 2000]], nodes[J[s:s + 2000]]
        d = q - p
        L2 = np.sum(d * d, axis=1)
        w = nodes[None] - p[:, None]                      # (b, n, 3)
        t = np.einsum('bnk,bk->bn', w, d) / L2[:, None]
        perp = np.linalg.norm(w - t[..., None] * d[:, None], axis=2)
        hit = (t > 1e-6) & (t < 1 - 1e-6) & (perp < 0.05)
        keep[s:s + 2000] = ~hit.any(axis=1)
    return np.stack([I[keep], J[keep]], axis=1)




def merge_chains(nodes, bars, a, keep_nodes):
    """Merge collinear degree-2 pass-through nodes into single bars (area = max of the two)."""
    bars = [tuple(b) for b in bars]
    a = list(a)
    changed = True
    while changed:
        changed = False
        adj = {}
        for k, (i, j) in enumerate(bars):
            adj.setdefault(i, []).append(k)
            adj.setdefault(j, []).append(k)
        for nd, ks in adj.items():
            if nd in keep_nodes or len(ks) != 2:
                continue
            k1, k2 = ks
            o1 = bars[k1][0] if bars[k1][1] == nd else bars[k1][1]
            o2 = bars[k2][0] if bars[k2][1] == nd else bars[k2][1]
            u = nodes[o1] - nodes[nd]
            v = nodes[o2] - nodes[nd]
            if np.dot(u, v) / (np.linalg.norm(u) * np.linalg.norm(v)) < -0.9999:
                newa = max(a[k1], a[k2])
                for k in sorted((k1, k2), reverse=True):
                    del bars[k]
                    del a[k]
                bars.append((o1, o2))
                a.append(newa)
                changed = True
                break
    return np.array(bars, int), np.array(a)



def count_x(P, bars, cab):
    """Number of crossing cable pairs (an X): two cables whose segments pass within 0.15 of each other at
    interior points (not sharing a node)."""
    cb = bars[cab]
    nx = 0
    for a in range(len(cb)):
        for b in range(a + 1, len(cb)):
            if set(cb[a]) & set(cb[b]):
                continue
            p, r = P[cb[a, 0]], P[cb[a, 1]] - P[cb[a, 0]]
            q_, s = P[cb[b, 0]], P[cb[b, 1]] - P[cb[b, 0]]
            A = np.array([[r @ r, -r @ s], [r @ s, -s @ s]])
            if abs(np.linalg.det(A)) < 1e-9:
                continue
            t, u = np.linalg.solve(A, [(q_ - p) @ r, (q_ - p) @ s])
            if 0.05 < t < 0.95 and 0.05 < u < 0.95 and np.linalg.norm(p + t * r - q_ - u * s) < 0.15:
                nx += 1
    return nx

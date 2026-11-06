"""Eingebackene Verschattung (Ambient Occlusion) je Ecke: Oberflaeche in ein feines Voxelgitter rastern, von jeder
Ecke Strahlen in die Halbkugel der Normale schicken und zaehlen, wie viele die eigene Geometrie treffen.
Fugen (Parierstange/Klinge, Wicklung, Nieten) werden so dunkel - das macht viel vom echten Look aus.
"""
import math

import numpy as np


def _triangles(quads):
    tris = []
    for q in quads:
        tris.append((q[0], q[1], q[2]))
        if q[3] != q[2]:
            tris.append((q[0], q[2], q[3]))
    return np.array(tris)


def _rays(count, seed=5):
    """Kosinus-gewichtete Richtungen um +Z."""
    rng = np.random.default_rng(seed)
    u1, u2 = rng.random(count), rng.random(count)
    r = np.sqrt(u1)
    phi = 2 * math.pi * u2
    return np.stack([r * np.cos(phi), r * np.sin(phi), np.sqrt(1 - u1)], axis=1)


def bake(pos, nrm, quads, voxel=0.09, rays=48, max_dist=2.4, start=0.2):
    tris = _triangles(quads)
    lo = pos.min(axis=0) - 3.0
    hi = pos.max(axis=0) + 3.0
    dims = np.ceil((hi - lo) / voxel).astype(int) + 1
    grid = np.zeros(dims, dtype=bool)
    # Oberflaeche abtasten
    a, b, c = pos[tris[:, 0]], pos[tris[:, 1]], pos[tris[:, 2]]
    for i in range(len(tris)):
        e1, e2 = b[i] - a[i], c[i] - a[i]
        area = 0.5 * np.linalg.norm(np.cross(e1, e2))
        n = int(max(3, min(4000, area / (voxel * voxel) * 4)))
        k = int(math.ceil(math.sqrt(n)))
        s, t = np.meshgrid(np.linspace(0, 1, k), np.linspace(0, 1, k))
        s, t = s.ravel(), t.ravel()
        keep = s + t <= 1.0
        pts = a[i] + np.outer(s[keep], e1) + np.outer(t[keep], e2)
        idx = np.floor((pts - lo) / voxel).astype(int)
        grid[idx[:, 0], idx[:, 1], idx[:, 2]] = True
    base = _rays(rays)
    steps = np.arange(start, max_dist, voxel * 0.9)
    ao = np.ones(len(pos))
    for v in range(len(pos)):
        n = nrm[v]
        # Rahmen um die Normale
        t1 = np.cross(n, [0.0, 1.0, 0.0] if abs(n[1]) < 0.9 else [1.0, 0.0, 0.0])
        t1 /= np.linalg.norm(t1)
        t2 = np.cross(n, t1)
        dirs = base[:, :1] * t1 + base[:, 1:2] * t2 + base[:, 2:3] * n
        origin = pos[v] + n * voxel * 1.5
        pts = origin[None, None, :] + dirs[:, None, :] * steps[None, :, None]
        idx = np.floor((pts - lo) / voxel).astype(int)
        idx = np.clip(idx, 0, dims - 1)
        hit = grid[idx[..., 0], idx[..., 1], idx[..., 2]].any(axis=1)
        ao[v] = 1.0 - hit.mean()
    return np.clip(0.22 + 0.78 * ao ** 1.3, 0, 1)

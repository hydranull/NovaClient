"""Geometrie-Baukasten fuer die Tool-Skins (Nova Client).

Alle Masse in Sprite-Pixeln (1/16 Block). Werkzeug-Rahmen: Ursprung = Griffende, +Y = Richtung Kopf,
+X = Seite (Axt-/Hackenblatt zeigt nach -X), +Z = zum Betrachter (Flachseite einer Klinge).
"""
import math

import numpy as np


def unit(v):
    v = np.asarray(v, dtype=float)
    n = np.linalg.norm(v)
    return v / n if n > 1e-12 else v


def smoothstep(a, b, x):
    t = min(1.0, max(0.0, (x - a) / (b - a)))
    return t * t * (3.0 - 2.0 * t)


def lerp(a, b, t):
    return a + (b - a) * t


class Mesh:
    """Polygone mit Material je Ecke; Normalen entstehen in build() mit Auto-Smooth (Winkelgrenze je Flaeche)."""

    def __init__(self):
        self.pos = []
        self.uv = []
        self.mat = []
        self.faces = []  # (indices, smooth_deg)

    def vert(self, p, uv, mat):
        self.pos.append(np.asarray(p, dtype=float))
        self.uv.append((float(uv[0]), float(uv[1])))
        self.mat.append(int(mat))
        return len(self.pos) - 1

    def face(self, idx, smooth=35.0):
        self.faces.append((tuple(idx), float(smooth)))

    def add(self, other):
        off = len(self.pos)
        self.pos += other.pos
        self.uv += other.uv
        self.mat += other.mat
        self.faces += [(tuple(i + off for i in f), s) for f, s in other.faces]
        return self

    def transform(self, m):
        m = np.asarray(m, dtype=float)
        for i, p in enumerate(self.pos):
            self.pos[i] = m[:3, :3] @ p + m[:3, 3]
        if np.linalg.det(m[:3, :3]) < 0:
            self.faces = [(tuple(reversed(f)), s) for f, s in self.faces]
        return self

    def bounds(self):
        a = np.array(self.pos)
        return a.min(axis=0), a.max(axis=0)

    # ------------------------------------------------------------------ Normalen

    def build(self):
        """-> (pos[N,3], normal[N,3], uv[N,2], mat[N], quads[M,4]) mit getrennten Ecken an harten Kanten."""
        pos = np.array(self.pos)
        face_n = []
        for f, _ in self.faces:
            pts = pos[list(f)]
            n = np.zeros(3)
            for i in range(len(pts)):
                a, b = pts[i], pts[(i + 1) % len(pts)]
                n += np.array([(a[1] - b[1]) * (a[2] + b[2]), (a[2] - b[2]) * (a[0] + b[0]),
                               (a[0] - b[0]) * (a[1] + b[1])])
            face_n.append(n)
        face_n = np.array(face_n)
        face_u = np.array([unit(n) for n in face_n])
        keys = [tuple(np.round(p * 2000.0).astype(int)) for p in pos]
        by_key = {}
        for fi, (f, _) in enumerate(self.faces):
            for vi in f:
                by_key.setdefault(keys[vi], []).append(fi)
        out_pos, out_n, out_uv, out_mat = [], [], [], []
        index = {}
        quads = []
        for fi, (f, smooth) in enumerate(self.faces):
            cos_lim = math.cos(math.radians(smooth))
            corners = []
            for vi in f:
                acc = np.zeros(3)
                for fj in set(by_key[keys[vi]]):
                    if face_u[fj] @ face_u[fi] >= cos_lim:
                        acc += face_n[fj]
                n = unit(acc) if np.linalg.norm(acc) > 1e-12 else face_u[fi]
                k = (keys[vi], tuple(np.round(n * 300).astype(int)), round(self.uv[vi][0], 4),
                     round(self.uv[vi][1], 4), self.mat[vi])
                if k not in index:
                    index[k] = len(out_pos)
                    out_pos.append(pos[vi])
                    out_n.append(n)
                    out_uv.append(self.uv[vi])
                    out_mat.append(self.mat[vi])
                corners.append(index[k])
            if len(corners) == 4:
                quads.append(corners)
            elif len(corners) == 3:
                quads.append(corners + [corners[2]])
            else:
                for i in range(1, len(corners) - 1):
                    quads.append([corners[0], corners[i], corners[i + 1], corners[i + 1]])
        return (np.array(out_pos), np.array(out_n), np.array(out_uv), np.array(out_mat, dtype=int),
                np.array(quads, dtype=int))


# ---------------------------------------------------------------------- Bausteine

def _orient(mesh, first_face, center_fn):
    """Flaechen ab first_face so drehen, dass ihre Normalen von center_fn(Flaeche) weg zeigen (Mehrheit)."""
    pos = mesh.pos
    score = 0.0
    for f, _ in mesh.faces[first_face:]:
        pts = [pos[i] for i in f]
        c = sum(pts) / len(pts)
        n = np.cross(pts[1] - pts[0], pts[-1] - pts[0])
        score += float(n @ (c - center_fn(c)))
    if score < 0:
        mesh.faces[first_face:] = [(tuple(reversed(f)), s) for f, s in mesh.faces[first_face:]]


def rows_surface(mesh, rows, mat, tile, closed=True, smooth=35.0, cap0=None, cap1=None, uv_scale=(1.0, 1.0),
                 axis=None):
    """Flaeche aus Ringen (rows[i] = Liste von Punkten). closed = Ringe sind geschlossen.

    cap0/cap1: None, "flat" (Faecher zur Ringmitte) oder ein Punkt (Spitze). axis: Funktion Punkt -> Mittelpunkt
    fuer die Ausrichtung (Standard: Ringmitten)."""
    rows = [[np.asarray(p, dtype=float) for p in r] for r in rows]
    n_rows, k = len(rows), len(rows[0])
    centers = [sum(r) / len(r) for r in rows]
    # u entlang (Bogenlaenge der Mitten), v rundherum (Umfang)
    lengths = [0.0]
    for i in range(1, n_rows):
        lengths.append(lengths[-1] + float(np.linalg.norm(centers[i] - centers[i - 1])) + 1e-9)
    total = lengths[-1] if lengths[-1] > 0 else 1.0
    u0, v0, u1, v1 = tile
    first = len(mesh.faces)
    grid = []
    cols = k + 1 if closed else k
    for i, r in enumerate(rows):
        per = [0.0]
        for j in range(1, cols):
            per.append(per[-1] + float(np.linalg.norm(r[j % k] - r[j - 1])) + 1e-9)
        circ = per[-1] if per[-1] > 0 else 1.0
        line = []
        for j in range(cols):
            u = (lengths[i] / total) * uv_scale[0]
            v = (per[j] / circ) * uv_scale[1]
            line.append(mesh.vert(r[j % k], (lerp(u0, u1, min(u, 1.0)), lerp(v0, v1, min(v, 1.0))), mat))
        grid.append(line)
    for i in range(n_rows - 1):
        for j in range(cols - 1):
            mesh.face((grid[i][j], grid[i][j + 1], grid[i + 1][j + 1], grid[i + 1][j]), smooth)
    center_of = axis
    if center_of is None:
        def center_of(c):
            best = min(range(n_rows), key=lambda i: float(np.linalg.norm(centers[i] - c)))
            return centers[best]
    _orient(mesh, first, center_of)
    for cap, ri, sign in ((cap0, 0, -1), (cap1, n_rows - 1, 1)):
        if cap is None:
            continue
        r = rows[ri]
        start = len(mesh.faces)
        tip = centers[ri] if isinstance(cap, str) else np.asarray(cap, dtype=float)
        flat = isinstance(cap, str)
        uc = (u0 + u1) * 0.5, (v0 + v1) * 0.5
        c = mesh.vert(tip, uc, mat)
        ring = [mesh.vert(p, (lerp(u0, u1, 0.5 + 0.45 * math.cos(2 * math.pi * j / k)),
                              lerp(v0, v1, 0.5 + 0.45 * math.sin(2 * math.pi * j / k))), mat)
                for j, p in enumerate(r)]
        for j in range(k if closed else k - 1):
            mesh.face((c, ring[j], ring[(j + 1) % k]), 30.0 if flat else smooth)
        # Deckel zeigt vom Koerper weg (Richtung entlang der Achse)
        inner = centers[ri - sign] if n_rows > 1 else centers[ri] - np.array([0, sign, 0])
        _orient(mesh, start, lambda _c, inner=inner: inner)
    return grid


def frames(path, up=(0.0, 0.0, 1.0)):
    """Tangente T, Breite W (in der Sprite-Ebene, rechts von T) und Dicke D (~Z) je Pfadpunkt."""
    path = [np.asarray(p, dtype=float) for p in path]
    up = unit(up)
    out = []
    for i in range(len(path)):
        a = path[max(0, i - 1)]
        b = path[min(len(path) - 1, i + 1)]
        t = unit(b - a)
        w = np.cross(t, up)
        if np.linalg.norm(w) < 1e-6:
            w = np.cross(t, np.array([1.0, 0.0, 0.0]))
        w = unit(w)
        d = unit(np.cross(w, t))
        out.append((t, w, d))
    return out


def sweep(mesh, path, section, mat, tile, smooth=35.0, cap0=None, cap1=None, up=(0, 0, 1), closed=True,
          uv_scale=(1.0, 1.0)):
    """Profil entlang eines Pfads. section(i, s) -> Liste (w, d) im Rahmen (W, D); s = 0..1 entlang."""
    fr = frames(path, up)
    n = len(path)
    rows = []
    for i, p in enumerate(path):
        _, w, d = fr[i]
        s = i / (n - 1) if n > 1 else 0.0
        rows.append([np.asarray(p) + a * w + b * d for a, b in section(i, s)])
    path_pts = [np.asarray(p, dtype=float) for p in path]

    def axis(c):
        best = min(range(n), key=lambda i: float(np.linalg.norm(path_pts[i] - c)))
        return path_pts[best]

    cap_pts = []
    for cap, idx in ((cap0, 0), (cap1, n - 1)):
        if isinstance(cap, str) and cap == "point":
            cap_pts.append(path_pts[idx])
        else:
            cap_pts.append(cap)
    return rows_surface(mesh, rows, mat, tile, closed=closed, smooth=smooth, cap0=cap_pts[0], cap1=cap_pts[1],
                        uv_scale=uv_scale, axis=axis)


def lathe(mesh, profile, mat, tile, segments=16, smooth=40.0, base=(0.0, 0.0, 0.0), axis_rot=None, phase=0.0,
          cap0="flat", cap1="flat"):
    """Drehkoerper um die Y-Achse: profile = [(radius, y), ...] von unten nach oben."""
    rows = []
    for r, y in profile:
        ring = []
        for j in range(segments):
            a = phase + 2.0 * math.pi * j / segments
            ring.append(np.array([r * math.cos(a), y, -r * math.sin(a)]))
        rows.append(ring)
    m = Mesh()
    c0 = cap0 if profile[0][0] > 1e-4 else None
    c1 = cap1 if profile[-1][0] > 1e-4 else None
    if profile[0][0] <= 1e-4:
        rows = rows[1:]
        c0 = np.array([0.0, profile[0][1], 0.0])
    if profile[-1][0] <= 1e-4:
        rows = rows[:-1]
        c1 = np.array([0.0, profile[-1][1], 0.0])
    rows_surface(m, rows, mat, tile, closed=True, smooth=smooth, cap0=c0, cap1=c1,
                 axis=lambda c: np.array([0.0, c[1], 0.0]))
    t = np.eye(4)
    if axis_rot is not None:
        t[:3, :3] = axis_rot
    t[:3, 3] = base
    m.transform(t)
    return mesh.add(m)


def rot_x(deg):
    a = math.radians(deg)
    return np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])


def rot_y(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])


def rot_z(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), -math.sin(a), 0], [math.sin(a), math.cos(a), 0], [0, 0, 1]])


def matrix(rot=None, pos=(0, 0, 0), scale=1.0):
    m = np.eye(4)
    m[:3, :3] = (np.eye(3) if rot is None else np.asarray(rot)) * scale
    m[:3, 3] = pos
    return m


def rounded_rect(hw, hd, r=0.3, n=3):
    """Abgerundetes Rechteck im (w, d)-Rahmen, gegen den Uhrzeigersinn; r = Eckradius-Anteil."""
    rw, rd = hw * r, hd * r
    pts = []
    for cx, cy, a0 in ((hw - rw, hd - rd, 0), (-hw + rw, hd - rd, 90), (-hw + rw, -hd + rd, 180),
                       (hw - rw, -hd + rd, 270)):
        for i in range(n + 1):
            a = math.radians(a0 + 90.0 * i / n)
            pts.append((cx + rw * math.cos(a), cy + rd * math.sin(a)))
    return pts


def ellipse(hw, hd, n=12, phase=0.0):
    return [(hw * math.cos(phase + 2 * math.pi * i / n), hd * math.sin(phase + 2 * math.pi * i / n)) for i in range(n)]


class Noise:
    """Glatter 1D/2D-Wertrauschen mit festem Seed (fuer Steinkanten, Unebenheiten)."""

    def __init__(self, seed):
        self.rng = np.random.default_rng(seed)
        self.table = self.rng.random(1024)

    def n1(self, x):
        i = math.floor(x)
        f = x - i
        a = self.table[i % 1024]
        b = self.table[(i + 1) % 1024]
        f = f * f * (3 - 2 * f)
        return a + (b - a) * f

    def n2(self, x, y):
        return self.n1(x + 57.31 * math.floor(y)) * (1 - (y - math.floor(y))) + \
            self.n1(x + 57.31 * (math.floor(y) + 1)) * (y - math.floor(y))

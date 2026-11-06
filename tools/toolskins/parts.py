"""Bausteine fuer die Hyper-Tools-Modelle (Material-Kuerzel, Stiele, Ringe).

Rahmen siehe meshkit.py: Griffende bei y=0, Klinge Richtung +Y.
"""
import math

import numpy as np

from materials import MAT, mat_tile
from meshkit import ellipse, lathe, lerp, rows_surface, sweep  # noqa: F401 (rows_surface fuer energy.py)


def M(name):
    return MAT[name]


def T(name):
    return mat_tile(name)


def line(y0, y1, n, x=0.0, z=0.0):
    return [np.array([x, y0 + (y1 - y0) * i / (n - 1), z]) for i in range(n)]


def stick(m, y0, y1, r0, r1, mat, segs=12, bend=0.0, rows=None):
    """Stiel (leicht konisch, optional gebogen nach +X)."""
    n = rows or max(4, int((y1 - y0) / 0.7) + 2)
    path = [np.array([bend * math.sin(math.pi * i / (n - 1)), y0 + (y1 - y0) * i / (n - 1), 0.0]) for i in range(n)]
    sweep(m, path, lambda i, s: ellipse(lerp(r0, r1, s), lerp(r0, r1, s), segs), M(mat), T(mat), smooth=55,
          cap0="flat", cap1="flat")


def band(m, y, h, r, mat, segs=16):
    prof = [(r * 0.90, y - h / 2), (r, y - h / 2 + 0.18 * h), (r, y + h / 2 - 0.18 * h), (r * 0.90, y + h / 2)]
    lathe(m, prof, M(mat), T(mat), segments=segs, smooth=35)

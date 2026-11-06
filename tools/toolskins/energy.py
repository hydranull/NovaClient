"""Hyper Tools: Laserschwerter. Griff aus echtem Material (PBR), Leuchtakzente in der Farbe der Seltenheit (Holz weiss
.. Netherite rot). Die Klinge selbst zeichnet der Shader saber_blade im Spiel; hier steht nur ihr Verlauf.
"""
import math

import numpy as np

from parts import M, T, band, line, rows_surface, stick
from materials import RARITY
from meshkit import Mesh, lathe, rot_x, rounded_rect, sweep

TIER = {"wooden": "white", "stone": "green", "copper": "cyan", "iron": "blue", "golden": "gold", "diamond": "purple",
        "netherite": "red"}
HILT = {
    "wooden": dict(body="wood_dark", metal="brass", grip="leather_brown"),
    "stone": dict(body="steel_dark", metal="steel_satin", grip="leather_black"),
    "copper": dict(body="copper", metal="brass", grip="leather_brown"),
    "iron": dict(body="steel", metal="steel_dark", grip="leather_black"),
    "golden": dict(body="gold_plain", metal="steel_dark", grip="leather_black"),
    "diamond": dict(body="silver", metal="steel_dark", grip="leather_blue"),
    "netherite": dict(body="netherite", metal="steel_dark", grip="leather_black"),
}


class Energy:
    """Leuchtbaender eines Werkzeugs (Farbe = Seltenheit)."""

    def __init__(self, color):
        self.color = color
        self.paths = []

    def path(self, pts, width, intensity=1.0, show=0, delta=None, t=None):
        pts = [np.asarray(p, dtype=float) for p in pts]
        n = len(pts)
        if t is None:
            d = [0.0]
            for i in range(1, n):
                d.append(d[-1] + float(np.linalg.norm(pts[i] - pts[i - 1])))
            total = d[-1] if d[-1] > 0 else 1.0
            t = [x / total for x in d]
        widths = width if isinstance(width, (list, tuple)) else [width] * n
        deltas = delta if delta is not None else [np.zeros(3)] * n
        self.paths.append(dict(points=pts, delta=[np.asarray(x, dtype=float) for x in deltas], width=list(widths),
                               t=list(t), intensity=intensity, show=show))


# ---------------------------------------------------------------------- Bausteine

def ribbed_grip(m, y0, y1, r, mat, ribs=8):
    """Gummigriff mit Laengsrippen (T-Track)."""
    n = max(4, int((y1 - y0) / 0.35) + 2)

    def sec(i, s):
        pts = []
        for k in range(32):
            a = 2 * math.pi * k / 32
            rib = 0.5 + 0.5 * math.cos(ribs * a)
            pts.append(((r + 0.055 * (rib > 0.35)) * math.cos(a), (r + 0.055 * (rib > 0.35)) * math.sin(a)))
        return pts

    sweep(m, line(y0, y1, n), sec, M(mat), T(mat), smooth=40, cap0="flat", cap1="flat")


def emitter(m, y0, y1, r0, r1, mat, accent, teeth=6):
    """Emitter-Krone: aufgeweitete Huelse mit Zacken oben, innen ein leuchtender Ring."""
    rows = []
    prof = [(r0, y0), (r0 + 0.03, y0 + 0.2 * (y1 - y0)), (r1, y0 + 0.75 * (y1 - y0)), (r1, y1)]
    for j, (r, y) in enumerate(prof):
        ring = []
        for k in range(36):
            a = 2 * math.pi * k / 36
            yy = y
            if j == len(prof) - 1:
                tooth = (k * teeth / 36.0) % 1.0
                yy = y - (0.0 if tooth < 0.55 else 0.22)
            ring.append(np.array([r * math.cos(a), yy, -r * math.sin(a)]))
        rows.append(ring)
    inner = [np.array([(r1 - 0.08) * math.cos(2 * math.pi * k / 36), rows[-1][k][1],
                       -(r1 - 0.08) * math.sin(2 * math.pi * k / 36)]) for k in range(36)]
    rows.append(inner)
    rows.append([np.array([p[0] * 0.85, y1 - 0.25, p[2] * 0.85]) for p in inner])
    rows_surface(m, rows, M(mat), T(mat), closed=True, smooth=30, axis=lambda c: np.array([0.0, c[1], 0.0]))
    lathe(m, [(0.0, y1 - 0.3), (r1 - 0.2, y1 - 0.3), (r1 - 0.2, y1 - 0.22), (0.0, y1 - 0.2)], M(accent), T(accent),
          segments=20, smooth=40)


def accent_band(m, y, r, accent, h=0.12):
    band(m, y, h, r, accent, segs=24)


def pommel_cap(m, y0, r, mat, accent):
    lathe(m, [(0.0, y0), (r * 0.78, y0 + 0.02), (r, y0 + 0.14), (r, y0 + 0.46), (r * 0.9, y0 + 0.56)], M(mat), T(mat),
          segments=24, smooth=35)
    accent_band(m, y0 + 0.3, r + 0.005, accent)


def button(m, center, mat):
    sweep(m, [np.array(center) + np.array([0, -0.18, 0]), np.array(center) + np.array([0, 0.18, 0])],
          lambda i, s: rounded_rect(0.13, 0.07, 0.6), M(mat), T(mat), smooth=40, cap0="flat", cap1="flat")


# ---------------------------------------------------------------------- Laserschwert

def saber(material, extend):
    """Laserschwert: Griff mit Rippen, Bedienteil, Emitter-Krone; weisser Plasma-Kern."""
    h = HILT[material]
    accent = "accent_" + TIER[material]
    m = Mesh()
    pommel_cap(m, 0.0, 0.50, h["body"], accent)
    stick(m, 0.55, 1.0, 0.47, 0.47, h["body"], segs=24)
    ribbed_grip(m, 1.0, 3.1, 0.44, h["grip"])
    stick(m, 3.1, 4.15, 0.49, 0.49, h["body"], segs=24)
    accent_band(m, 3.2, 0.495, h["metal"], h=0.1)
    button(m, (0.0, 3.62, 0.5), accent)
    for y in (3.35, 3.9):
        lathe(m, [(0.0, 0.0), (0.07, 0.0), (0.05, 0.05), (0.0, 0.06)], M(h["metal"]), T(h["metal"]), segments=8,
              smooth=60, base=(0.0, y, 0.49), axis_rot=rot_x(90))
    emitter(m, 4.15, 5.3, 0.49, 0.62, h["metal"], accent)
    # Die Klinge rechnet der Shader saber_blade (glatt in jeder Groesse): hier nur Emitter -> Spitze und Leuchtradius
    e = Energy(RARITY[TIER[material]])
    e.path([(0, 5.15, 0), (0, 21.0, 0)], 1.8)
    return m, e


MATERIALS = ["wooden", "stone", "copper", "iron", "golden", "diamond", "netherite"]


def all_items():
    """(item_id, kind, mesh, [morphs], energy) - nur noch die Laserschwerter (Klinge = Shader, kein Morph)."""
    out = []
    for mat in MATERIALS:
        full, e = saber(mat, 1.0)
        out.append((f"{mat}_sword", "sword", full, [], e))
    return out

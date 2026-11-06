"""Materialien der Hyper Tools: Albedo-Atlas (RGBA, Alpha = 1 - Leuchtmaske), Material-Atlas (R Metall, G Rauheit,
B Hohlraum-Schatten, A Hoehe fuer das Feinrelief) und die Studio-Umgebung fuer die Spiegelungen.

Licht rechnet der Shader hyper_tool.fsh pro Pixel - render.py macht fuer die Vorschau dasselbe (shade_pixels).
"""
import math

import numpy as np

TILE = 256
COLS, ROWS = 8, 6

(T_STEEL, T_STEEL_DARK, T_SILVER, T_GOLD, T_GOLD_PLAIN, T_COPPER, T_NETHERITE, T_DIAMOND,
 T_STONE, T_WOOD_LIGHT, T_WOOD_DARK, T_WOOD_BLACK, T_LEATHER_BROWN, T_LEATHER_BLACK, T_LEATHER_RED, T_LEATHER_BLUE,
 T_CORD, T_RUBY, T_BREEZE, T_PRISMARINE, T_WOOD_CARVED, T_IVORY, T_STRING, T_IRON_DARK,
 T_STEEL_SATIN, T_FEATHER, T_FIREWORK, T_FLINT, T_MOLTEN, T_BRASS,
 T_PLASMA, T_ACCENT_WHITE, T_ACCENT_GREEN, T_ACCENT_CYAN, T_ACCENT_BLUE, T_ACCENT_GOLD, T_ACCENT_PURPLE,
 T_ACCENT_RED) = range(38)
# Plasma-Kerne in Seltenheitsfarbe (weiss gemischt): auch vor hellem Himmel farbig
PLASMA_TILES = {"white": 38, "green": 39, "cyan": 32 + 8, "blue": 33 + 8, "gold": 34 + 8, "purple": 35 + 8,
                "red": 36 + 8}

# Seltenheit: Holz weiss, Stein gruen, Kupfer tuerkis, Eisen blau, Gold gelb, Diamant lila, Netherite rot
RARITY = {
    "white": (0.86, 0.92, 1.0),
    "green": (0.22, 1.0, 0.32),
    "cyan": (0.12, 0.95, 0.92),
    "blue": (0.18, 0.48, 1.0),
    "gold": (1.0, 0.78, 0.12),
    "purple": (0.72, 0.28, 1.0),
    "red": (1.0, 0.10, 0.08),
}
ACCENT_TILES = {"white": T_ACCENT_WHITE, "green": T_ACCENT_GREEN, "cyan": T_ACCENT_CYAN, "blue": T_ACCENT_BLUE,
                "gold": T_ACCENT_GOLD, "purple": T_ACCENT_PURPLE, "red": T_ACCENT_RED}


class Material:
    def __init__(self, name, tile, glow=0.0):
        self.name = name
        self.tile = tile
        self.glow = glow


MATERIALS = [
    Material("steel", T_STEEL),
    Material("steel_dark", T_STEEL_DARK),
    Material("silver", T_SILVER),
    Material("gold", T_GOLD),
    Material("gold_plain", T_GOLD_PLAIN),
    Material("copper", T_COPPER),
    Material("netherite", T_NETHERITE, glow=1.0),
    Material("diamond", T_DIAMOND, glow=0.7),
    Material("stone", T_STONE),
    Material("wood_light", T_WOOD_LIGHT),
    Material("wood_mid", T_WOOD_LIGHT),
    Material("wood_dark", T_WOOD_DARK),
    Material("wood_black", T_WOOD_BLACK),
    Material("leather_brown", T_LEATHER_BROWN),
    Material("leather_black", T_LEATHER_BLACK),
    Material("leather_red", T_LEATHER_RED),
    Material("leather_blue", T_LEATHER_BLUE),
    Material("cord", T_CORD),
    Material("ruby", T_RUBY, glow=0.5),
    Material("breeze", T_BREEZE, glow=0.6),
    Material("prismarine", T_PRISMARINE, glow=0.8),
    Material("wood_carved", T_WOOD_CARVED),
    Material("iron_dark", T_IRON_DARK),
    Material("ivory", T_IVORY),
    Material("string", T_STRING),
    Material("steel_satin", T_STEEL_SATIN),
    Material("feather", T_FEATHER),
    Material("firework", T_FIREWORK, glow=0.4),
    Material("flint", T_FLINT),
    Material("molten", T_MOLTEN, glow=1.0),
    Material("brass", T_BRASS),
    Material("plasma", T_PLASMA, glow=1.0),
] + [Material("accent_" + name, tile, glow=0.95) for name, tile in ACCENT_TILES.items()] + [
    Material("plasma_" + name, tile, glow=1.0) for name, tile in PLASMA_TILES.items()]
MAT = {m.name: i for i, m in enumerate(MATERIALS)}


def tile_rect(tile):
    """UV-Rechteck einer Kachel mit 4 Texeln Rand (lineares Filtern blutet sonst in die Nachbarn)."""
    cx, cy = tile % COLS, tile // COLS
    w, h = COLS * TILE, ROWS * TILE
    pad = 4
    return ((cx * TILE + pad) / w, (cy * TILE + pad) / h, ((cx + 1) * TILE - pad) / w, ((cy + 1) * TILE - pad) / h)


def mat_tile(name):
    return tile_rect(MATERIALS[MAT[name]].tile)


# ---------------------------------------------------------------------- Rauschen

def _ss(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def _value_noise(shape, sx, sy, rng):
    h, w = shape
    gx, gy = int(math.ceil(w / sx)) + 2, int(math.ceil(h / sy)) + 2
    lat = rng.random((gy, gx))
    ys, xs = np.mgrid[0:h, 0:w].astype(float)
    fx, fy = xs / sx, ys / sy
    ix, iy = np.floor(fx).astype(int), np.floor(fy).astype(int)
    tx, ty = fx - ix, fy - iy
    tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    a, b = lat[iy, ix], lat[iy, ix + 1]
    c, d = lat[iy + 1, ix], lat[iy + 1, ix + 1]
    return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty


def _fbm(shape, sx, sy, rng, octaves=4, gain=0.5):
    total, amp, norm = np.zeros(shape), 1.0, 0.0
    for _ in range(octaves):
        total += amp * _value_noise(shape, max(1.0, sx), max(1.0, sy), rng)
        norm += amp
        amp *= gain
        sx, sy = sx / 2, sy / 2
    return total / norm


def _ridge(shape, sx, sy, rng, octaves=4):
    return 1.0 - np.abs(2.0 * _fbm(shape, sx, sy, rng, octaves) - 1.0)


def _scratches(shape, rng, count, length=(20, 120), along=None, width=1.0):
    """Feine Kratzer: Maske 0..1 (duenne Linien in zufaelliger oder vorgegebener Richtung)."""
    h, w = shape
    mask = np.zeros(shape)
    ys, xs = np.mgrid[0:h, 0:w].astype(float)
    for _ in range(count):
        x0, y0 = rng.random() * w, rng.random() * h
        ang = rng.random() * math.pi if along is None else along + rng.normal(0, 0.06)
        ln = rng.uniform(*length)
        dx, dy = math.cos(ang), math.sin(ang)
        # Abstand zur Strecke
        px, py = xs - x0, ys - y0
        t = np.clip(px * dx + py * dy, 0, ln)
        dist = np.hypot(px - t * dx, py - t * dy)
        mask = np.maximum(mask, np.exp(-(dist / width) ** 2) * rng.uniform(0.4, 1.0))
    return mask


class Tile:
    """Eine Kachel: Albedo (RGB 0..1), Leuchten (0..1), Metall, Rauheit, Hohlraum, Hoehe."""

    def __init__(self):
        s = (TILE, TILE)
        self.albedo = np.ones(s + (3,)) * 0.5
        self.glow = np.zeros(s)
        self.metal = np.zeros(s)
        self.rough = np.ones(s) * 0.5
        self.cavity = np.ones(s)
        self.height = np.ones(s) * 0.5


def _rgb(v, color):
    return v[..., None] * np.array(color)


# ---------------------------------------------------------------------- Kacheln

def metal_tile(rng, color, rough, brush=0.08, scratch=40, hammer=0.0, dirt=0.15):
    t = Tile()
    s = (TILE, TILE)
    streak = _fbm(s, 120, 1.5, rng, 3)
    spots = _fbm(s, 40, 40, rng, 4)
    scr = _scratches(s, rng, scratch, (15, 90), width=0.7)
    long_scr = _scratches(s, rng, scratch // 3, (60, 200), along=0.0, width=0.6)
    shade = 1.0 - brush * (streak - 0.5) - dirt * _ss(0.55, 0.85, spots) * 0.5
    t.albedo = np.clip(_rgb(shade, color), 0, 1)
    t.metal[:] = 1.0
    t.rough = np.clip(rough + 0.10 * (streak - 0.5) + 0.12 * scr + 0.06 * long_scr + 0.08 * _ss(0.6, 0.9, spots), 0.03, 1)
    t.height = 0.5 + 0.06 * (streak - 0.5) - 0.35 * scr - 0.2 * long_scr
    if hammer > 0:
        cells = _fbm(s, 14, 14, rng, 2)
        dimples = np.cos(cells * 18.0) * 0.5 + 0.5
        t.height += hammer * (dimples - 0.5)
        t.rough += 0.05 * dimples
    t.cavity = np.clip(1.0 - 0.5 * scr - 0.25 * _ss(0.7, 0.95, spots), 0, 1)
    return t


def engraved_gold(rng):
    t = metal_tile(rng, (1.0, 0.77, 0.34), 0.12, brush=0.05, scratch=20, dirt=0.1)
    ys, xs = np.mgrid[0:TILE, 0:TILE] / TILE
    # Rankenmuster in der Mitte (laeuft entlang u = Klingenlaenge)
    vine = np.abs(np.sin(xs * 46 + 2.4 * np.sin(ys * 10.0 * math.pi + xs * 6)))
    curl = np.abs(np.sin(np.hypot((xs * 12) % 1 - 0.5, (ys * 3) % 1 - 0.5) * 22))
    groove = ((vine < 0.09) | (curl < 0.06)) & (np.abs(ys - 0.5) < 0.30) & (np.abs(ys - 0.5) > 0.04)
    groove = groove.astype(float)
    t.height -= 0.45 * groove
    t.cavity = np.clip(t.cavity - 0.55 * groove, 0, 1)
    t.rough += 0.25 * groove
    t.albedo *= (1.0 - 0.25 * groove)[..., None]
    return t


def copper_tile(rng):
    t = metal_tile(rng, (0.96, 0.64, 0.50), 0.26, brush=0.1, scratch=30, dirt=0.2)
    s = (TILE, TILE)
    pat = _fbm(s, 18, 18, rng, 5)
    patina = _ss(0.70, 0.80, pat) * 0.8
    crust = _fbm(s, 6, 6, rng, 3)
    pcol = np.array([0.22, 0.45, 0.38]) * (0.8 + 0.4 * crust[..., None])
    t.albedo = t.albedo * (1 - patina[..., None]) + pcol * patina[..., None]
    t.metal = 1.0 - patina
    t.rough = t.rough * (1 - patina) + (0.75 + 0.15 * crust) * patina
    t.height += 0.25 * patina * (crust - 0.3)
    t.cavity = np.clip(t.cavity - 0.15 * patina, 0, 1)
    return t


def damascus_netherite(rng):
    t = Tile()
    s = (TILE, TILE)
    ys, xs = np.mgrid[0:TILE, 0:TILE] / TILE
    warp = _fbm(s, 60, 30, rng, 4)
    layers = np.sin((ys * 9 + warp * 3.2 + xs * 1.5) * math.pi * 2)
    fine = _fbm(s, 3, 3, rng, 2)
    band = 0.5 + 0.5 * layers
    t.albedo = np.clip(_rgb(0.15 + 0.11 * band + 0.03 * fine, (1.0, 0.96, 1.02)), 0, 1)
    t.metal[:] = 1.0
    t.rough = 0.22 + 0.22 * (1 - band) + 0.05 * fine
    t.height = 0.5 + 0.12 * (band - 0.5)
    # Glut-Risse
    ridge = _ridge(s, 90, 50, rng, 4)
    veins = _ss(0.965, 0.993, ridge) * _ss(0.45, 0.6, _fbm(s, 80, 80, rng, 2))
    t.glow = veins
    t.albedo = t.albedo * (1 - veins[..., None]) + np.array([1.0, 0.30, 0.05]) * veins[..., None]
    t.metal = 1 - 0.8 * veins
    t.rough = t.rough * (1 - veins) + 0.6 * veins
    t.height -= 0.35 * veins
    t.cavity = np.clip(1.0 - 0.3 * veins, 0, 1)
    return t


def diamond_tile(rng):
    t = Tile()
    s = (TILE, TILE)
    inner = _fbm(s, 24, 24, rng, 3)
    frac = _ridge(s, 40, 40, rng, 3)
    t.albedo = np.clip(_rgb(0.70 + 0.25 * inner - 0.25 * _ss(0.9, 0.98, frac), (0.40, 0.86, 1.0)), 0, 1)
    t.metal[:] = 0.55
    t.rough = 0.03 + 0.05 * _ss(0.9, 0.98, frac)
    t.height = 0.5 - 0.1 * _ss(0.9, 0.98, frac)
    sparkle = (rng.random(s) > 0.9975).astype(float)
    t.glow = sparkle
    t.albedo = t.albedo * (1 - sparkle[..., None]) + sparkle[..., None]
    return t


def stone_tile(rng):
    t = Tile()
    s = (TILE, TILE)
    ys, xs = np.mgrid[0:TILE, 0:TILE] / TILE
    mott = _fbm(s, 20, 20, rng, 5)
    speck = (rng.random(s) > 0.94) * 0.12
    # Muschelbruch: konzentrische Wellen um zufaellige Punkte
    shell = np.zeros(s)
    for _ in range(9):
        cx, cy = rng.random(), rng.random()
        r = np.hypot(xs - cx, (ys - cy) * 0.7)
        shell += np.exp(-r * 4) * np.sin(r * 60 + rng.random() * 6)
    t.albedo = np.clip(_rgb(0.30 + 0.12 * mott - speck, (1.0, 0.96, 0.92)), 0, 1)
    t.rough = 0.5 + 0.2 * mott
    t.height = 0.5 + 0.18 * shell + 0.1 * (mott - 0.5)
    t.cavity = np.clip(0.85 + 0.15 * mott, 0, 1)
    return t


def wood_tile(rng, light, dark, rough, rings=8.0, pores=0.25):
    t = Tile()
    s = (TILE, TILE)
    warp = _fbm(s, 120, 40, rng, 3)
    ys = np.mgrid[0:TILE, 0:TILE][0] / TILE
    g = (ys * rings + warp * 1.8) % 1.0
    late = np.exp(-((g - 0.5) / 0.14) ** 2)
    fiber = _fbm(s, 80, 1.6, rng, 2)
    pore = (_fbm(s, 6, 1.2, rng, 1) > 0.78).astype(float) * pores
    v = np.clip(0.6 * late + 0.25 * fiber + pore, 0, 1)
    t.albedo = np.clip(_rgb(1 - v, light) + _rgb(v, dark), 0, 1)
    t.rough = rough + 0.15 * fiber + 0.2 * pore
    t.height = 0.5 + 0.08 * (fiber - 0.5) - 0.3 * pore - 0.05 * late
    t.cavity = np.clip(1.0 - 0.4 * pore, 0, 1)
    return t


def leather_tile(rng, color, dark):
    t = Tile()
    s = (TILE, TILE)
    cells = _fbm(s, 4, 4, rng, 2)
    blot = _fbm(s, 50, 50, rng, 4)
    wear = _ss(0.6, 0.85, _fbm(s, 70, 20, rng, 3))
    grain = _ss(0.35, 0.65, cells)
    v = np.clip(0.55 * grain + 0.45 * blot, 0, 1)
    t.albedo = np.clip(_rgb(1 - v * 0.55, color) + _rgb(v * 0.55, dark) + _rgb(wear * 0.08, (1, 1, 1)), 0, 1)
    t.rough = 0.62 - 0.25 * wear + 0.1 * grain
    t.height = 0.5 + 0.25 * (grain - 0.5)
    t.cavity = np.clip(0.8 + 0.2 * grain, 0, 1)
    return t


def cord_tile(rng, color=(0.78, 0.68, 0.50)):
    t = Tile()
    s = (TILE, TILE)
    ys, xs = np.mgrid[0:TILE, 0:TILE] / TILE
    strand = (xs * 30 + ys * 6) % 1.0
    ridge = np.sin(strand * math.pi)
    fib = _fbm(s, 3, 3, rng, 2)
    t.albedo = np.clip(_rgb(0.55 + 0.4 * ridge - 0.12 * fib, color), 0, 1)
    t.rough = 0.85 - 0.1 * ridge
    t.height = 0.25 + 0.6 * ridge + 0.1 * fib
    t.cavity = np.clip(0.5 + 0.5 * ridge, 0, 1)
    return t


def gem_tile(rng, color):
    t = Tile()
    s = (TILE, TILE)
    inner = _fbm(s, 16, 16, rng, 3)
    t.albedo = np.clip(_rgb(0.7 + 0.3 * inner, color), 0, 1)
    t.metal[:] = 0.4
    t.rough[:] = 0.04
    sparkle = (rng.random(s) > 0.997).astype(float)
    t.glow = sparkle
    return t


def make_tiles(seed=7):
    rng = np.random.default_rng(seed)
    tiles = {}
    tiles[T_STEEL] = metal_tile(rng, (0.80, 0.81, 0.84), 0.13, brush=0.07, scratch=45)
    tiles[T_STEEL_SATIN] = metal_tile(rng, (0.74, 0.75, 0.78), 0.30, brush=0.12, scratch=60)
    tiles[T_STEEL_DARK] = metal_tile(rng, (0.34, 0.35, 0.38), 0.36, brush=0.1, scratch=50, hammer=0.25, dirt=0.3)
    tiles[T_IRON_DARK] = metal_tile(rng, (0.30, 0.31, 0.34), 0.42, brush=0.1, scratch=40, hammer=0.35, dirt=0.35)
    tiles[T_SILVER] = metal_tile(rng, (0.95, 0.95, 0.97), 0.08, brush=0.04, scratch=25)
    tiles[T_GOLD] = engraved_gold(rng)
    tiles[T_GOLD_PLAIN] = metal_tile(rng, (1.0, 0.79, 0.38), 0.10, brush=0.04, scratch=20, dirt=0.08)
    tiles[T_COPPER] = copper_tile(rng)
    tiles[T_NETHERITE] = damascus_netherite(rng)
    tiles[T_DIAMOND] = diamond_tile(rng)
    tiles[T_STONE] = stone_tile(rng)
    tiles[T_WOOD_LIGHT] = wood_tile(rng, (0.66, 0.48, 0.30), (0.42, 0.27, 0.15), 0.55)
    tiles[T_WOOD_CARVED] = wood_tile(rng, (0.74, 0.56, 0.36), (0.50, 0.33, 0.19), 0.45, rings=5.0)
    tiles[T_WOOD_DARK] = wood_tile(rng, (0.33, 0.21, 0.13), (0.17, 0.10, 0.06), 0.28, pores=0.15)
    tiles[T_WOOD_BLACK] = wood_tile(rng, (0.11, 0.09, 0.09), (0.04, 0.03, 0.03), 0.32, rings=10.0, pores=0.1)
    tiles[T_LEATHER_BROWN] = leather_tile(rng, (0.40, 0.24, 0.14), (0.18, 0.10, 0.05))
    tiles[T_LEATHER_BLACK] = leather_tile(rng, (0.10, 0.10, 0.11), (0.03, 0.03, 0.035))
    tiles[T_LEATHER_RED] = leather_tile(rng, (0.50, 0.06, 0.07), (0.22, 0.02, 0.03))
    tiles[T_LEATHER_BLUE] = leather_tile(rng, (0.10, 0.16, 0.38), (0.03, 0.05, 0.15))
    tiles[T_CORD] = cord_tile(rng)
    tiles[T_STRING] = cord_tile(rng, (0.86, 0.84, 0.78))
    tiles[T_RUBY] = gem_tile(rng, (0.90, 0.06, 0.10))
    ivory = wood_tile(rng, (0.90, 0.86, 0.76), (0.80, 0.74, 0.62), 0.32, rings=3.0, pores=0.05)
    tiles[T_IVORY] = ivory
    # Breeze-Rute: hell, glatt, leuchtende Spiralbaender
    br = Tile()
    ys, xs = np.mgrid[0:TILE, 0:TILE] / TILE
    band = 0.5 + 0.5 * np.sin((xs * 10 + ys) * 2 * math.pi)
    br.albedo = _rgb(0.75 + 0.2 * band, (0.80, 0.90, 1.0))
    br.rough[:] = 0.18
    br.height = 0.5 + 0.2 * band
    br.glow = _ss(0.75, 1.0, band) * 0.8
    br.albedo = br.albedo * (1 - br.glow[..., None]) + np.array([0.55, 0.85, 1.0]) * br.glow[..., None]
    tiles[T_BREEZE] = br
    # Prismarin: tuerkises Metall mit leuchtenden Adern
    pr = metal_tile(rng, (0.32, 0.74, 0.68), 0.22, brush=0.06, scratch=20)
    crack = _ss(0.94, 0.99, _ridge((TILE, TILE), 50, 50, rng, 3))
    pr.glow = crack
    pr.albedo = pr.albedo * (1 - crack[..., None]) + np.array([0.55, 1.0, 0.92]) * crack[..., None]
    pr.height -= 0.3 * crack
    tiles[T_PRISMARINE] = pr
    fe = Tile()
    fe.albedo = _rgb(0.85 + 0.1 * _fbm((TILE, TILE), 2, 30, rng, 2), (0.95, 0.95, 0.92))
    fe.rough[:] = 0.7
    fe.height = 0.5 + 0.3 * np.sin(ys * 120)
    tiles[T_FEATHER] = fe
    fw = Tile()
    fw.albedo = _rgb(0.6 + 0.3 * _fbm((TILE, TILE), 8, 8, rng, 2), (0.85, 0.12, 0.12))
    fw.rough[:] = 0.6
    tiles[T_FIREWORK] = fw
    # Feuerstein: dunkel, glaenzend, Muschelbruch
    fl = stone_tile(rng)
    mott = _fbm((TILE, TILE), 30, 30, rng, 4)
    fl.albedo = np.clip(_rgb(0.13 + 0.10 * mott, (1.0, 0.93, 0.86)), 0, 1)
    fl.rough = np.clip(0.22 + 0.12 * mott, 0, 1)
    tiles[T_FLINT] = fl
    # Glut-Rinne: leuchtend, fliessende Schlieren
    mo = Tile()
    flow = _fbm((TILE, TILE), 60, 10, rng, 4)
    mo.albedo = np.clip(_rgb(0.75 + 0.25 * flow, (1.0, 0.42, 0.08)), 0, 1)
    mo.glow = 0.75 + 0.25 * flow
    mo.rough[:] = 0.5
    tiles[T_MOLTEN] = mo
    tiles[T_BRASS] = metal_tile(rng, (0.90, 0.74, 0.44), 0.22, brush=0.06, scratch=30, dirt=0.25)
    # Plasma-Kern: weiss, voll leuchtend
    pl = Tile()
    pl.albedo[:] = 1.0
    pl.glow[:] = 1.0
    pl.rough[:] = 0.4
    tiles[T_PLASMA] = pl
    # Leuchtstreifen am Griff in der Seltenheitsfarbe
    for name, tile in ACCENT_TILES.items():
        ac = Tile()
        ys2, xs2 = np.mgrid[0:TILE, 0:TILE] / TILE
        stripe = 0.85 + 0.15 * np.sin(xs2 * 40 * math.pi)
        ac.albedo = _rgb(stripe, RARITY[name])
        ac.glow[:] = 1.0
        ac.rough[:] = 0.3
        tiles[tile] = ac
    for name, tile in PLASMA_TILES.items():
        pc = Tile()
        pc.albedo[:] = np.array(RARITY[name]) * 0.78 + 0.22
        pc.glow[:] = 1.0
        pc.rough[:] = 0.4
        tiles[tile] = pc
    return tiles


def make_atlas(seed=7):
    """-> (albedo RGBA, material RGBA) als float-Arrays 0..1."""
    tiles = make_tiles(seed)
    albedo = np.zeros((ROWS * TILE, COLS * TILE, 4))
    albedo[..., :3] = 0.5
    albedo[..., 3] = 1.0
    material = np.zeros((ROWS * TILE, COLS * TILE, 4))
    material[..., 1] = 0.5
    material[..., 2] = 1.0
    material[..., 3] = 0.5
    for index, t in tiles.items():
        cx, cy = index % COLS, index // COLS
        sl = (slice(cy * TILE, (cy + 1) * TILE), slice(cx * TILE, (cx + 1) * TILE))
        albedo[sl + (slice(0, 3),)] = np.clip(t.albedo, 0, 1)
        albedo[sl + (3,)] = 1.0 - np.clip(t.glow, 0, 1)
        material[sl + (0,)] = np.clip(t.metal, 0, 1)
        material[sl + (1,)] = np.clip(t.rough, 0, 1)
        material[sl + (2,)] = np.clip(t.cavity, 0, 1)
        material[sl + (3,)] = np.clip(t.height, 0, 1)
    return albedo, material


# ---------------------------------------------------------------------- Studio-Umgebung

ENV_W, ENV_H, ENV_LEVELS = 512, 256, 4


def _dirs():
    v, u = np.mgrid[0:ENV_H, 0:ENV_W]
    u = (u + 0.5) / ENV_W
    v = (v + 0.5) / ENV_H
    phi = (u - 0.5) * 2 * math.pi
    theta = v * math.pi
    # wie im Shader: u = atan(x, z) / 2pi + 0.5, v = acos(y) / pi
    x = np.sin(theta) * np.sin(phi)
    z = np.sin(theta) * np.cos(phi)
    y = np.cos(theta)
    return np.stack([x, y, z], axis=-1)


def _softbox(d, center, size, intensity, color=(1.0, 1.0, 1.0), soft=0.02):
    """Rechteckige Leuchtflaeche um die Richtung center (Halbwinkel size = (breite, hoehe) in Radiant)."""
    c = np.array(center, dtype=float)
    c /= np.linalg.norm(c)
    up = np.array([0.0, 1.0, 0.0])
    right = np.cross(up, c)
    if np.linalg.norm(right) < 1e-6:
        right = np.array([1.0, 0.0, 0.0])
    right /= np.linalg.norm(right)
    up2 = np.cross(c, right)
    facing = d @ c
    px = np.arctan2(d @ right, facing)
    py = np.arctan2(d @ up2, facing)
    inside = _ss(size[0] + soft, size[0] - soft, np.abs(px)) * _ss(size[1] + soft, size[1] - soft, np.abs(py))
    inside *= facing > 0
    return inside[..., None] * np.array(color) * intensity


def make_env():
    """Studio-Licht in Blickraum-Richtungen (Kamera schaut nach -Z, +Z zeigt zum Betrachter), HDR-Werte."""
    d = _dirs()
    y = d[..., 1]
    sky = np.where(y[..., None] > 0, 0.55 + 0.45 * _ss(0.0, 0.9, y)[..., None], 0.0) * np.array([0.88, 0.92, 1.0])
    floor = np.where(y[..., None] <= 0, 0.10 + 0.10 * _ss(-1.0, 0.0, y)[..., None], 0.0) * np.array([1.0, 0.97, 0.94])
    horizon = np.exp(-(y / 0.035) ** 2)[..., None] * 0.9
    env = sky + floor + horizon
    # dunkle Flaggen (Kontrast in Chrom-Spiegelungen)
    env *= 1.0 - 0.75 * _softbox(d, (0.9, 0.15, 0.35), (0.35, 0.5), 1.0, soft=0.06)[..., :1]
    env *= 1.0 - 0.6 * _softbox(d, (-0.2, 0.05, -1.0), (0.5, 0.35), 1.0, soft=0.08)[..., :1]
    env += _softbox(d, (-0.55, 0.62, 0.55), (0.30, 0.18), 7.0)
    env += _softbox(d, (-0.85, 0.15, -0.5), (0.16, 0.45), 3.2, (1.0, 0.96, 0.9))
    env += _softbox(d, (0.75, 0.25, -0.62), (0.05, 0.55), 6.0, (0.92, 0.96, 1.0))
    env += _softbox(d, (0.0, 0.98, 0.15), (0.6, 0.08), 2.5)
    env += _softbox(d, (0.35, 0.1, 0.93), (0.25, 0.12), 1.6, (1.0, 0.95, 0.88))
    return env


def _blur_env(img, sigma):
    if sigma <= 0:
        return img
    out = img.copy()
    # vertikal
    k = int(sigma * 3) + 1
    xs = np.arange(-k, k + 1)
    w = np.exp(-(xs / sigma) ** 2 / 2)
    w /= w.sum()
    pad = np.pad(out, ((k, k), (0, 0), (0, 0)), mode="edge")
    out = sum(w[i] * pad[i:i + ENV_H] for i in range(len(xs)))
    # horizontal, breiter zu den Polen hin
    res = np.empty_like(out)
    for row in range(ENV_H):
        lat = math.sin(math.pi * (row + 0.5) / ENV_H)
        s = min(ENV_W / 3, sigma / max(lat, 0.05))
        kk = int(s * 3) + 1
        xs2 = np.arange(-kk, kk + 1)
        w2 = np.exp(-(xs2 / s) ** 2 / 2)
        w2 /= w2.sum()
        line = out[row]
        idx = (np.arange(ENV_W)[:, None] + xs2[None, :]) % ENV_W
        res[row] = (line[idx] * w2[None, :, None]).sum(axis=1)
    return res


def make_env_atlas():
    """4 Stufen (spiegelnd .. sehr rau) untereinander, gespeichert als c / (1 + c)."""
    env = make_env()
    levels = [env, _blur_env(env, 3.0), _blur_env(env, 9.0), _blur_env(env, 26.0)]
    stack = np.concatenate(levels, axis=0)
    return stack / (1.0 + stack), levels

"""Baut die Hyper Tools: Albedo-/Material-Atlas, Studio-Umgebung und Modelle (tools.ntm) fuer den Client,
dazu Vorschau-Bilder mit demselben Licht wie im Spiel.

Aufruf (aus diesem Ordner):  python build.py [--preview ZIELORDNER] [--only sword] [--no-export]
Ausgabe: src/client/resources/assets/nova_client/toolskins/{atlas.png, material.png, env.png, tools.ntm}
"""
import argparse
import os
import struct
import sys

import numpy as np
from PIL import Image

import ao
import energy
import render
from materials import MATERIALS, make_atlas, make_env_atlas

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "src", "client", "resources", "assets", "nova_client", "toolskins")

TOOL_TYPES = ["sword", "pickaxe", "axe", "shovel", "hoe", "spear", "mace", "trident", "bow", "crossbow"]
# Leuchten: 0 = keins, 1 = Glut-Welle, 2 = Funkeln, 3 = sanftes Pulsieren
PULSE = {"netherite": 1, "diamond": 2, "ruby": 2, "breeze": 3, "prismarine": 3, "firework": 3}


def write_ntm(path, items):
    """Format NTM3 (big endian, wie DataInputStream):
    int magic 'NTM3'; int materialCount; je Material: float glow, byte pulse
    int meshCount; je Mesh: UTF id, byte type, float[6] bounds, int vertices, int quads, int morphs,
      je Ecke: short x,y,z (Pixel * 1000); short nx,ny,nz; ushort u,v; byte material; ubyte ao
      je Morph (Zielform fuer Zuenden/Spannen/Laden): je Ecke short x,y,z (* 1000); short nx,ny,nz
      je Viereck: ushort i0..i3
      Energie: float r,g,b; int paths; je Band: int points, float intensity, byte show,
        je Punkt: float x,y,z, float dx,dy,dz (Morph 0), float halbe Breite, float t (0..1 ab Emitter)"""
    with open(path, "wb") as f:
        f.write(struct.pack(">I", 0x4E544D33))
        f.write(struct.pack(">i", len(MATERIALS)))
        for m in MATERIALS:
            f.write(struct.pack(">fb", m.glow, PULSE.get(m.name, 0)))
        f.write(struct.pack(">i", len(items)))
        for item_id, kind, data, morphs, glow in items:
            pos, nrm, uv, mat, quads, occ = data
            name = item_id.encode("utf-8")
            f.write(struct.pack(">H", len(name)) + name)
            f.write(struct.pack(">b", TOOL_TYPES.index(kind)))
            lo, hi = pos.min(axis=0), pos.max(axis=0)
            f.write(struct.pack(">6f", *lo, *hi))
            f.write(struct.pack(">iii", len(pos), len(quads), len(morphs)))
            if len(pos) > 65535:
                raise ValueError(f"{item_id}: zu viele Ecken ({len(pos)})")
            buf = bytearray()
            for i in range(len(pos)):
                n = np.clip(nrm[i], -1, 1)
                buf += struct.pack(">3h3h2HbB", *(int(round(c * 1000)) for c in pos[i]),
                                   *(int(round(c * 32767)) for c in n),
                                   int(round(np.clip(uv[i][0], 0, 1) * 65535)),
                                   int(round(np.clip(uv[i][1], 0, 1) * 65535)), int(mat[i]),
                                   int(round(np.clip(occ[i], 0, 1) * 255)))
            for mp, mn in morphs:
                for i in range(len(pos)):
                    n = np.clip(mn[i], -1, 1)
                    buf += struct.pack(">3h3h", *(int(round(c * 1000)) for c in mp[i]),
                                       *(int(round(c * 32767)) for c in n))
            for q in quads:
                buf += struct.pack(">4H", *q)
            buf += struct.pack(">3f", *glow.color)
            buf += struct.pack(">i", len(glow.paths))
            for p in glow.paths:
                buf += struct.pack(">ifb", len(p["points"]), p["intensity"], p["show"])
                for i, pt in enumerate(p["points"]):
                    buf += struct.pack(">3f3f2f", *pt, *p["delta"][i], p["width"][i], p["t"][i])
            f.write(buf)


def to_png(img, path, mode=None):
    arr = (np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)
    Image.fromarray(arr, mode).save(path, optimize=True)


def glow_values(data):
    mat = data[3]
    return np.array([MATERIALS[m].glow for m in mat])


def preview(items, albedo, material, levels, folder):
    os.makedirs(folder, exist_ok=True)
    kinds = []
    for _, k, _, _, _ in items:
        if k not in kinds:
            kinds.append(k)
    by_kind = {k: [it for it in items if it[1] == k] for k in kinds}
    fw, fh = 300, 300
    for k in kinds:
        row = np.ones((fh * 2, fw * len(by_kind[k]), 3)) * 0.12
        for c, (item_id, kind, data, _, glow_paths) in enumerate(by_kind[k]):
            glow = glow_values(data)
            # oben: Ego-Sicht (rechte Haelfte eines 16:9-Bilds, FOV 70)
            if kind == "spear":
                frame = render.sprite_frame(1.0, origin=(15.7, 0.3, 8.0), flip=True)
                disp = ((-20, 90, -35), (3.13, 2.0, 0.13), (1.36, 1.36, 0.68))
            else:
                frame = render.sprite_frame(1.0)
                disp = ((0, -90, 25), (1.13, 3.2, 1.13), (0.68, 0.68, 0.68))
            mm = render.first_person_matrix(disp) @ frame
            full = render.render(data, mm, albedo, material, levels, size=(2 * fw, 2 * fw * 9 // 16), fov=70,
                                 glow_of=glow, energy=glow_paths)
            crop = full[:, full.shape[1] // 2:]
            ys = np.linspace(0, crop.shape[0] - 1, fh).astype(int)
            xs = np.linspace(0, crop.shape[1] - 1, fw).astype(int)
            row[:fh, c * fw:(c + 1) * fw] = crop[ys][:, xs]
            # unten: Nahaufnahme schraeg (wie in der Hand gedreht)
            r = render.m4(render.rot_xyz(-18, 32, 0))
            near = render.translate(0, 0, -1.7) @ r @ render.translate(-0.5, -0.5, -0.5) @ render.sprite_frame(
                0.88 if kind == "spear" else 1.0)
            row[fh:, c * fw:(c + 1) * fw] = render.render(data, near, albedo, material, levels, size=(fw, fh), fov=40,
                                                          glow_of=glow, energy=glow_paths)
        to_png(row, os.path.join(folder, f"pbr_{k}.png"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview")
    ap.add_argument("--only")
    ap.add_argument("--no-export", action="store_true")
    ap.add_argument("--no-ao", action="store_true")
    args = ap.parse_args()
    albedo, material = make_atlas()
    env_encoded, levels = make_env_atlas()
    items = []
    for item_id, kind, mesh, morph_meshes, glow_paths in energy.all_items():
        if args.only and kind != args.only and item_id != args.only:
            continue
        data = mesh.build()
        occ = np.ones(len(data[0])) if args.no_ao else ao.bake(data[0], data[1], data[4])
        morphs = []
        for target in morph_meshes:
            md = target.build()
            if len(md[0]) != len(data[0]):
                raise ValueError(f"{item_id}: Morph hat andere Eckenzahl")
            morphs.append((md[0], md[1]))
        lo, hi = data[0].min(axis=0), data[0].max(axis=0)
        print(f"{item_id:20s} {len(data[0]):5d} Ecken {len(data[4]):5d} Vierecke  "
              f"x {lo[0]:6.2f}..{hi[0]:5.2f} y {lo[1]:5.2f}..{hi[1]:5.2f} z {lo[2]:5.2f}..{hi[2]:5.2f}"
              + (f"  {len(morphs)} Morph" if morphs else ""))
        items.append((item_id, kind, data + (occ,), morphs, glow_paths))
    if not args.no_export and not args.only:
        os.makedirs(OUT, exist_ok=True)
        to_png(albedo, os.path.join(OUT, "atlas.png"), "RGBA")
        to_png(material, os.path.join(OUT, "material.png"), "RGBA")
        to_png(env_encoded, os.path.join(OUT, "env.png"), "RGB")
        old = os.path.join(OUT, "glow.png")
        if os.path.exists(old):
            os.remove(old)
        write_ntm(os.path.join(OUT, "tools.ntm"), items)
        for f in ("atlas.png", "material.png", "env.png", "tools.ntm"):
            print(f"  {f}: {os.path.getsize(os.path.join(OUT, f)) // 1024} KB")
    if args.preview:
        preview(items, albedo, material, levels, args.preview)


if __name__ == "__main__":
    sys.exit(main())

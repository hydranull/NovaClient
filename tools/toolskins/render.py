"""Software-Vorschau der Hyper Tools (numpy): erst ein G-Buffer (Normale, Position, UV, Verschattung) per
Rasterizer, dann pro Pixel dasselbe Licht wie hyper_tool.fsh (Studio-Umgebung, Fresnel, GGX, Feinrelief, ACES)."""
import math

import numpy as np

S2 = 1.0 / math.sqrt(2.0)


def sprite_frame(scale=1.0, origin=(1.3, 1.3, 8.0), flip=False):
    """Werkzeug-Rahmen -> Modellraum (0..1): Griffende unten links, Kopf oben rechts (wie die Vanilla-Sprites)."""
    m = np.eye(4)
    if not flip:
        x_axis, y_axis = np.array([S2, -S2, 0]), np.array([S2, S2, 0])
    else:
        x_axis, y_axis = np.array([S2, S2, 0]), np.array([-S2, S2, 0])
    m[:3, 0] = x_axis * scale / 16.0
    m[:3, 1] = y_axis * scale / 16.0
    m[:3, 2] = np.array([0, 0, 1.0]) * scale / 16.0
    m[:3, 3] = np.array(origin) / 16.0
    return m


def rot_xyz(x, y, z):
    x, y, z = map(math.radians, (x, y, z))
    rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return rx @ ry @ rz


def m4(rot=None, t=(0, 0, 0), s=(1, 1, 1)):
    m = np.eye(4)
    r = np.eye(3) if rot is None else rot
    m[:3, :3] = r @ np.diag(s)
    m[:3, 3] = t
    return m


def translate(x, y, z):
    return m4(t=(x, y, z))


def first_person_matrix(display=((0, -90, 25), (1.13, 3.2, 1.13), (0.68, 0.68, 0.68))):
    """Kette wie ItemInHandRenderer (rechte Hand, ohne Schlag): Arm -> Display-Transform -> -0.5."""
    rot, tr, sc = display
    arm = translate(0.56, -0.52, -0.72)
    disp = translate(*(np.array(tr) / 16.0)) @ m4(rot_xyz(*rot)) @ m4(s=sc)
    return arm @ disp @ translate(-0.5, -0.5, -0.5)


def sample(tex, uv):
    h, w = tex.shape[:2]
    x = np.clip(uv[:, 0] * w - 0.5, 0, w - 1.001)
    y = np.clip(uv[:, 1] * h - 0.5, 0, h - 1.001)
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    fx, fy = (x - x0)[:, None], (y - y0)[:, None]
    a, b = tex[y0, x0], tex[y0, x0 + 1]
    c, d = tex[y0 + 1, x0], tex[y0 + 1, x0 + 1]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def env_lookup(levels, d, level):
    """Wie env() im Shader: Richtung -> (u, v), zwischen zwei Unschaerfe-Stufen mischen."""
    u = np.arctan2(d[:, 0], d[:, 2]) / (2 * math.pi) + 0.5
    v = np.arccos(np.clip(d[:, 1], -1, 1)) / math.pi
    uv = np.stack([u % 1.0, v], axis=1)
    l0 = np.floor(level).astype(int)
    l1 = np.minimum(l0 + 1, len(levels) - 1)
    f = (level - l0)[:, None]
    out = np.zeros((len(d), 3))
    for k in range(len(levels)):
        m0, m1 = l0 == k, l1 == k
        if m0.any():
            out[m0] += sample(levels[k], uv[m0]) * (1 - f[m0])
        if m1.any():
            out[m1] += sample(levels[k], uv[m1]) * f[m1]
    return out


def shade_pixels(n, v, p_dx, p_dy, h_dx, h_dy, albedo, material, ao, glow, levels, bump=0.0035):
    metal, rough, cavity = material[:, 0], np.clip(material[:, 1], 0.035, 1), material[:, 2]
    # Feinrelief
    r1 = np.cross(p_dy, n)
    r2 = np.cross(n, p_dx)
    det = np.sum(p_dx * r1, axis=1)
    grad = np.sign(det)[:, None] * (h_dx[:, None] * r1 + h_dy[:, None] * r2)
    bumped = np.abs(det)[:, None] * n - bump * grad
    ln = np.linalg.norm(bumped, axis=1, keepdims=True)
    n = np.where(ln > 1e-9, bumped / np.maximum(ln, 1e-9), n)
    base = albedo[:, :3] ** 2.2
    nv = np.clip(np.sum(n * v, axis=1), 0, 1)
    f0 = 0.04 * (1 - metal[:, None]) + base * metal[:, None]
    fres = f0 + (np.maximum(1 - rough[:, None], f0) - f0) * ((1 - nv) ** 5)[:, None]
    r = 2 * nv[:, None] * n - v
    spec = env_lookup(levels, r, rough * (len(levels) - 1))
    irr = env_lookup(levels, n, np.full(len(n), len(levels) - 1.0))
    l = np.array([-0.45, 0.65, 0.62])
    l /= np.linalg.norm(l)
    h = l + v
    h /= np.linalg.norm(h, axis=1, keepdims=True)
    nl = np.clip(n @ l, 0, None)
    nh = np.clip(np.sum(n * h, axis=1), 0, None)
    a2 = (rough * rough) ** 2
    dd = nh * nh * (a2 - 1) + 1
    dist = a2 / (math.pi * dd * dd)
    k = (rough + 1) ** 2 / 8
    geo = nl / (nl * (1 - k) + k) * nv / (nv * (1 - k) + k)
    fl = f0 + (1 - f0) * ((1 - np.clip(np.sum(h * v, axis=1), 0, 1)) ** 5)[:, None]
    sun = (dist * geo / np.maximum(4 * nl * nv, 1e-3) * nl * 2.2)[:, None] * fl
    a = (ao * (1 - 0.9 * (1 - cavity)))[:, None]
    diffuse = (1 - metal)[:, None] * base * (irr * 0.85 + nl[:, None] * 0.55) * (1 - fres)
    color = (diffuse + spec * fres) * a + sun * (1 - 0.6 * (1 - a))
    color += base * (1 - albedo[:, 3:4]) * glow[:, None] * 2.6
    color *= 1.35
    lum = np.maximum(color @ np.array([0.2126, 0.7152, 0.0722]), 1e-4)
    mapped = np.clip((lum * (2.51 * lum + 0.03)) / (lum * (2.43 * lum + 0.59) + 0.14), 0, 1)
    color = np.clip(color * (mapped / lum)[:, None], 0, 1)
    return color ** (1 / 2.2)


def project(p, W, H, ortho, fov):
    if ortho is not None:
        x0, x1, y0, y1 = ortho
        return (p[:, 0] - x0) / (x1 - x0) * W, (1 - (p[:, 1] - y0) / (y1 - y0)) * H, np.full(len(p), W / (x1 - x0))
    f = 1.0 / math.tan(math.radians(fov) / 2)
    aspect = W / H
    z = np.maximum(-p[:, 2], 1e-3)
    return (p[:, 0] * f / aspect / z * 0.5 + 0.5) * W, (1 - (p[:, 1] * f / z * 0.5 + 0.5)) * H, f / z * 0.5 * H


def halos(img, energy, model_matrix, ortho, fov):
    """Leuchtbaender wie hyper_glow.fsh: weicher farbiger Schein + weisser Kern, additiv."""
    H, W, _ = img.shape
    color = np.array(energy.color)
    add = np.zeros_like(img)
    for path in energy.paths:
        if path["show"] != 0:
            continue
        pts = np.array(path["points"])
        widths = np.array(path["width"])
        dense, dw = [], []
        for i in range(len(pts)):
            if i + 1 < len(pts):
                n = max(2, int(np.linalg.norm(pts[i + 1] - pts[i]) / 0.15))
                for f in np.linspace(0, 1, n, endpoint=False):
                    dense.append(pts[i] + (pts[i + 1] - pts[i]) * f)
                    dw.append(widths[i] + (widths[i + 1] - widths[i]) * f)
            else:
                dense.append(pts[i])
                dw.append(widths[i])
        dense = np.array(dense)
        P = np.c_[dense, np.ones(len(dense))] @ model_matrix.T
        scale = np.linalg.norm(model_matrix[:3, 0])
        sx, sy, k = project(P[:, :3], W, H, ortho, fov)
        rad = np.array(dw) * scale * k
        for x, y, r in zip(sx, sy, rad):
            r = max(r, 1.0)
            x0, x1 = int(max(0, x - r * 2)), int(min(W, x + r * 2 + 1))
            y0, y1 = int(max(0, y - r * 2)), int(min(H, y + r * 2 + 1))
            if x0 >= x1 or y0 >= y1:
                continue
            gy, gx = np.mgrid[y0:y1, x0:x1]
            d2 = ((gx - x) ** 2 + (gy - y) ** 2) / (r * r)
            halo = np.exp(-d2 * 4.5) * 0.55 + np.exp(-d2 * 18) * 0.55
            core = np.exp(-d2 * 90) * 0.9
            add[y0:y1, x0:x1] = np.maximum(add[y0:y1, x0:x1], halo[..., None] * color * path["intensity"]
                                           + core[..., None])
    return np.clip(img + add, 0, 1)


def render(data, model_matrix, atlas, material, levels, size=(256, 256), ortho=None, fov=70.0,
           bg=(0.13, 0.14, 0.17), ss=2, glow_level=1.0, glow_of=None, ao=None, energy=None):
    """data = (pos, nrm, uv, mat, quads[, ao]) im Werkzeug-Rahmen. ortho = (x0, x1, y0, y1) oder None."""
    pos, nrm, uv, mat, quads = data[:5]
    vao = data[5] if len(data) > 5 else np.ones(len(pos))
    W, H = size[0] * ss, size[1] * ss
    P = np.c_[pos, np.ones(len(pos))] @ model_matrix.T
    p = P[:, :3]
    nm = np.linalg.inv(model_matrix[:3, :3]).T
    n = nrm @ nm.T
    n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-12
    if ortho is not None:
        x0, x1, y0, y1 = ortho
        sx = (p[:, 0] - x0) / (x1 - x0) * W
        sy = (1 - (p[:, 1] - y0) / (y1 - y0)) * H
        sz = -p[:, 2]
    else:
        f = 1.0 / math.tan(math.radians(fov) / 2)
        aspect = W / H
        sx = (p[:, 0] * f / aspect / -p[:, 2] * 0.5 + 0.5) * W
        sy = (1 - (p[:, 1] * f / -p[:, 2] * 0.5 + 0.5)) * H
        sz = -p[:, 2]
    zb = np.full((H, W), np.inf)
    gb_n = np.zeros((H, W, 3))
    gb_p = np.zeros((H, W, 3))
    gb_uv = np.zeros((H, W, 2))
    gb_ao = np.ones((H, W))
    gb_glow = np.zeros((H, W))
    gglow = np.zeros(len(pos)) if glow_of is None else glow_of
    tris = []
    for q in quads:
        tris.append((q[0], q[1], q[2]))
        if q[3] != q[2]:
            tris.append((q[0], q[2], q[3]))
    for t in np.array(tris):
        xs, ys, zs = sx[t], sy[t], sz[t]
        if ortho is None and np.any(zs <= 0.01):
            continue
        minx, maxx = int(max(0, math.floor(xs.min()))), int(min(W - 1, math.ceil(xs.max())))
        miny, maxy = int(max(0, math.floor(ys.min()))), int(min(H - 1, math.ceil(ys.max())))
        if minx > maxx or miny > maxy:
            continue
        area = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0])
        if abs(area) < 1e-9:
            continue
        gy, gx = np.mgrid[miny:maxy + 1, minx:maxx + 1]
        px, py = gx + 0.5, gy + 0.5
        w0 = ((xs[1] - px) * (ys[2] - py) - (xs[2] - px) * (ys[1] - py)) / area
        w1 = ((xs[2] - px) * (ys[0] - py) - (xs[0] - px) * (ys[2] - py)) / area
        w2 = 1 - w0 - w1
        inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if not inside.any():
            continue
        z = w0 * zs[0] + w1 * zs[1] + w2 * zs[2]
        sel = inside & (z < zb[gy, gx])
        if not sel.any():
            continue
        yy, xx = gy[sel], gx[sel]
        b0, b1, b2 = w0[sel][:, None], w1[sel][:, None], w2[sel][:, None]
        zb[yy, xx] = z[sel]
        nn = b0 * n[t[0]] + b1 * n[t[1]] + b2 * n[t[2]]
        nn /= np.linalg.norm(nn, axis=1, keepdims=True) + 1e-12
        # beidseitig: zur Kamera drehen
        flip = np.sum(nn * (-(b0 * p[t[0]] + b1 * p[t[1]] + b2 * p[t[2]]) if ortho is None else np.array([0, 0, 1.0])),
                      axis=1) < 0
        nn[flip] *= -1
        gb_n[yy, xx] = nn
        gb_p[yy, xx] = b0 * p[t[0]] + b1 * p[t[1]] + b2 * p[t[2]]
        gb_uv[yy, xx] = b0 * uv[t[0]] + b1 * uv[t[1]] + b2 * uv[t[2]]
        gb_ao[yy, xx] = (b0 * vao[t[0]] + b1 * vao[t[1]] + b2 * vao[t[2]])[:, 0]
        gb_glow[yy, xx] = (b0 * gglow[t[0]] + b1 * gglow[t[1]] + b2 * gglow[t[2]])[:, 0]
    covered = np.isfinite(zb)
    img = np.ones((H, W, 3)) * np.array(bg)
    if covered.any():
        mat_px = sample(material, gb_uv[covered])
        alb_px = sample(atlas, gb_uv[covered])
        height = np.zeros((H, W))
        height[covered] = mat_px[:, 3]
        # Ableitungen wie dFdx/dFdy (Vorwaertsdifferenz, am Rand rueckwaerts)
        def ddx(a):
            d = np.zeros_like(a)
            d[:, :-1] = a[:, 1:] - a[:, :-1]
            d[:, -1] = d[:, -2]
            return d

        def ddy(a):
            d = np.zeros_like(a)
            d[:-1] = a[1:] - a[:-1]
            d[-1] = d[-2]
            return d
        edge = ~(np.roll(covered, -1, 1) & np.roll(covered, -1, 0))
        hdx, hdy = ddx(height), -ddy(height)
        pdx, pdy = ddx(gb_p), -ddy(gb_p)
        hdx[edge], hdy[edge] = 0, 0
        if ortho is not None:
            v = np.tile([0.0, 0.0, 1.0], (covered.sum(), 1))
        else:
            v = -gb_p[covered]
            v /= np.linalg.norm(v, axis=1, keepdims=True) + 1e-12
        col = shade_pixels(gb_n[covered], v, pdx[covered], pdy[covered], hdx[covered], hdy[covered], alb_px,
                           mat_px, gb_ao[covered], gb_glow[covered] * glow_level, levels)
        img[covered] = col
    if energy is not None:
        img = halos(img, energy, model_matrix, ortho, fov)
    if ss > 1:
        img = img.reshape(size[1], ss, size[0], ss, 3).mean(axis=(1, 3))
    return img

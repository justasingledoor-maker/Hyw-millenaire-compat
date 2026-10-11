"""
Item icons drawn from the pieces themselves: each piece's model is ray-cast (orthographic, a helmet seen three-quarters
from above, a coat, trousers or boots face on) into a 16x16 grid, each pixel taking the colour of the texel it hits on
the piece's own painted texture, lit by the face it lands on, and outlined. Dyeable texels stay on the tinted base
layer, the rest on the overlay, as the hand-drawn icons do. Used for every piece without a hand-drawn icon.
"""
import math

from PIL import Image

OUT = (38, 34, 30)


def _rot(rx, ry, rz):
    """Minecraft's ModelPart rotation (ZYX) as a 3x3 matrix."""
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    rzm = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    rym = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rxm = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    return _mul(_mul(rzm, rym), rxm)


def _mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _apply(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def _t(m):
    return [[m[j][i] for j in range(3)] for i in range(3)]


def _cubes(roots):
    """Every cube with its part's world rotation and translation (a point p in the part goes to R p + T)."""
    out = []

    def walk(p, R, T):
        r = _rot(*p.rot)
        R2 = _mul(R, r)
        T2 = [T[i] + _apply(R, p.pivot)[i] for i in range(3)]
        for c in p.cubes:
            out.append((c, R2, T2))
        for ch in p.children:
            walk(ch, R2, T2)
    ident = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    for r in roots:
        walk(r, ident, [0, 0, 0])
    return out


def _texel(c, face, a, b):
    """The texel of cube c (texture units) for a hit at fractions a, b across the face (a: as a ray walks the axes below)."""
    w, h, d = c.size
    u, v = c.uv

    def ix(f, n):
        return min(n - 1, max(0, int(f * n)))
    if face == "x0":   # the wearer's right side: s from the back
        return u + ix(1 - a, d), v + d + ix(b, h)
    if face == "x1":
        return u + d + w + ix(a, d), v + d + ix(b, h)
    if face == "z0":   # the front
        return u + d + ix(a, w), v + d + ix(b, h)
    if face == "z1":
        return u + 2 * d + w + ix(1 - a, w), v + d + ix(b, h)
    if face == "y0":   # the top
        return u + d + ix(a, w), v + ix(1 - b, d)
    return u + d + w + ix(a, w), v + ix(1 - b, d)


SHADE = {"z0": 1.0, "y0": 1.12, "x0": 0.8, "x1": 0.8, "y1": 0.6, "z1": 0.7}


# the limbs spread for the icon, as the hand-drawn silhouettes are: (pivot shift, z rotation) by root part
SPREAD = {"CHESTPLATE": {"right_arm": ((-1.2, 0, 0), 0.0), "left_arm": ((1.2, 0, 0), 0.0)},
          "LEGGINGS": {"right_leg": ((-1.2, 0, 0), 0.0), "left_leg": ((1.2, 0, 0), 0.0)},
          "BOOTS": {"right_leg": ((-2.2, 0, 0), 0.0), "left_leg": ((2.2, 0, 0), 0.0)}}


def render(model, canvas, slot, scale=1):
    """A 16x16 icon of the piece: (base, overlay) images."""
    yaw, pitch = (math.radians(-28), math.radians(-22)) if slot == "HELMET" else (0.0, math.radians(-6))
    view = _mul(_rot(pitch, 0, 0), _rot(0, yaw, 0))
    cubes = []
    saved = []
    for r in model.roots:
        sp = SPREAD.get(slot, {}).get(r.name)
        if sp:
            saved.append((r, r.pivot, r.rot))
            r.pivot = tuple(r.pivot[i] + sp[0][i] for i in range(3))
            r.rot = (r.rot[0], r.rot[1], r.rot[2] + sp[1])
    placed = _cubes(model.roots)
    for r, pv, rt in saved:
        r.pivot, r.rot = pv, rt
    for c, R, T in placed:
        g = c.inflate
        lo = [c.origin[i] - g for i in range(3)]
        hi = [c.origin[i] + c.size[i] + g for i in range(3)]
        M = _mul(view, R)
        VT = _apply(view, T)
        corners = [_apply(M, [x, y, z]) for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
        corners = [[p[i] + VT[i] for i in range(3)] for p in corners]
        cubes.append((c, M, VT, lo, hi, corners))
    xs = [p[0] for cb in cubes for p in cb[5]]
    ys = [p[1] for cb in cubes for p in cb[5]]
    if slot == "HELMET":
        kx = ky = max(max(xs) - min(xs), max(ys) - min(ys)) / 14.0
    else:
        # a coat, trousers or boots fill the icon's height as the hand-drawn silhouettes do (stretched at most 1.8 times)
        kx, ky = (max(xs) - min(xs)) / 14.0, (max(ys) - min(ys)) / 14.0
        kx, ky = max(kx, ky / 1.8), max(ky, kx / 1.8)
    cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
    grid = [[None] * 16 for _ in range(16)]
    for py in range(16):
        for px in range(16):
            vx, vy = cx + (px + 0.5 - 8) * kx, cy + (py + 0.5 - 8) * ky
            best = None
            for c, M, VT, lo, hi, _ in cubes:
                Mi = _t(M)
                o = _apply(Mi, [vx - VT[0], vy - VT[1], -1000 - VT[2]])
                dvec = _apply(Mi, [0, 0, 1])
                t0, t1, face = -1e9, 1e9, None
                for ax, (n0, n1) in enumerate((("x0", "x1"), ("y0", "y1"), ("z0", "z1"))):
                    if abs(dvec[ax]) < 1e-9:
                        if o[ax] < lo[ax] or o[ax] > hi[ax]:
                            t0, t1 = 1, 0
                        continue
                    ta, tb = (lo[ax] - o[ax]) / dvec[ax], (hi[ax] - o[ax]) / dvec[ax]
                    fa = n0 if dvec[ax] > 0 else n1
                    if ta > tb:
                        ta, tb = tb, ta
                    if ta > t0:
                        t0, face = ta, fa
                    t1 = min(t1, tb)
                if face is None or t0 > t1:
                    continue
                if best is not None and t0 >= best[0]:
                    continue
                hit = [o[i] + dvec[i] * t0 for i in range(3)]
                fr = [(hit[i] - lo[i]) / max(1e-6, hi[i] - lo[i]) for i in range(3)]
                a, b = {"x0": (fr[2], fr[1]), "x1": (fr[2], fr[1]), "z0": (fr[0], fr[1]), "z1": (fr[0], fr[1]),
                        "y0": (fr[0], fr[2]), "y1": (fr[0], fr[2])}[face]
                tu, tv = _texel(c, face, a, b)
                for sx in range(scale):
                    pxl = canvas.px.get((tu * scale + sx, tv * scale + scale // 2))
                    if pxl:
                        break
                if pxl is None:
                    continue                                  # a transparent texel: look behind it
                best = (t0, pxl, face)
            if best:
                (rgb, dye), face = best[1], best[2]
                f = SHADE[face]
                grid[py][px] = (tuple(max(0, min(255, int(ch * f))) for ch in rgb), dye)
    base = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    over = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for py in range(16):
        for px in range(16):
            cell = grid[py][px]
            if cell is None:
                if any(0 <= px + dx < 16 and 0 <= py + dy < 16 and grid[py + dy][px + dx] is not None
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    over.putpixel((px, py), OUT + (255,))
                continue
            rgb, dye = cell
            if dye:
                g = rgb[0]
                base.putpixel((px, py), (g, g, g, 255))
            else:
                over.putpixel((px, py), rgb + (255,))
    return base, over


def composite(base, over, tint):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            r, g, b, a = over.getpixel((x, y))
            if a:
                img.putpixel((x, y), (r, g, b, 255))
                continue
            gr = base.getpixel((x, y))
            if gr[3]:
                f = gr[0] / 255
                img.putpixel((x, y), (int(tint[0] * f), int(tint[1] * f), int(tint[2] * f), 255))
    return img

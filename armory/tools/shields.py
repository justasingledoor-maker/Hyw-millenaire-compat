"""
The cultures' shields, made as Epic Knights makes its own (MedievalShieldItem: a 'plate' and a 'handle', drawn by its
heraldry renderer from a texture in the shield atlas), in the frames of Epic Knights' round and kite shields so its
hand-held display fits them. The plate is built of row slices following the outline, so the rim is solid all round,
with a boss and a domed centre where the shield had one. Each shield carries its own painting (not paintable with
banners), drawn at twice the density of Epic Knights' (SCALE texels per unit).

  Byzantium (10th-12th c.):
    skoutarion       the infantry's kite shield (Praecepta Militaria; the military saints' shields): crimson, a gilt rim
                     with pearls, a gold cross
    aspis            the round shield of the skoutatoi and menavlatoi: blue, a red band with pearls, white rays, an iron boss
    varangian_shield the Varangian Guard's round Norse shield: planks painted in red and white quarters, an iron boss,
                     a rawhide rim
  The Seljuks (11th-13th c.):
    kalkan           the Turkic shield of coiled cane bound with coloured thread, convex, a steel boss
    sipar            the round shield of the ghulams: leather over wood, dark red, a gold eight-pointed Seljuk star round
                     a domed boss
  Japan:
    tedate           the hand-held plank shield (te-date) with a painted mon (the maru ni jūji: a cross in a ring); samurai
                     carried none, nor did the Inuit
  India (Mughal and Rajput, 16th-17th c.):
    dhal             the round, convex shield of buffalo or rhinoceros hide, lacquered dark, four gilt bosses
  The Maya (Classic murals, Bonampak; the Postclassic):
    pakal            the small round shield of hide over wood, a jaguar pelt painted on it, a red rim, a fringe of
                     quetzal feathers below
    flexible_shield  the rectangular flexible shield of quilted cotton and hide, striped, feather tassels on its foot
"""
import math

from armorgen import Canvas, Cube, Faces, Model, Part
from japan import B, G, W
from seljuk import STEEL

SCALE = 2
TEX = (64, 64)

CRIMSON = [(84, 14, 20), (118, 22, 28), (150, 30, 36), (176, 46, 48)]
BLUE = [(22, 34, 82), (32, 50, 112), (46, 68, 140)]
RED = [(110, 24, 22), (146, 34, 30), (172, 50, 42)]
WHITE = [(196, 192, 180), (222, 218, 206), (240, 236, 226)]
WOOD = [(96, 66, 38), (122, 86, 52), (146, 106, 66), (170, 128, 84)]
PALE_WOOD = [(150, 116, 74), (176, 140, 94), (198, 162, 112), (214, 182, 132)]
RAWHIDE = [(150, 120, 84), (178, 146, 106)]
CANE = [W[0], W[1], W[2]]
THREAD_RED, THREAD_BLUE = (150, 32, 30), (36, 52, 120)
LEATHER = [B[0], B[1], B[2], B[3]]
BLACK = (24, 22, 22)


class ShieldModel(Model):
    """Epic Knights' shield parts: 'plate' (the shield, posed as its own) and 'handle' (the grip behind)."""

    def __init__(self, java_name, location, plate, handle, plate_pivot=(0, 0, 0)):
        self.java_name, self.location, self.tex = java_name, location, TEX
        self.roots = [Part("plate", pivot=plate_pivot, cubes=plate), Part("handle", cubes=handle)]

    def paint(self):
        canvas = Canvas((self.tex[0] * SCALE, self.tex[1] * SCALE))
        for _, c in self.cubes():
            if c.paint:
                c.paint(Faces(canvas, c, SCALE))
        return canvas


class Outline:
    """A shield's outline: the width of each one-unit row from the top, centred on x = 0; y0 the top."""

    def __init__(self, widths, y0):
        self.widths, self.y0 = widths, y0
        self.W = max(widths)
        self.H = len(widths)

    def inside(self, gx, gy):
        """Texel (gx, gy) of the whole front (gx from the left edge of the widest row, gy from the top)."""
        row = int(gy // SCALE)
        if gy < 0 or row >= self.H:
            return False
        half = self.widths[row] * SCALE / 2
        return abs(gx + 0.5 - self.W * SCALE / 2) <= half

    def edge(self, gx, gy, k=2):
        return self.inside(gx, gy) and not all(self.inside(gx + dx, gy + dy) for dx, dy in
                                               ((k, 0), (-k, 0), (0, k), (0, -k), (k - 1, k - 1), (1 - k, k - 1), (k - 1, 1 - k), (1 - k, 1 - k)))

    def slices(self, z0, depth, front, back, rim, tag):
        """Boxes for runs of equal-width rows; each paints its part of the front and back designs."""
        out, r = [], 0
        while r < self.H:
            w = self.widths[r]
            n = 1
            while r + n < self.H and self.widths[r + n] == w:
                n += 1
            ox, oy = -w / 2, self.y0 + r
            out.append(Cube((ox, oy, z0), (w, n, depth), paint=self._painter(ox, oy, front, back, rim), tag=f"{tag}{r}"))
            r += n
        return out

    def _painter(self, ox, oy, front, back, rim):
        gx0 = (ox + self.W / 2) * SCALE
        gy0 = (oy - self.y0) * SCALE

        def paint(f):
            for s in range(f.strip_w):
                face, i = f.face_of(s)
                for t in range(f.h):
                    if face == "front":
                        rgb = front(self, gx0 + i, gy0 + t)
                    elif face == "back":
                        rgb = back(self, gx0 + (f.w - 1 - i), gy0 + t)
                    else:
                        rgb = rim
                    if rgb is not None:
                        f.side(s, t, f.noise(rgb, 2))
            for i in range(f.w):
                for j in range(f.d):
                    f.top(i, j, rim)
                    f.bottom(i, j, rim)
        return paint


def round_widths(d):
    """A disc d units across, as rows of whole widths."""
    r = d / 2
    return [max(1, round(2 * math.sqrt(max(0.0, r * r - (y + 0.5 - r) ** 2)))) for y in range(d)]


def kite_widths(w, h, round_rows=5):
    """A kite: a rounded top, widest below it, then tapering to a point."""
    rows = []
    for y in range(h):
        if y < round_rows:
            t = (round_rows - y - 0.5) / round_rows
            rows.append(max(3, round(w * math.sqrt(max(0.0, 1 - t * t)))))
        else:
            k = (y - round_rows) / (h - round_rows - 1)
            rows.append(max(1, round(w - (w - 1) * k)))
    return rows


def polar(o, gx, gy, cy_units):
    """Distance (texels) and angle of (gx, gy) from the centre of a round shield whose centre row is cy_units."""
    cx = o.W * SCALE / 2
    cy = cy_units * SCALE
    dx, dy = gx + 0.5 - cx, gy + 0.5 - cy
    return math.hypot(dx, dy), math.atan2(dy, dx)


# ---------------------------------------------------------------------- the paintings

def back_wood(planks=6):
    """The back: planks, a leather strap across."""
    def back(o, gx, gy):
        if not o.inside(gx, gy):
            return None
        if o.edge(gx, gy, 1):
            return LEATHER[0]
        if abs(gy - o.H * SCALE * 0.45) < 2:
            return LEATHER[2]
        return WOOD[0] if gx % (planks * 2) == 0 else WOOD[1] if (gx // 3 + gy // 7) % 3 else WOOD[2]
    return back


def back_leather(o, gx, gy):
    if not o.inside(gx, gy):
        return None
    if o.edge(gx, gy, 1):
        return LEATHER[0]
    return LEATHER[1] if (gx * 3 + gy * 5) % 11 else LEATHER[2]


def skoutarion_front(o, gx, gy):
    if not o.inside(gx, gy):
        return None
    if o.edge(gx, gy, 2):
        return WHITE[2] if (gx + gy) % 4 == 0 else G[3] if (gx + gy) % 2 else G[2]     # a gilt rim set with pearls
    w, h = o.W * SCALE, o.H * SCALE
    cx = w / 2
    vert = abs(gx + 0.5 - cx) <= 2 and h * 0.14 <= gy <= h * 0.66
    horiz = abs(gy + 0.5 - h * 0.30) <= 2 and abs(gx + 0.5 - cx) <= w * 0.30
    if vert or horiz:
        flare = (abs(gx + 0.5 - cx) <= 3 and (gy < h * 0.14 + 3 or gy > h * 0.66 - 3)) or \
                (abs(gy + 0.5 - h * 0.30) <= 3 and abs(gx + 0.5 - cx) > w * 0.30 - 3)
        return G[4] if flare else G[3] if (gx + gy) % 3 else G[2]
    near = (abs(gx + 0.5 - cx) <= 3 and h * 0.14 - 1 <= gy <= h * 0.66 + 1) or \
           (abs(gy + 0.5 - h * 0.30) <= 3 and abs(gx + 0.5 - cx) <= w * 0.30 + 1)
    if near:
        return G[1]                                                                     # the cross outlined
    return CRIMSON[2] if (gx * 7 + gy * 3) % 9 else CRIMSON[1]


def aspis_front(cy):
    def front(o, gx, gy):
        if not o.inside(gx, gy):
            return None
        r, a = polar(o, gx, gy, cy)
        R = o.W * SCALE / 2
        if r > R - 2:
            return STEEL[1]                                                             # an iron rim
        if r > R - 7:
            pearl = abs(r - (R - 4.5)) < 1.2 and int((a + math.pi) * R / 3) % 2 == 0
            return WHITE[2] if pearl else RED[1]                                        # a red band with pearls
        if r < 5:
            return WHITE[1]
        ray = abs(((a + math.pi) / (math.pi / 4)) % 1 - 0.5) > 0.44                      # eight white rays
        return WHITE[0] if ray else BLUE[1] if (gx + gy) % 5 else BLUE[0]
    return front


def varangian_front(cy):
    def front(o, gx, gy):
        if not o.inside(gx, gy):
            return None
        r, a = polar(o, gx, gy, cy)
        R = o.W * SCALE / 2
        if r > R - 2:
            return RAWHIDE[0] if (gx + gy) % 3 else RAWHIDE[1]                          # the rawhide rim, stitched
        seam = gx % 8 == 0                                                              # the planks
        quarter = int(((a + math.pi) / (math.pi / 2)) % 4)
        base = RED if quarter % 2 == 0 else WHITE
        rgb = base[1] if (gy * 3 + gx) % 7 else base[0]
        return tuple(max(0, c - 28) for c in rgb) if seam else rgb
    return front


def kalkan_front(cy):
    def front(o, gx, gy):
        if not o.inside(gx, gy):
            return None
        r, a = polar(o, gx, gy, cy)
        R = o.W * SCALE / 2
        if r > R - 2:
            return LEATHER[0]                                                           # bound in leather
        coil = int(r) % 2                                                               # the cane, coiled
        sector = ((a + math.pi) / (math.pi / 6)) % 1
        if sector < 0.22 and r > 6:
            return THREAD_RED if coil else (120, 24, 24)                                # threads binding the coils
        if abs(r - R * 0.62) < 1.5:
            return THREAD_BLUE
        return CANE[2] if coil else CANE[1]
    return front


def sipar_front(cy):
    def front(o, gx, gy):
        if not o.inside(gx, gy):
            return None
        r, a = polar(o, gx, gy, cy)
        R = o.W * SCALE / 2
        if r > R - 2:
            return LEATHER[0]
        if r > R - 4:
            return G[3] if int((a + math.pi) * R / 2.5) % 2 else G[2]                   # a gilt band
        # the eight-pointed star: two squares, one turned by 45 degrees
        cx, cyy = o.W * SCALE / 2, cy * SCALE
        dx, dy = gx + 0.5 - cx, gy + 0.5 - cyy
        k = R * 0.36
        sq1 = max(abs(dx), abs(dy)) <= k
        sq2 = (abs(dx) + abs(dy)) / math.sqrt(2) <= k
        if sq1 or sq2:
            outline = not (max(abs(dx), abs(dy)) <= k - 1.5 or (abs(dx) + abs(dy)) / math.sqrt(2) <= k - 1.5)
            return G[2] if outline else G[3]
        return CRIMSON[1] if (gx * 5 + gy * 3) % 7 else CRIMSON[0]
    return front


def tedate_front(o, gx, gy):
    if not o.inside(gx, gy):
        return None
    w, h = o.W * SCALE, o.H * SCALE
    plank = w / 3
    if gx % int(plank) == 0 and gx > 0:
        return PALE_WOOD[0]                                                             # the seams
    if o.edge(gx, gy, 1) or abs(gy - h * 0.12) < 1 or abs(gy - h * 0.88) < 1:
        return BLACK if (gx // 2) % 2 else (40, 36, 34)                                  # black iron bands
    cx, cy = w / 2, h * 0.36
    r = math.hypot(gx + 0.5 - cx, gy + 0.5 - cy)
    R = w * 0.30
    if R - 2.2 <= r <= R:
        return BLACK                                                                    # the mon: a ring...
    if r < R - 2.2 and (abs(gx + 0.5 - cx) <= 1.2 or abs(gy + 0.5 - cy) <= 1.2):
        return BLACK                                                                    # ...and a cross
    return PALE_WOOD[2] if (gy * 7 + gx * 2) % 11 else PALE_WOOD[3]


def boss(f):
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(STEEL[3] if t == 0 else STEEL[1], 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, STEEL[3])
            f.bottom(i, j, STEEL[1])


def boss_front(f):
    """The boss's face: lit at the top left."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "front":
                rgb = STEEL[4] if i + t < 3 else STEEL[3] if i + t < f.w else STEEL[2]
            else:
                rgb = STEEL[2] if t == 0 else STEEL[1]
            f.side(s, t, f.noise(rgb, 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, STEEL[3])
            f.bottom(i, j, STEEL[1])


def handle(f):
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(LEATHER[1] if (s + t) % 4 else LEATHER[0], 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, LEATHER[2])
            f.bottom(i, j, LEATHER[0])


def batten(f):
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(PALE_WOOD[0] if t == f.h - 1 else PALE_WOOD[1], 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, PALE_WOOD[1])
            f.bottom(i, j, PALE_WOOD[0])


HANDLE = [Cube((-1, -3, -1), (2, 6, 6), paint=handle, tag="handle")]


def round_shield(java_name, loc, front_fn, back_fn, rim, dome=False, d=15):
    o = Outline(round_widths(d), -10)
    cy = d / 2                                                   # the centre row (units from the top)
    plate = o.slices(-2, 1, front_fn(cy), back_fn, rim, "plate")
    zc = -2
    if dome:
        inner = Outline(round_widths(9), -10 + (d - 9) / 2)
        # the domed centre: the same painting, one step proud
        off = (d - 9) / 2

        def shifted(oi, gx, gy):
            return front_fn(cy)(o, gx + off * SCALE, gy + off * SCALE)
        plate += inner.slices(-2.6, 1, shifted, lambda oi, gx, gy: None, rim, "dome")
        zc = -2.6
    c = -10 + cy
    plate += [Cube((-1.5, c - 1.5, zc - 1), (3, 3, 1), paint=boss_front, tag="boss"),
              Cube((-0.5, c - 0.5, zc - 1.6), (1, 1, 1), paint=boss, tag="knob")]
    return ShieldModel(java_name, loc, plate, HANDLE)


def skoutarion():
    o = Outline(kite_widths(13, 21), -9)
    return ShieldModel("SkoutarionModel", "skoutarion", o.slices(-2, 1, skoutarion_front, back_leather, G[1], "plate"), HANDLE,
                       plate_pivot=(0, 2, 0))


def tedate():
    o = Outline([12] * 19, -9)
    plate = o.slices(-2, 1, tedate_front, back_wood(4), PALE_WOOD[0], "plate")
    plate += [Cube((-6, -5, -1), (12, 1, 1), paint=batten, tag="batten_top"),
              Cube((-6, 6, -1), (12, 1, 1), paint=batten, tag="batten_bottom")]
    return ShieldModel("TedateModel", "tedate", plate, HANDLE, plate_pivot=(0, 2, 0))


HIDE = [(34, 24, 18), (52, 36, 26), (70, 50, 36), (96, 72, 52)]
JAG = [(196, 140, 62), (172, 116, 46), (120, 76, 30), (34, 26, 20)]
QUETZ = [(22, 96, 64), (34, 132, 84), (70, 172, 104)]
MAYA_RED = [(120, 34, 26), (168, 52, 38)]


def dhal_front(cy):
    def front(o, gx, gy):
        if not o.inside(gx, gy):
            return None
        r, a = polar(o, gx, gy, cy)
        R = o.W * SCALE / 2
        if r > R - 2:
            return HIDE[0]
        if abs(r - (R - 4)) < 1:
            return G[2] if int((a + math.pi) * R / 2) % 2 else G[3]                      # a gilt painted band
        sheen = (gx - gy) / (o.W * SCALE)                                              # the lacquer catches the light
        return HIDE[3] if -0.15 < sheen < -0.05 and r < R * 0.7 else HIDE[2] if (gx * 3 + gy * 5) % 9 else HIDE[1]
    return front


def dhal():
    d = 15
    o = Outline(round_widths(d), -10)
    cy = d / 2
    plate = o.slices(-2, 1, dhal_front(cy), back_leather, HIDE[0], "plate")
    inner = Outline(round_widths(9), -10 + (d - 9) / 2)
    off = (d - 9) / 2
    plate += inner.slices(-2.6, 1, lambda oi, gx, gy: dhal_front(cy)(o, gx + off * SCALE, gy + off * SCALE),
                          lambda oi, gx, gy: None, HIDE[0], "dome")
    c = -10 + cy
    for i, (dx, dy) in enumerate(((-2, -2), (2, -2), (-2, 2), (2, 2))):          # the four bosses
        plate.append(Cube((dx - 0.5, c + dy - 0.5, -3.6), (1, 1, 1), paint=gilt_boss, tag=f"boss{i}"))
    return ShieldModel("DhalModel", "dhal", plate, HANDLE)


def gilt_boss(f):
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            f.side(s, t, G[4] if face == "front" and i + t < 2 else G[3] if face == "front" else G[2])
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, G[3])
            f.bottom(i, j, G[1])


def pakal_front(cy):
    def front(o, gx, gy):
        if not o.inside(gx, gy):
            return None
        r, a = polar(o, gx, gy, cy)
        R = o.W * SCALE / 2
        if r > R - 3:
            return MAYA_RED[0] if r > R - 1.5 else MAYA_RED[1]                          # a red-painted rim
        # the jaguar pelt: rosettes of black spots round a darker heart
        cell = 7
        ux, uy = gx // cell, gy // cell
        jx = gx - ux * cell - 3 - ((ux * 5 + uy * 3) % 3 - 1)
        jy = gy - uy * cell - 3 - ((ux * 3 + uy * 7) % 3 - 1)
        dd = math.hypot(jx, jy)
        if 1.8 < dd < 3.0 and int((math.atan2(jy, jx) + math.pi) / (math.pi / 3)) % 2 == 0:
            return JAG[3]
        if dd <= 1.6:
            return JAG[2]
        return JAG[0] if (gx * 5 + gy * 3) % 7 else JAG[1]
    return front


def feathers(f):
    """A fringe of feathers, hanging: green with lighter tips, darker spines."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            tip = t >= f.h - 2
            rgb = QUETZ[2] if tip else QUETZ[1] if (i // 2) % 2 else QUETZ[0]
            if face in ("front", "back") and i % 4 == 3 and not tip:
                rgb = (20, 70, 46)
            if tip and i % 4 == 0:
                continue
            f.side(s, t, f.noise(rgb, 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, QUETZ[0])
            f.bottom(i, j, QUETZ[1])


def pakal():
    d = 13
    o = Outline(round_widths(d), -9)
    cy = d / 2
    plate = o.slices(-2, 1, pakal_front(cy), back_leather, MAYA_RED[0], "plate")
    plate.append(Cube((-4.5, -9 + d - 1, -2), (9, 5, 1), paint=feathers, tag="fringe"))
    return ShieldModel("PakalModel", "pakal", plate, HANDLE)


def flexible_front(o, gx, gy):
    if not o.inside(gx, gy):
        return None
    w, h = o.W * SCALE, o.H * SCALE
    if o.edge(gx, gy, 1):
        return HIDE[1]
    band = int(gy / (h / 7))
    if band in (0, 6):                                                                  # the cotton ends, red-striped
        return MAYA_RED[1] if gy % 4 < 2 else (226, 220, 204)
    if band == 3:
        return JAG[0] if (gx // 3 + gy // 3) % 3 else JAG[3]
    return (226, 220, 204) if (gx + gy) % 9 else (196, 188, 170)                        # quilted cotton


def flexible_shield():
    o = Outline([11] * 16, -9)
    plate = o.slices(-2, 1, flexible_front, back_leather, HIDE[1], "plate")
    plate.append(Cube((-5.5, 7, -2), (11, 4, 1), paint=feathers, tag="fringe"))
    return ShieldModel("FlexibleShieldModel", "flexible_shield", plate, HANDLE, plate_pivot=(0, 2, 0))


# id: (model, Epic Knights item model it is held like, tier, ShieldType.of(durability, material factor, weight,
#      max block damage), name, recipe)
_PL, _LS = "#minecraft:planks", "#magistuarmory:leather_strips"
SHIELDS = {
    "skoutarion": (skoutarion, "kiteshield", "IRON", "370, 0.8F, 5.0F, 10.0F, true, true", "Skoutarion",
                   (["lgl", "lpl", " l "], {"l": "minecraft:leather", "g": "minecraft:gold_nugget", "p": _PL})),
    "aspis": (lambda: round_shield("AspisModel", "aspis", aspis_front, back_wood(), STEEL[1]), "roundshield", "IRON",
              "350, 0.8F, 3.0F, 7.0F, true, true", "Byzantine Round Shield",
              (["pbp", "pip", " p "], {"p": _PL, "i": "#c:ingots/iron", "b": "minecraft:blue_dye"})),
    "varangian_shield": (lambda: round_shield("VarangianShieldModel", "varangian_shield", varangian_front, back_wood(), RAWHIDE[0]),
                         "roundshield", "IRON", "360, 0.8F, 3.5F, 8.0F, true, true", "Varangian Shield",
                         (["prp", "pip", " p "], {"p": _PL, "i": "#c:ingots/iron", "r": "minecraft:red_dye"})),
    "kalkan": (lambda: round_shield("KalkanModel", "kalkan", kalkan_front, back_leather, LEATHER[0], dome=True, d=13),
               "roundshield", "IRON", "340, 0.8F, 2.5F, 7.0F, true, true", "Kalkan",
               (["csc", "cic", " c "], {"c": "minecraft:sugar_cane", "s": "minecraft:string", "i": "#c:ingots/iron"})),
    "sipar": (lambda: round_shield("SiparModel", "sipar", sipar_front, back_leather, LEATHER[0], dome=True),
              "roundshield", "STEEL", "360, 0.8F, 3.5F, 8.0F, true, true", "Sipar",
              (["lgl", "lpl", " l "], {"l": "minecraft:leather", "g": "minecraft:gold_ingot", "p": _PL})),
    "tedate": (tedate, "kiteshield", "WOOD", "330, 0.8F, 4.0F, 9.0F, true, true", "Tedate",
               (["ppp", "pbp", "ppp"], {"p": _PL, "b": "minecraft:black_dye"})),
    "dhal": (dhal, "roundshield", "STEEL", "360, 0.8F, 3.0F, 8.0F, true, true", "Dhal",
             (["lgl", "lhl", " l "], {"l": "minecraft:leather", "g": "minecraft:gold_nugget", "h": "minecraft:black_dye"})),
    "pakal": (pakal, "roundshield", "WOOD", "300, 0.8F, 2.5F, 6.0F, true, true", "Pakal",
              (["lol", "lpl", "fff"], {"l": "minecraft:leather", "o": "minecraft:orange_dye", "p": _PL, "f": "minecraft:feather"})),
    "flexible_shield": (flexible_shield, "kiteshield", "WOOD", "280, 0.8F, 2.0F, 6.0F, true, true", "Maya Flexible Shield",
                        (["ww", "ll", "ff"], {"w": "minecraft:white_wool", "l": "minecraft:leather", "f": "minecraft:feather"})),
}

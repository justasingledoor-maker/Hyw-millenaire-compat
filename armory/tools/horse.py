"""
Horse armour: textures for the vanilla horse model (HorseModel.createBodyMesh, 1.21.1, drawn by HorseArmorLayer on a
model inflated by 0.1), the way Epic Knights' barding is made. Each box is placed at its vanilla texture offset, so the
painters draw on exactly the texels the game uses. Horse armour is one texture (no overlay): a dyeable one is tinted all
over, so the felt barding is all greys.

  cataphract_barding:      lamellar over the whole horse to the knees, a steel chamfron, red leather edging (the
                           kataphraktos' horse, after the Praecepta Militaria: covered to the knees, eyes and nostrils free)
  cataphract_half_barding: a steel chamfron, a scale crinet on the neck, a scale peytral over the chest and shoulders
  felt_barding:            quilted felt trappers over body, neck and upper legs (dyeable)
"""
from armorgen import Canvas, Cube, Faces, Part
from japan import G
from seljuk import STEEL, lamella_px, mail_px
from byzantine import LEATHER_RED, scale_px


class HorseModel:
    """The vanilla horse's boxes at their texture offsets (body, neck with head, mane and muzzle, four legs, tail)."""

    def __init__(self, paints):
        def box(name, origin, size, uv, inflate=0.1):
            c = Cube(origin, size, inflate=inflate, paint=paints.get(name), tag=name)
            c.uv = uv
            return c
        self.tex = (64, 64)
        body = Part("body", pivot=(0, 11, 5), cubes=[box("body", (-5, -8, -17), (10, 10, 22), (0, 32), 0.15)], children=[
            Part("tail", pivot=(0, -5, 2), rot=(0.5236, 0, 0), cubes=[box("tail", (-1.5, 0, 0), (3, 14, 4), (42, 36))]),
        ])
        neck = Part("head_parts", pivot=(0, 4, -12), rot=(0.5236, 0, 0), cubes=[box("neck", (-2.05, -6, -2), (4, 12, 7), (0, 35))],
                    children=[
                        Part("head", cubes=[box("head", (-3, -11, -2), (6, 5, 7), (0, 13))]),
                        Part("mane", cubes=[box("mane", (-1, -11, 5.01), (2, 16, 2), (56, 36))]),
                        Part("upper_mouth", cubes=[box("muzzle", (-2, -11, -7), (4, 5, 5), (0, 25))]),
                    ])
        legs = [Part(name, pivot=pivot, cubes=[box("leg", (x0, -1.01, z0), (4, 11, 4), (48, 21))])
                for name, pivot, x0, z0 in (("left_hind_leg", (4, 14, 7), -3, -1), ("right_hind_leg", (-4, 14, 7), -1, -1),
                                            ("left_front_leg", (4, 14, -12), -3, -1.9), ("right_front_leg", (-4, 14, -12), -1, -1.9))]
        self.roots = [body, neck] + legs

    def cubes(self):
        for r in self.roots:
            for p in r.walk():
                for c in p.cubes:
                    yield p, c

    def paint(self):
        canvas = Canvas(self.tex)
        done = set()
        for _, c in self.cubes():
            if c.paint and c.uv not in done:                  # the four legs share one texture area
                done.add(c.uv)
                c.paint(Faces(canvas, c))
        return canvas

    def preview_json(self):
        def part(p):
            return {"name": p.name, "pivot": p.pivot, "rot": p.rot,
                    "cubes": [{"origin": c.origin, "size": c.size, "inflate": c.inflate, "uv": c.uv} for c in p.cubes],
                    "children": [part(ch) for ch in p.children]}
        return {"tex": self.tex, "parts": [part(r) for r in self.roots]}


# ---------------------------------------------------------------------- painters

EDGE = LEATHER_RED


def lamellar_cover(rows=None, edge=True):
    """Lamellae over every side face (or the top `rows` rows), a red leather edge at the bottom of the covered part."""
    def paint(f):
        h = f.h if rows is None else min(rows, f.h)
        for s in range(f.strip_w):
            for t in range(h):
                if edge and t == h - 1:
                    rgb = EDGE[2] if s % 2 else EDGE[1]
                else:
                    rgb = lamella_px(s, t)[0]
                f.side(s, t, f.noise(rgb, 2))
        if rows is None or rows >= f.h:
            for i in range(f.w):
                for j in range(f.d):
                    f.top(i, j, f.noise(lamella_px(i, j)[0], 2))
    return paint


def body_lamellar(f):
    """The body: lamellae all round and over the back, red edging along the bottom of the flanks."""
    for s in range(f.strip_w):
        for t in range(f.h):
            rgb = (EDGE[2] if s % 2 else EDGE[1]) if t == f.h - 1 else lamella_px(s, t)[0]
            f.side(s, t, f.noise(rgb, 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(lamella_px(i, j)[0], 2))
            f.bottom(i, j, STEEL[0])


def chamfron(f, eye_holes=True):
    """The steel chamfron over the face: lit plates down the front and top, a gilt boss on the brow; eye holes left free."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face in ("right", "left"):
                if t >= 2 and eye_holes:
                    continue                                   # the cheeks and the eyes stay bare
                rgb = STEEL[2] if t == 0 else STEEL[1]
            elif face == "front":
                rgb = G[3] if (t == 1 and i in (f.w // 2 - 1, f.w // 2)) else (STEEL[4] if t == 0 else STEEL[3] if i % 2 else STEEL[2])
            else:
                rgb = STEEL[1]
            f.side(s, t, f.noise(rgb, 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(STEEL[4] if i in (f.w // 2 - 1, f.w // 2) else STEEL[3], 2))


def muzzle_plate(f):
    """The chamfron's lower part over the nose, the nostrils free (only the top and the upper front)."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        for t in range(f.h):
            if face == "front" and t <= 2:
                f.side(s, t, f.noise(STEEL[3] if i % 2 else STEEL[2], 2))
            elif face in ("right", "left") and t == 0:
                f.side(s, t, f.noise(STEEL[2], 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(STEEL[4] if i in (1, 2) else STEEL[3], 2))


def scale_cover(f):
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(scale_px(s, t)[0], 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(scale_px(i, j)[0], 2))


def peytral(f):
    """Scale over the chest and the front of the flanks; red leather edging; the rest of the body bare."""
    for s in range(f.strip_w):
        face, i = f.face_of(s)
        front_part = face == "front" or (face == "right" and i >= f.d - 6) or (face == "left" and i < 6)
        if not front_part:
            continue
        for t in range(f.h):
            rgb = (EDGE[2] if s % 2 else EDGE[1]) if t == f.h - 1 else scale_px(s, t)[0]
            f.side(s, t, f.noise(rgb, 2))


def felt_px(s, t):
    """Quilted felt (greys: the dye tints it), in diamonds, with a fringe at the bottom."""
    return (214, 214, 214) if (s + t) % 4 == 0 or (s - t) % 4 == 0 else (188, 188, 188)


def felt_cover(rows=None):
    def paint(f):
        h = f.h if rows is None else min(rows, f.h)
        for s in range(f.strip_w):
            for t in range(h):
                if t == h - 1:
                    rgb = (240, 240, 240) if s % 2 else (150, 150, 150)       # the fringe
                else:
                    rgb = felt_px(s, t)
                f.side(s, t, f.noise(rgb, 3))
        if rows is None or rows >= f.h:
            for i in range(f.w):
                for j in range(f.d):
                    f.top(i, j, f.noise(felt_px(i, j), 3))
    return paint


def felt_body(f):
    for s in range(f.strip_w):
        for t in range(f.h):
            rgb = ((240, 240, 240) if s % 2 else (150, 150, 150)) if t == f.h - 1 else \
                (226, 226, 226) if t == 0 else felt_px(s, t)
            f.side(s, t, f.noise(rgb, 3))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(felt_px(i, j), 3))


def mail_neck(f):
    for s in range(f.strip_w):
        for t in range(f.h):
            f.side(s, t, f.noise(mail_px(s, t), 2))
    for i in range(f.w):
        for j in range(f.d):
            f.top(i, j, f.noise(mail_px(i, j), 2))


# ---------------------------------------------------------------------- the bardings

# id, painters by box, ArmorMaterials holder (the horse's armour value: LEATHER 3, IRON 5, DIAMOND 11), dyeable, default dye
BARDINGS = [
    ("cataphract_barding", {"body": body_lamellar, "neck": lamellar_cover(), "mane": lamellar_cover(), "head": chamfron,
                            "muzzle": muzzle_plate, "leg": lamellar_cover(rows=6)}, "DIAMOND", False, None),
    ("cataphract_half_barding", {"body": peytral, "neck": scale_cover, "mane": scale_cover, "head": chamfron,
                                 "muzzle": muzzle_plate}, "IRON", False, None),
    ("felt_barding", {"body": felt_body, "neck": felt_cover(), "mane": felt_cover(), "leg": felt_cover(rows=5)}, "LEATHER", True,
     0x8E2A2A),
]

NAMES = {
    "cataphract_barding": "Kataphraktos Barding",
    "cataphract_half_barding": "Scale Peytral and Chamfron",
    "felt_barding": "Quilted Felt Barding",
}

RECIPES = {
    "cataphract_barding": (["  r", "rrr", "r r"], {"r": "#magistuarmory:lamellar_rows/steel"}),
    "cataphract_half_barding": (["  p", "ppp", "l l"], {"p": "#c:plates/steel", "l": "#magistuarmory:leather_strips"}),
    "felt_barding": (["  f", "fff", "f f"], {"f": "#magistuarmory:woolen_fabrics"}),
}
